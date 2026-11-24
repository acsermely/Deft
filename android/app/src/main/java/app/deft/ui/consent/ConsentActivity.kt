package app.deft.ui.consent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.deft.DeftApp
import app.deft.R
import app.deft.bridge.BridgeService
import app.deft.bridge.SpecialAsks
import app.deft.catalog.Capability
import app.deft.catalog.CapabilityKind
import app.deft.catalog.Risk
import app.deft.permissions.OsPermissionHelper
import app.deft.registry.ConsentPayload
import app.deft.registry.decodeConsentPayload
import app.deft.registry.encode
import app.deft.ui.OverlayGuard
import app.deft.ui.host.WebViewHostActivity
import app.deft.ui.theme.AppDeftTheme
import app.deft.registry.DeftDependency
import app.deft.registry.DeftTool
import app.deft.agent.ToolRunner
import app.deft.data.InstalledWebApp
import app.deft.workspace.AppDeps
import app.deft.workspace.BundledApps
import app.deft.workspace.MyAppsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConsentActivity : ComponentActivity() {
    private var pendingCaps: List<Capability> = emptyList()
    private var askedRuntime = false
    private var askedSmsRole = false
    private var askedDialerRole = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { continueInstall() }

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { continueInstall() }

    private var onReadyToInstall: (() -> Unit)? = null

    /** Set when this screen is a point-of-use ask for one plain capability (see [pointOfUseIntent]). */
    private var pointOfUseToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayGuard.protect(this)
        enableEdgeToEdge()
        // Read first, so even an early finish() releases the point-of-use waiter in onDestroy.
        pointOfUseToken = intent.getStringExtra(EXTRA_ASK_TOKEN)
        val raw = intent.getStringExtra(EXTRA_MANIFEST_JSON) ?: run { finish(); return }
        val payload = runCatching { decodeConsentPayload(raw) }.getOrElse { finish(); return }
        val name = payload.name
        val startUrl = payload.startUrl
        val origin = payload.origin
        val manifestUrl = payload.manifestUrl
        val http = payload.http
        val slug = intent.getStringExtra(EXTRA_WORKSPACE_SLUG)
        val newCapsOnly = intent.getBooleanExtra(EXTRA_NEW_CAPS, false)
        val skipLaunch = intent.getBooleanExtra(EXTRA_SKIP_LAUNCH, false) || pointOfUseToken != null
        val bundledId = intent.getStringExtra(EXTRA_BUNDLED_ID) ?: payload.bundledId
        val caps = payload.capabilities.mapNotNull { Capability.fromId(it) }
        val allCaps = payload.allCapabilities.mapNotNull { Capability.fromId(it) }
            .ifEmpty { caps }
        // A point-of-use ask shows exactly the one plain capability it was opened for, and nothing else.
        if (pointOfUseToken != null) {
            val only = intent.getStringArrayListExtra(EXTRA_ONLY_CAPABILITIES)?.toSet()
            if (only.isNullOrEmpty() || caps.size != only.size || caps.map { it.id }.toSet() != only ||
                caps.any { it.risk == Risk.SPECIAL }
            ) {
                finish()
                return
            }
        }
        // PARITY G0: special access is never granted here. It is listed under "Asks later" with no
        // checkbox; installing writes it as DENIED and the dashboard sheet is the only way to allow it.
        val normalCaps = caps.filter { it.risk != Risk.SPECIAL }
        val specialCaps = caps.filter { it.risk == Risk.SPECIAL }
        pendingCaps = normalCaps
        val depsAppId = slug?.let { MyAppsStore.webAppId(it) } ?: bundledId?.let { BundledApps.webAppId(it) }
        setContent {
            AppDeftTheme {
                val enabled = remember { mutableStateMapOf<String, Boolean>().apply { normalCaps.forEach { put(it.id, true) } } }
                var reasons by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
                // Capability id -> "This replaces <App> as your <group> app." for exclusive groups held elsewhere.
                var replaces by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
                var error by remember { mutableStateOf<String?>(null) }
                var deps by remember { mutableStateOf<List<DeftDependency>>(emptyList()) }
                var depsChecked by remember { mutableStateOf(false) }
                var origins by remember { mutableStateOf<List<String>>(emptyList()) }
                // PARITY G14: embed origins, approved one by one (ticked by default, untick to refuse).
                var embeds by remember { mutableStateOf<List<String>>(emptyList()) }
                val embedTicked = remember { mutableStateMapOf<String, Boolean>() }
                // W5: agent tools the app declares, listed by name and description; allowing the install allows them.
                var tools by remember { mutableStateOf<List<DeftTool>>(emptyList()) }
                var downloading by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(depsAppId) {
                    // A point-of-use ask has no libraries, network or embed origins to settle: only its capability.
                    if (pointOfUseToken != null) {
                        depsChecked = true
                        return@LaunchedEffect
                    }
                    deps = depsAppId?.let { id ->
                        withContext(Dispatchers.IO) { runCatching { AppDeps.missing(this@ConsentActivity, id, slug) }.getOrDefault(emptyList()) }
                    }.orEmpty()
                    origins = depsAppId?.let { id ->
                        withContext(Dispatchers.IO) {
                            runCatching { DeftApp.instance.repository.unconsentedNetwork(id, slug) }
                                .getOrDefault(emptyList())
                        }
                    }.orEmpty()
                    embeds = depsAppId?.let { id ->
                        withContext(Dispatchers.IO) {
                            runCatching { DeftApp.instance.repository.unconsentedEmbeds(id, slug) }
                                .getOrDefault(emptyList())
                        }
                    }.orEmpty()
                    embeds.forEach { embedTicked.putIfAbsent(it, true) }
                    tools = depsAppId?.let { id ->
                        withContext(Dispatchers.IO) {
                            runCatching { DeftApp.instance.repository.unconsentedTools(id, slug) }
                                .getOrDefault(emptyList())
                        }
                    }.orEmpty()
                    reasons = withContext(Dispatchers.IO) {
                        specialCaps.associate { cap ->
                            cap.id to runCatching {
                                DeftApp.instance.repository.reasonFor(depsAppId ?: "", slug, cap)
                            }.getOrDefault(cap.description)
                        }
                    }
                    replaces = withContext(Dispatchers.IO) {
                        val repo = DeftApp.instance.repository
                        normalCaps.mapNotNull { cap ->
                            val group = cap.exclusiveGroup ?: return@mapNotNull null
                            val holder = runCatching { repo.specialHolder(group) }.getOrNull()
                                ?.takeIf { it != depsAppId } ?: return@mapNotNull null
                            val holderName = runCatching { repo.getApp(holder)?.name }.getOrNull() ?: "another app"
                            cap.id to ConsentText.replacesHolder(holderName, group)
                        }.toMap()
                    }
                    depsChecked = true
                    // Nothing left to ask: an app opened only for libraries or origins already settled.
                    if (caps.isEmpty() && deps.isEmpty() && origins.isEmpty() && embeds.isEmpty() && tools.isEmpty() && depsAppId != null) {
                        if (skipLaunch) setResult(RESULT_OK) else startActivity(WebViewHostActivity.intent(this@ConsentActivity, depsAppId))
                        finish()
                    }
                }
                val onlyDeps = caps.isEmpty() && deps.isNotEmpty()
                val onlyNetwork = caps.isEmpty() && deps.isEmpty() && (origins.isNotEmpty() || embeds.isNotEmpty())
                val onlyTools = caps.isEmpty() && deps.isEmpty() && origins.isEmpty() && embeds.isEmpty() && tools.isNotEmpty()
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
                    Column(
                        Modifier
                            .padding(pad)
                            .padding(16.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            when {
                                onlyDeps -> "Download libraries for $name"
                                onlyTools -> "Allow agent tools for $name"
                                onlyNetwork -> "Allow network access for $name"
                                newCapsOnly -> "Allow new access for $name"
                                else -> "Install $name"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(origin, style = MaterialTheme.typography.bodySmall)
                        if (http) {
                            Text(
                                "This origin is HTTP. On a local network someone else could impersonate this address and use any capabilities you grant.",
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "Location, camera, and microphone will not work on HTTP. Chromium blocks those APIs on insecure origins. Use HTTPS or a *.localhost URL to test them.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (normalCaps.isNotEmpty()) {
                            Text("This is not a normal browser install. Bridge capabilities can read SMS, contacts, or files.")
                        }
                        normalCaps.forEach { cap ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = enabled[cap.id] == true, onCheckedChange = { enabled[cap.id] = it })
                                Column {
                                    val kind = if (cap.kind == CapabilityKind.BRIDGE) "Launcher bridge" else "Standard web API"
                                    Text("${cap.title} ($kind)", style = MaterialTheme.typography.titleSmall)
                                    Text(cap.description, style = MaterialTheme.typography.bodySmall)
                                    replaces[cap.id]?.let { line ->
                                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                        if (specialCaps.isNotEmpty()) {
                            Text("Asks later", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Special permissions are not given now. If you want the app to have one, " +
                                    "allow it later from the app's card in Settings.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            specialCaps.forEach { cap ->
                                Column {
                                    Text(cap.title, style = MaterialTheme.typography.titleSmall)
                                    Text(reasons[cap.id] ?: cap.description, style = MaterialTheme.typography.bodySmall)
                                    if (!cap.available) {
                                        Text(
                                            "Not available yet.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (deps.isNotEmpty()) {
                            Text("Libraries", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Downloaded once, checked against their hash, then served from this phone. " +
                                    "The app never contacts these sites itself, and library code runs with this app's permissions.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            deps.forEach { dep ->
                                Column {
                                    Text(dep.host, style = MaterialTheme.typography.titleSmall)
                                    Text(dep.path, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (origins.isNotEmpty()) {
                            Text("Network", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "This app wants to connect to these sites. It can send them anything it can read, " +
                                    "including data from the capabilities above.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            origins.forEach { Text(it, style = MaterialTheme.typography.titleSmall) }
                        }
                        if (embeds.isNotEmpty()) {
                            Text("Code from other sites", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "This app wants to run code from these sites, for example a payment form. " +
                                    "That code runs inside the app with every permission you give it, and can see " +
                                    "and send anything the app can. Untick any site you do not trust.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            embeds.forEach { site ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = embedTicked[site] == true,
                                        onCheckedChange = { embedTicked[site] = it },
                                    )
                                    Text(site, style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                        if (tools.isNotEmpty()) {
                            Text("Tools the agent can call: ${tools.joinToString { it.name }}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "The agent in the launcher can run these without asking each time. They run with this app's " +
                                    "permissions, and what they return goes to the agent as untrusted data. You can switch them off " +
                                    "from the app's card in Settings.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            tools.forEach { tool ->
                                Column {
                                    Text(tool.name, style = MaterialTheme.typography.titleSmall)
                                    Text(tool.description, style = MaterialTheme.typography.bodySmall)
                                    if (tool.isolate) {
                                        Text(
                                            if (ToolRunner.isolateSupported()) "Pure computation: no permissions, no network."
                                            else "Not available: this device has no isolated JavaScript engine.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (downloading) Text("Downloading libraries…", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = depsChecked && !downloading, onClick = {
                            val selected = normalCaps.filter { enabled[it.id] == true }
                            val shownOrigins = origins
                            val shownEmbeds = embeds
                            val tickedEmbeds = embeds.filter { embedTicked[it] == true }
                            // An isolate tool is only allowed where the device can run it; the rest stay unallowed.
                            val shownTools = tools.filter { !it.isolate || ToolRunner.isolateSupported() }
                            if (selected.isEmpty() && normalCaps.isNotEmpty() && !newCapsOnly) {
                                error = "Select at least one capability, or cancel."
                                return@Button
                            }
                            scope.launch {
                                if (deps.isNotEmpty() && depsAppId != null) {
                                    downloading = true
                                    error = null
                                    val failed = withContext(Dispatchers.IO) {
                                        runCatching { AppDeps.downloadMissing(this@ConsentActivity, depsAppId, slug) }
                                            .exceptionOrNull()
                                    }
                                    downloading = false
                                    if (failed != null) {
                                        error = "Download failed: ${failed.message ?: "the library could not be verified"}. Nothing was changed."
                                        return@launch
                                    }
                                    if (skipLaunch) DeftApp.instance.myApps.bumpPreview()
                                }
                                approve(
                                    name,
                                    startUrl,
                                    origin,
                                    manifestUrl,
                                    allCaps,
                                    caps,
                                    selected,
                                    slug,
                                    bundledId,
                                    payload.catalogHash,
                                    payload.existingAppId,
                                    skipLaunch,
                                    shownOrigins,
                                    shownEmbeds,
                                    tickedEmbeds,
                                    shownTools,
                                )
                            }
                        }) {
                            Text(
                                when {
                                    onlyDeps -> "Download and open"
                                    onlyTools -> "Allow"
                                    onlyNetwork -> "Allow and open"
                                    deps.isNotEmpty() && newCapsOnly -> "Allow and download"
                                    deps.isNotEmpty() -> "Allow, download and install"
                                    newCapsOnly -> "Allow"
                                    else -> "Allow and install"
                                },
                            )
                        }
                        if (pointOfUseToken != null) {
                            // "Not now" asks again later (after the 30 s cooldown the bridge keeps); "Don't ask again" is final.
                            TextButton(enabled = depsChecked, onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        DeftApp.instance.repository.refuseConsent(slug, bundledId, caps, byUser = false)
                                    }
                                    finish()
                                }
                            }) { Text(stringResource(R.string.consent_not_now)) }
                            TextButton(enabled = depsChecked, onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        DeftApp.instance.repository.refuseConsent(slug, bundledId, caps, byUser = true)
                                    }
                                    finish()
                                }
                            }) { Text(stringResource(R.string.consent_dont_ask_again)) }
                        } else {
                            TextButton(onClick = { finish() }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }

    private suspend fun approve(
        name: String,
        startUrl: String,
        origin: String,
        manifestUrl: String,
        allCaps: List<Capability>,
        shown: List<Capability>,
        selected: List<Capability>,
        slug: String?,
        bundledId: String?,
        catalogHash: String?,
        existingAppId: String?,
        skipLaunch: Boolean,
        shownOrigins: List<String>,
        shownEmbeds: List<String>,
        tickedEmbeds: List<String>,
        shownTools: List<DeftTool>,
    ) {
        val repo = DeftApp.instance.repository
        val installed = if (slug != null) {
            val app = repo.applyWorkspaceConsent(slug, shown, selected)
            if (!skipLaunch) {
                repo.touchWorkspaceOpened(app.id)
                DeftApp.instance.database.webApps().get(app.id)?.let { repo.recordWebLaunch(it) }
            }
            app
        } else if (bundledId != null) {
            repo.applyBundledConsent(bundledId, shown, selected)
        } else {
            finish()
            return
        }
        if (pointOfUseToken == null) {
            repo.recordNetworkConsent(installed.id, slug, shownOrigins)
            repo.recordEmbedConsent(installed.id, slug, shownEmbeds, tickedEmbeds)
            repo.recordToolConsent(installed.id, slug, shownTools)
        }
        pendingInstallId = installed.id
        pendingCaps = selected
        onReadyToInstall = {
            if (skipLaunch) {
                setResult(RESULT_OK)
                finish()
            } else {
                startActivity(WebViewHostActivity.intent(this, installed.id))
                finish()
            }
        }
        continueInstall()
    }

    private var pendingInstallId: String? = null

    override fun onDestroy() {
        super.onDestroy()
        // The point-of-use waiter ends when this screen ends for good; a rotation is not the end.
        if (isFinishing) pointOfUseToken?.let { SpecialAsks.closed(it) }
    }

    private fun continueInstall() {
        val caps = pendingCaps
        // A point-of-use ask leaves Android's runtime dialog to OsPermissions.ensure, the one dialog the user sees.
        val missing = if (pointOfUseToken != null) emptyList() else OsPermissionHelper.missingRuntime(this, caps)
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
        BridgeService.start(this)
        onReadyToInstall?.invoke()
        onReadyToInstall = null
    }

    companion object {
        const val EXTRA_MANIFEST_JSON = "manifestJson"
        const val EXTRA_WORKSPACE_SLUG = "workspaceSlug"
        const val EXTRA_NEW_CAPS = "newCaps"
        const val EXTRA_SKIP_LAUNCH = "skipLaunch"
        const val EXTRA_BUNDLED_ID = "bundledId"
        const val EXTRA_ASK_TOKEN = "askToken"
        const val EXTRA_ONLY_CAPABILITIES = "onlyCapabilities"

        /**
         * The consent screen for a set of plain launcher capabilities of an installed app, opened at the point of use
         * ([SpecialAsk]). It lists only those, each ticked by default; it writes only their grants, and only when the
         * user taps Allow. Special capabilities are refused here.
         *
         * Limit: an app that is neither workspace nor bundled (a remote or catalog-installed record with no consent
         * record of its own here) gets null, so the call returns the hinted error instead of asking.
         */
        fun pointOfUseIntent(context: Context, app: InstalledWebApp, capabilities: List<Capability>, askToken: String): Intent? {
            if (capabilities.isEmpty() || capabilities.any { it.risk == Risk.SPECIAL }) return null
            val slug = app.workspaceSlug
            val bundled = if (slug == null && app.tier == BundledApps.TIER_BUNDLED) BundledApps.idFromWebAppId(app.id) else null
            if (slug == null && bundled == null) return null
            val json = ConsentPayload(
                name = app.name,
                startUrl = app.startUrl,
                origin = app.origin,
                manifestUrl = app.manifestUrl,
                http = false,
                capabilities = capabilities.map { it.id },
                bundledId = bundled,
            ).encode()
            return Intent(context, ConsentActivity::class.java)
                .putExtra(EXTRA_MANIFEST_JSON, json)
                .putExtra(EXTRA_WORKSPACE_SLUG, slug)
                .putExtra(EXTRA_BUNDLED_ID, bundled)
                .putExtra(EXTRA_NEW_CAPS, true)
                .putExtra(EXTRA_SKIP_LAUNCH, true)
                .putExtra(EXTRA_ASK_TOKEN, askToken)
                .putStringArrayListExtra(EXTRA_ONLY_CAPABILITIES, ArrayList(capabilities.map { it.id }))
        }

        fun workspaceIntent(
            context: Context,
            slug: String,
            name: String,
            startUrl: String,
            origin: String,
            manifestUrl: String,
            pending: List<Capability>,
            newCapsOnly: Boolean,
            skipLaunch: Boolean = false,
        ): Intent {
            val json = ConsentPayload(
                name = name,
                startUrl = startUrl,
                origin = origin,
                manifestUrl = manifestUrl,
                http = false,
                capabilities = pending.map { it.id },
            ).encode()
            return Intent(context, ConsentActivity::class.java)
                .putExtra(EXTRA_MANIFEST_JSON, json)
                .putExtra(EXTRA_WORKSPACE_SLUG, slug)
                .putExtra(EXTRA_NEW_CAPS, newCapsOnly)
                .putExtra(EXTRA_SKIP_LAUNCH, skipLaunch)
        }

        fun bundledIntent(
            context: Context,
            bundledId: String,
            name: String,
            startUrl: String,
            origin: String,
            pending: List<Capability>,
            newCapsOnly: Boolean,
        ): Intent {
            val json = ConsentPayload(
                name = name,
                startUrl = startUrl,
                origin = origin,
                manifestUrl = "$origin/manifest.json",
                http = false,
                capabilities = pending.map { it.id },
                bundledId = bundledId,
            ).encode()
            return Intent(context, ConsentActivity::class.java)
                .putExtra(EXTRA_MANIFEST_JSON, json)
                .putExtra(EXTRA_BUNDLED_ID, bundledId)
                .putExtra(EXTRA_NEW_CAPS, newCapsOnly)
        }
    }
}
