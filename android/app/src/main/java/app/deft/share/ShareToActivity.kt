package app.deft.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.deft.DeftApp
import app.deft.R
import app.deft.catalog.Capability
import app.deft.catalog.GrantLevel
import app.deft.catalog.isGranted
import app.deft.data.InstalledWebApp
import app.deft.ui.host.WebViewHostActivity
import app.deft.ui.theme.AppDeftTheme
import androidx.core.content.IntentCompat
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ShareToActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val share = parseShare(intent) ?: run { finish(); return }
        setContent {
            var apps by remember { mutableStateOf<List<InstalledWebApp>>(emptyList()) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                val repo = DeftApp.instance.repository
                apps = repo.allApps().filter { app ->
                    Capability.SHARE_RECEIVE in app.requiredCapabilities &&
                        repo.grantLevel(app.id, Capability.SHARE_RECEIVE).isGranted()
                }
                if (apps.isEmpty()) finish()
            }
            AppDeftTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(getString(R.string.share_to_label)) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    },
                ) { padding ->
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        items(apps, key = { it.id }) { app ->
                            Text(
                                text = app.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            deliver(app, share)
                                            startActivity(WebViewHostActivity.intent(this@ShareToActivity, app.id))
                                            finish()
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun deliver(app: InstalledWebApp, share: SharePayload) {
        val uris = share.uris.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        val items = InboxDelivery.copy(this, app.id, uris, share.type)
        if (uris.isNotEmpty() && items.isEmpty()) {
            InboxDelivery.fullMessage(this, app.id, app.name)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }
        val payload = buildJsonObject {
            put("type", JsonPrimitive(share.type))
            put("text", JsonPrimitive(share.text.orEmpty()))
            put("items", InboxDelivery.itemsJson(items))
        }.toString()
        InboxDelivery.emit(app.id, "intents.shareReceived", payload)
    }

    private data class SharePayload(
        val type: String,
        val text: String?,
        val uris: List<String>,
    )

    private fun parseShare(intent: Intent): SharePayload? {
        val action = intent.action ?: return null
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
        val type = intent.type ?: "*/*"
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val uris = mutableListOf<String>()
        when (action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { uris += it.toString() }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.forEach { uris += it.toString() }
            }
        }
        return SharePayload(type, text, uris)
    }

}
