package app.deft.ui.workspace

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.deft.DeftApp
import app.deft.bridge.BridgeService
import app.deft.catalog.Capability
import app.deft.permissions.OsPermissionHelper
import app.deft.ui.consent.ConsentActivity
import app.deft.ui.host.WebViewHostActivity
import app.deft.ui.theme.AppDeftTheme
import app.deft.ui.theme.applyDeftChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WorkspaceLaunchActivity : ComponentActivity() {
    private var askedRuntime = false
    private var askedSmsRole = false
    private var askedDialerRole = false
    private var caps: List<Capability> = emptyList()
    private var webAppId: String? = null
    private var message by mutableStateOf("Opening…")

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { continueLaunch() }

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { continueLaunch() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyDeftChrome(DeftApp.instance.configStore.config.value)
        val slug = intent.getStringExtra(EXTRA_SLUG) ?: run { finish(); return }
        setContent {
            AppDeftTheme {
                Scaffold { pad ->
                    Column(
                        Modifier.padding(pad).padding(16.dp).fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(message, style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
        lifecycleScope.launch {
            val store = DeftApp.instance.myApps
            val repo = DeftApp.instance.repository
            val installed = withContext(Dispatchers.IO) {
                if (!store.hasProject(slug)) {
                    Result.failure(IllegalStateException("Local app $slug has no manifest.json"))
                } else {
                    runCatching { repo.ensureWorkspaceApp(slug) }
                }
            }.getOrElse { err ->
                message = err.message ?: "Could not open this app."
                return@launch
            }
            val pending = withContext(Dispatchers.IO) {
                repo.ungrantedCapabilities(installed.id, installed.requiredCapabilities)
            }
            val needsDeps = withContext(Dispatchers.IO) {
                app.deft.workspace.AppDeps.missing(this@WorkspaceLaunchActivity, installed.id, slug).isNotEmpty() ||
                    // Asset packs not downloaded yet go through the same screen.
                    runCatching { app.deft.workspace.AssetPacks.pendingFor(this@WorkspaceLaunchActivity, installed.id, slug) }
                        .getOrDefault(emptyList()).isNotEmpty()
            }
            if (pending.isNotEmpty() || needsDeps) {
                val existingGrants = withContext(Dispatchers.IO) { repo.grantsForApp(installed.id) }
                startActivity(
                    ConsentActivity.workspaceIntent(
                        this@WorkspaceLaunchActivity,
                        slug,
                        installed.name,
                        installed.startUrl,
                        installed.origin,
                        installed.manifestUrl,
                        pending,
                        newCapsOnly = existingGrants.isNotEmpty(),
                    ),
                )
                finish()
                return@launch
            }
            webAppId = installed.id
            caps = withContext(Dispatchers.IO) { repo.grantedCapabilities(installed.id) }
            if (!store.projectDir(slug).canRead()) {
                message = "App files are not readable."
                return@launch
            }
            continueLaunch()
        }
    }

    private fun continueLaunch() {
        if (isFinishing) return
        val missing = OsPermissionHelper.missingRuntime(this, caps)
        if (missing.isNotEmpty() && !askedRuntime) {
            askedRuntime = true
            permLauncher.launch(missing.toTypedArray())
            return
        }
        if (OsPermissionHelper.needsSmsRole(this, caps) && !askedSmsRole) {
            askedSmsRole = true
            val intent = OsPermissionHelper.smsRoleIntent(this)
            if (intent != null) {
                roleLauncher.launch(intent)
                return
            }
        }
        if (OsPermissionHelper.needsDialerRole(this, caps) && !askedDialerRole) {
            askedDialerRole = true
            val intent = OsPermissionHelper.dialerRoleIntent(this)
            if (intent != null) {
                roleLauncher.launch(intent)
                return
            }
        }
        val id = webAppId ?: run {
            message = "Install did not finish."
            return
        }
        val slug = intent.getStringExtra(EXTRA_SLUG)
        if (slug == null || !DeftApp.instance.myApps.hasProject(slug)) {
            message = "App files are not on disk yet. Apply apps.create first."
            return
        }
        BridgeService.start(this)
        lifecycleScope.launch {
            val repo = DeftApp.instance.repository
            withContext(Dispatchers.IO) {
                repo.touchWorkspaceOpened(id)
                val entity = DeftApp.instance.database.webApps().get(id)
                if (entity != null) repo.recordWebLaunch(entity)
            }
            startActivity(WebViewHostActivity.intent(this@WorkspaceLaunchActivity, id))
            finish()
        }
    }

    companion object {
        const val EXTRA_SLUG = "slug"

        fun intent(context: Context, slug: String): Intent =
            Intent(context, WorkspaceLaunchActivity::class.java).putExtra(EXTRA_SLUG, slug)
    }
}
