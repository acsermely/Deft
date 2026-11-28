package app.deft.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.webkit.GeolocationPermissions
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import app.deft.ui.theme.DeftTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.deft.BuildConfig
import app.deft.DeftApp
import app.deft.ui.OverlayGuard
import app.deft.webview.DeftLintEngine
import app.deft.webview.DeftLintFormat
import app.deft.R
import app.deft.catalog.Capability
import app.deft.catalog.GrantLevel
import app.deft.data.GrantEntity
import app.deft.data.UsageLogEntity
import app.deft.data.WebAppEntity
import app.deft.permissions.OsPermissionHelper
import app.deft.special.SpecialReview
import app.deft.ui.host.WebViewHostActivity
import app.deft.ui.theme.AppDeftTheme
import app.deft.ui.theme.LocalDeftConfig
import app.deft.ui.workspace.WorkspaceLaunchActivity
import app.deft.agent.AgentSession
import app.deft.workspace.BundledApps
import app.deft.workspace.MyAppsStore
import app.deft.workspace.OverlayStore
import app.deft.workspace.SourceIndex
import app.deft.workspace.ProjectInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardActivity : ComponentActivity() {
    private var exportSlug: String? = null
    private val banner = mutableStateOf<String?>(null)

    private fun work(block: suspend () -> String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val message = try {
                block()
            } catch (err: Exception) {
                err.message ?: "Could not finish"
            }
            withContext(Dispatchers.Main) { banner.value = message }
        }
    }

    private val exportZip = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val slug = exportSlug ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        work {
            contentResolver.openOutputStream(uri)?.use { out ->
                DeftApp.instance.myApps.exportZip(slug, out)
            } ?: error("Could not write the zip")
            "Exported $slug"
        }
    }
    private val importIndex = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        work {
            val text = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                ?: error("Could not read the index")
            val prefs = getSharedPreferences("sources", MODE_PRIVATE)
            val stored = prefs.getLong("serial", -1L)
            val previous = if (stored < 0) null else stored
            val checked = SourceIndex.verify(text, previous)
            val file = java.io.File(filesDir, "deft/sources/index.json")
            file.parentFile?.mkdirs()
            file.writeText(text)
            prefs.edit().putLong("serial", checked.serial).apply()
            "Added index, serial ${checked.serial}"
        }
    }
    private val importZip = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.removeSuffix(".zip")
            ?.lowercase()
            .orEmpty()
        val slug = runCatching { MyAppsStore.requireSlug(name) }
            .getOrElse { "imported" }
        work {
            val apps = DeftApp.instance.myApps
            val signed = contentResolver.openInputStream(uri)?.use { app.deft.workspace.DeftPackage.looksSigned(it) }
                ?: error("Could not read the package")
            val installed = if (signed) {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not read the package")
                // Verify, downgrade check, paths and quotas, then lint in memory.
                // A lint error is an install error; nothing on disk changes.
                // The grant decides the larger caps; lint gets the same package cap.
                val largeSlugs = DeftApp.instance.repository.largeStorageSlugs()
                val prepared = apps.prepareInstall(bytes, largeSlugs)
                val raw = DeftLintEngine.run(
                    this@DashboardActivity,
                    prepared.textFiles(),
                    "app",
                    packageBytes = prepared.verified.caps.packageBytes,
                )
                val errors = runCatching { DeftLintFormat.parse(raw) }.getOrNull().orEmpty().filter { it.severity == "error" }
                if (errors.isNotEmpty()) {
                    error("Not installed: ${errors.size} lint error(s)\n" + DeftLintFormat.lines(errors, 10))
                }
                apps.commitInstall(prepared)
            } else {
                contentResolver.openInputStream(uri)?.use { apps.importZip(slug, it) }
                    ?: error("Could not read the package")
                slug
            }
            "Imported $installed"
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayGuard.protect(this)
        enableEdgeToEdge()
        val repo = DeftApp.instance.repository
        val myApps = DeftApp.instance.myApps
        val initialTab = tabIndex(intent.getStringExtra(EXTRA_TAB))
        setContent {
            AppDeftTheme {
                val apps by repo.observeApps().collectAsState(initial = emptyList())
                val grants by repo.observeGrants().collectAsState(initial = emptyList())
                val config = LocalDeftConfig.current
                val message by banner
                val grantsByApp = remember(grants) { grants.groupBy { it.webAppId } }
                val appsBySlug = remember(apps) {
                    apps.mapNotNull { app -> app.workspaceSlug?.let { slug -> slug to app } }.toMap()
                }
                val scope = rememberCoroutineScope()
                var tab by remember { mutableIntStateOf(initialTab) }
                var uninstallTarget by remember { mutableStateOf<WebAppEntity?>(null) }
                var removeWorkspace by remember { mutableStateOf<WebAppEntity?>(null) }
                var deleteProject by remember { mutableStateOf<ProjectInfo?>(null) }
                var disk by remember { mutableStateOf(emptyList<ProjectInfo>()) }
                var overlayTick by remember { mutableIntStateOf(0) }
                var overlayCards by remember { mutableStateOf(emptyMap<String, OverlayCard>()) }
                LaunchedEffect(apps, tab) {
                    if (tab == TAB_WORKSPACES) {
                        disk = withContext(Dispatchers.IO) { myApps.listProjects() }
                    }
                }
                LaunchedEffect(disk, overlayTick, appsBySlug) {
                    val projects = disk
                    val rows = appsBySlug
                    overlayCards = withContext(Dispatchers.IO) {
                        projects.associate { proj ->
                            val overlayDir = OverlayStore.dir(
                                this@DashboardActivity,
                                rows[proj.slug]?.id ?: MyAppsStore.webAppId(proj.slug),
                            )
                            val modified = OverlayStore.metaFile(overlayDir).isFile ||
                                (overlayDir.isDirectory && overlayDir.list()?.isNotEmpty() == true)
                            val conflict = if (modified) OverlayStore.firstConflict(proj.dir, overlayDir) else null
                            proj.slug to OverlayCard(modified, conflict)
                        }
                    }
                }

                fun applyGrant(app: WebAppEntity, cap: Capability, level: GrantLevel) {
                    scope.launch {
                        repo.setGrant(app.id, cap, level)
                        // Review: "Remove" on special access is audited like the other special changes.
                        SpecialReview.dashboardAudit(cap, level)?.let {
                            repo.log(app.id, "special", cap.id, null, it)
                        }
                        if (cap == Capability.LOCATION && level == GrantLevel.DENIED) {
                            GeolocationPermissions.getInstance().clear(app.origin)
                        }
                    }
                }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = { Text("Settings") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                titleContentColor = MaterialTheme.colorScheme.onBackground,
                            ),
                            actions = {
                                TextButton(onClick = { finish() }) { Text("Close") }
                            },
                        )
                    },
                ) { pad ->
                    Column(Modifier.padding(pad).fillMaxSize()) {
                        if (message != null) {
                            Text(
                                message!!,
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        // 8.1: selected tab is label-coloured; the one accent is a 4 dp dot under it.
                        ScrollableTabRow(
                            selectedTabIndex = tab,
                            edgePadding = 16.dp,
                            indicator = { positions ->
                                if (tab < positions.size) {
                                    Box(
                                        Modifier.offset(x = positions[tab].left).width(positions[tab].width).fillMaxHeight(),
                                        contentAlignment = Alignment.BottomCenter,
                                    ) {
                                        Box(Modifier.padding(bottom = 4.dp).size(4.dp).background(DeftTheme.colors.accent, CircleShape))
                                    }
                                }
                            },
                        ) {
                            TABS.forEachIndexed { i, label ->
                                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                            }
                        }
                        LazyColumn(
                            Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            when (tab) {
                                TAB_APPS -> {
                                    val groups = DashboardAppGroups.of(apps)
                                    if (groups.mine.isNotEmpty()) {
                                        item(key = "h-mine") { SectionTitle("My apps") }
                                        items(groups.mine, key = { it.id }) { app ->
                                            InstalledAppCard(
                                                app = app,
                                                grants = grantsByApp[app.id].orEmpty(),
                                                onOpen = {
                                                    app.workspaceSlug?.let { slug ->
                                                        startActivity(WorkspaceLaunchActivity.intent(this@DashboardActivity, slug))
                                                    }
                                                },
                                                onGrant = { cap, level -> applyGrant(app, cap, level) },
                                                onClearFolder = { scope.launch { repo.clearFilesTreeUri(app.id) } },
                                                onUninstall = { removeWorkspace = app },
                                                uninstallLabel = "Remove from launcher",
                                            )
                                        }
                                    }
                                    if (groups.installed.isNotEmpty()) {
                                        item(key = "h-installed") { SectionTitle("Installed web apps") }
                                        items(groups.installed, key = { it.id }) { app ->
                                            InstalledAppCard(
                                                app = app,
                                                grants = grantsByApp[app.id].orEmpty(),
                                                onOpen = {
                                                    startActivity(WebViewHostActivity.intent(this@DashboardActivity, app.id))
                                                },
                                                onGrant = { cap, level -> applyGrant(app, cap, level) },
                                                onClearFolder = { scope.launch { repo.clearFilesTreeUri(app.id) } },
                                                onUninstall = { uninstallTarget = app },
                                            )
                                        }
                                    }
                                    item(key = "h-bundled") { SectionTitle("Bundled samples") }
                                    if (groups.bundled.isEmpty()) {
                                        item {
                                            Text(
                                                "Bundled samples appear here after first launch. Copy one into Workspaces to edit it.",
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                    items(groups.bundled, key = { it.id }) { app ->
                                        InstalledAppCard(
                                            app = app,
                                            grants = grantsByApp[app.id].orEmpty(),
                                            onOpen = {
                                                startActivity(WebViewHostActivity.intent(this@DashboardActivity, app.id))
                                            },
                                            onGrant = { cap, level -> applyGrant(app, cap, level) },
                                            onClearFolder = {
                                                scope.launch { repo.clearFilesTreeUri(app.id) }
                                            },
                                            onUninstall = { uninstallTarget = app },
                                            extraActions = {
                                                TextButton(
                                                    onClick = {
                                                        val id = BundledApps.idFromWebAppId(app.id)
                                                            ?: return@TextButton
                                                        val slug = runCatching {
                                                            MyAppsStore.requireSlug(id)
                                                        }.getOrElse { "copy-$id".take(40) }
                                                        val dest = if (myApps.hasProject(slug)) "$slug-copy" else slug
                                                        runCatching {
                                                            BundledApps.copyToMyApps(
                                                                this@DashboardActivity,
                                                                id,
                                                                dest,
                                                            )
                                                            scope.launch(Dispatchers.IO) {
                                                                repo.ensureWorkspaceApp(dest)
                                                                disk = myApps.listProjects()
                                                            }
                                                        }
                                                    },
                                                ) { Text("Copy to my apps") }
                                            },
                                        )
                                    }
                                }
                                TAB_WORKSPACES -> {
                                    item {
                                        TextButton(
                                            onClick = {
                                                importZip.launch(arrayOf("application/zip", "*/*"))
                                            },
                                        ) { Text("Import zip") }
                                        TextButton(
                                            onClick = {
                                                importIndex.launch(arrayOf("application/json", "*/*"))
                                            },
                                        ) { Text("Add local index") }
                                    }
                                    if (disk.isEmpty()) {
                                        item {
                                            Text(
                                                "No local apps yet. Ask the agent to apps.create a slug, or import a zip.",
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                    items(disk, key = { it.slug }) { proj ->
                                        val row = appsBySlug[proj.slug]
                                        val card = overlayCards[proj.slug]
                                        val overlayDir = OverlayStore.dir(this@DashboardActivity, row?.id ?: MyAppsStore.webAppId(proj.slug))
                                        WorkspaceCard(
                                            proj = proj,
                                            row = row,
                                            modified = card?.modified == true,
                                            conflict = card?.conflict,
                                            grants = row?.id?.let { grantsByApp[it] }.orEmpty(),
                                            onOpen = {
                                                startActivity(WorkspaceLaunchActivity.intent(this@DashboardActivity, proj.slug))
                                            },
                                            onPin = {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        val installed = repo.ensureWorkspaceApp(proj.slug)
                                                        repo.setPinned(installed.id, row?.pinned != true)
                                                    }
                                                }
                                            },
                                            onGrant = { cap, level ->
                                                if (row != null) applyGrant(row, cap, level)
                                            },
                                            onClearFolder = row?.let { app ->
                                                { scope.launch { repo.clearFilesTreeUri(app.id) } }
                                            },
                                            onRemove = { if (row != null) removeWorkspace = row },
                                            onDelete = { deleteProject = proj },
                                            onExport = {
                                                exportSlug = proj.slug
                                                exportZip.launch("${proj.slug}.zip")
                                            },
                                            onFork = {
                                                work {
                                                    val created = myApps.fork(proj.slug, row?.id)
                                                    disk = myApps.listProjects()
                                                    overlayTick += 1
                                                    "Forked $created"
                                                }
                                            },
                                            onResetOverlay = {
                                                work {
                                                    myApps.resetOverlay(row?.id ?: proj.slug)
                                                    overlayTick += 1
                                                    "Reset edits"
                                                }
                                            },
                                            onResetGrants = {
                                                row?.let { app ->
                                                    work {
                                                        repo.resetGrants(app.id)
                                                        "Permission decisions reset for ${app.name}. Reopen the app to see the asks again"
                                                    }
                                                }
                                            },
                                            onRollback = {
                                                work {
                                                    myApps.rollbackPackage(proj.slug)
                                                    "Rolled back ${proj.slug}"
                                                }
                                            },
                                            onKeepMine = {
                                                val path = card?.conflict
                                                if (path != null) {
                                                    work {
                                                        OverlayStore.keepMine(proj.dir, overlayDir, path)
                                                        overlayTick += 1
                                                        "Kept $path"
                                                    }
                                                }
                                            },
                                            onTakeUpstream = {
                                                val path = card?.conflict
                                                if (path != null) {
                                                    work {
                                                        OverlayStore.takeUpstream(overlayDir, path)
                                                        overlayTick += 1
                                                        "Took upstream for $path"
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                                TAB_CONFIG -> {
                                    item { DashboardConfigTab(config) }
                                }
                                else -> {
                                    item {
                                        Text(
                                            if (OsPermissionHelper.isGmsPresent(this@DashboardActivity)) {
                                                getString(R.string.gms_honesty)
                                            } else {
                                                "Google Play Services was not found. OEM and baseband telemetry can still exist outside this app."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    item { ActivityLog(repo, apps) }
                                }
                            }
                        }
                    }
                }

                uninstallTarget?.let { app ->
                    ConfirmActionDialog(
                        title = "Uninstall ${app.name}?",
                        body = "Remove ${app.origin} from the launcher. Grants, cache, and alarms for this origin go with it.",
                        confirm = "Uninstall",
                        onConfirm = {
                            val id = app.id
                            uninstallTarget = null
                            scope.launch { repo.uninstall(id) }
                        },
                        onDismiss = { uninstallTarget = null },
                    )
                }
                removeWorkspace?.let { app ->
                    ConfirmActionDialog(
                        title = "Remove ${app.name} from launcher?",
                        body = "The project folder stays on disk in private storage.",
                        confirm = "Remove",
                        onConfirm = {
                            val id = app.id
                            removeWorkspace = null
                            scope.launch { repo.uninstall(id) }
                        },
                        onDismiss = { removeWorkspace = null },
                    )
                }
                deleteProject?.let { proj ->
                    ConfirmActionDialog(
                        title = "Delete ${proj.name}?",
                        body = "Permanently delete ${proj.dir.path} and unregister it from the launcher.",
                        confirm = "Delete",
                        onConfirm = {
                            val slug = proj.slug
                            val row = apps.find { it.workspaceSlug == slug }
                            deleteProject = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { AgentSession.closeWorkspace(slug) }
                                    runCatching { myApps.deleteProject(slug) }
                                    if (row != null) repo.uninstall(row.id)
                                    disk = myApps.listProjects()
                                }
                            }
                        },
                        onDismiss = { deleteProject = null },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_APPS_KEY = "apps"
        const val TAB_WORKSPACES_KEY = "workspaces"
        const val TAB_CONFIG_KEY = "config"
        const val TAB_ACTIVITY_KEY = "activity"

        private const val TAB_APPS = 0
        private const val TAB_WORKSPACES = 1
        private const val TAB_CONFIG = 2
        private const val TAB_ACTIVITY = 3
        private val TABS = listOf("Apps", "Workspaces", "Config", "Activity")

        fun tabIndex(key: String?): Int = when (key) {
            TAB_WORKSPACES_KEY -> TAB_WORKSPACES
            TAB_CONFIG_KEY -> TAB_CONFIG
            TAB_ACTIVITY_KEY -> TAB_ACTIVITY
            else -> TAB_APPS
        }

        fun intent(context: android.content.Context, tab: String? = null): Intent =
            Intent(context, DashboardActivity::class.java).apply {
                if (tab != null) putExtra(EXTRA_TAB, tab)
            }
    }
}

@androidx.compose.runtime.Composable
private fun WorkspaceCard(
    proj: ProjectInfo,
    row: WebAppEntity?,
    grants: List<GrantEntity>,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onGrant: (Capability, GrantLevel) -> Unit,
    onClearFolder: (() -> Unit)?,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onFork: () -> Unit,
    onResetOverlay: () -> Unit,
    onResetGrants: () -> Unit,
    onRollback: () -> Unit,
    modified: Boolean,
    conflict: String?,
    onKeepMine: () -> Unit,
    onTakeUpstream: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (row != null) {
            InstalledAppCard(
                app = row,
                grants = grants,
                onOpen = onOpen,
                onGrant = onGrant,
                onClearFolder = onClearFolder,
                onUninstall = onRemove,
                uninstallLabel = "Remove from launcher",
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(proj.name, style = MaterialTheme.typography.titleMedium)
                Text(proj.slug, style = MaterialTheme.typography.bodySmall)
                Text(proj.dir.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Not opened yet: its permissions appear here and under Apps after the first open.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onOpen) { Text("Open") }
            }
        }
        TextButton(onClick = onPin) { Text(if (row?.pinned == true) "Unpin" else "Pin") }
        if (modified) Text("Modified by you", style = MaterialTheme.typography.bodySmall)
        if (conflict != null) {
            Text("Upstream changed: $conflict", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onKeepMine) { Text("Keep mine") }
            TextButton(onClick = onTakeUpstream) { Text("Take upstream") }
        }
        TextButton(onClick = onExport) { Text("Export zip") }
        TextButton(onClick = onFork) { Text("Fork") }
        TextButton(onClick = onResetOverlay) { Text("Reset my edits") }
        // Debug builds only: every capability is asked again on its next use.
        if (BuildConfig.DEBUG && row != null) {
            TextButton(onClick = onResetGrants) { Text(stringResource(R.string.debug_reset_grants)) }
        }
        TextButton(onClick = onRollback) { Text("Roll back") }
        TextButton(onClick = onDelete) {
            Text("Delete project", color = MaterialTheme.colorScheme.error)
        }
    }
}

private data class OverlayCard(val modified: Boolean, val conflict: String?)

@androidx.compose.runtime.Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
}

@androidx.compose.runtime.Composable
private fun ActivityLog(
    repo: app.deft.data.AppRepository,
    apps: List<WebAppEntity>,
) {
    val log by repo.observeLog().collectAsState(initial = emptyList())
    val names = remember(apps) { apps.associate { it.id to it.name } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Activity", style = MaterialTheme.typography.titleLarge)
        Text(summarizeLog(log, names), style = MaterialTheme.typography.bodyMedium)
        log.forEach { row -> LogRow(row, names) }
    }
}

private fun summarizeLog(log: List<UsageLogEntity>, names: Map<String, String>): String {
    val start = System.currentTimeMillis() - 24L * 60 * 60 * 1000
    val today = log.filter { it.timestamp >= start && it.outcome == "ok" }
    if (today.isEmpty()) return "No granted bridge calls in the last 24 hours."
    return today.groupBy { it.webAppId to it.capability }.entries.joinToString("\n") { (key, rows) ->
        val (webAppId, cap) = key
        val name = webAppId?.let { names[it] } ?: webAppId ?: "an app"
        val verb = when {
            cap.endsWith(".read") || cap == "location" -> "Read"
            cap.endsWith(".write") || cap.endsWith(".send") -> "Changed"
            cap.endsWith(".place") -> "Placed calls with"
            else -> "Used"
        }
        "$verb ${cap.replace('.', ' ')} from $name ${rows.size} times today"
    }
}

@androidx.compose.runtime.Composable
private fun LogRow(row: UsageLogEntity, names: Map<String, String>) {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val name = row.webAppId?.let { names[it] } ?: row.webAppId ?: "none"
    val summary = when {
        row.outcome != "ok" -> "${row.capability} was ${row.outcome.replace('_', ' ')}"
        row.method.isNullOrBlank() -> "used ${row.capability}"
        else -> "${row.method} (${row.capability})"
    }
    Text(
        "${fmt.format(Date(row.timestamp))}  $name  $summary",
        style = MaterialTheme.typography.bodySmall,
    )
}
