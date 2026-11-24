package app.deft.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.deft.DeftApp
import app.deft.R
import app.deft.bluetooth.BluetoothRadioLock
import app.deft.catalog.Capability
import app.deft.catalog.CapabilityKind
import app.deft.catalog.GrantLevel
import app.deft.catalog.Risk
import app.deft.data.GrantEntity
import app.deft.data.WebAppEntity
import app.deft.data.declaredCapabilities
import app.deft.ui.special.SpecialGrantActivity
import kotlinx.coroutines.launch

@Composable
fun InstalledAppCard(
    app: WebAppEntity,
    grants: List<GrantEntity>,
    onOpen: () -> Unit,
    onGrant: (Capability, GrantLevel) -> Unit,
    onClearFolder: (() -> Unit)? = null,
    onUninstall: () -> Unit,
    uninstallLabel: String = "Uninstall",
    extraActions: @Composable () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val caps = remember(app.requiredCapabilitiesJson) { app.declaredCapabilities() }
    val byCap = remember(grants) { grants.associate { it.capability to it.level } }
    val radioOwner by BluetoothRadioLock.ownerFlow.collectAsState()
    val http = app.origin.startsWith("http://")
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(app.name, style = MaterialTheme.typography.titleMedium)
            Text(app.origin, style = MaterialTheme.typography.bodySmall)
            Text(app.startUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (http) {
                Text(
                    "This origin is HTTP. On a local network someone else could impersonate this address and use any capabilities you grant.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpen) { Text("Open") }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide permissions" else "Permissions")
                }
                extraActions()
                TextButton(onClick = onUninstall) {
                    Text(uninstallLabel, color = MaterialTheme.colorScheme.error)
                }
            }
            if (expanded) {
                if (caps.isEmpty()) {
                    Text("No declared capabilities.", style = MaterialTheme.typography.bodySmall)
                } else {
                    caps.filter { it.risk != Risk.SPECIAL }.forEach { cap ->
                        val radioCaption = radioOwner?.takeIf { cap.id.startsWith("bluetooth.") }?.let { owner ->
                            val (ownerId, ownerName) = owner
                            if (ownerId == app.id) {
                                stringResource(R.string.bt_radio_using)
                            } else {
                                stringResource(R.string.bt_radio_held, ownerName)
                            }
                        }
                        GrantRow(
                            cap = cap,
                            level = byCap[cap.id] ?: GrantLevel.DENIED.name,
                            onGrant = onGrant,
                            caption = radioCaption,
                        )
                    }
                }
                val special = caps.filter { it.risk == Risk.SPECIAL }
                if (special.isNotEmpty()) {
                    Text("Special permissions", style = MaterialTheme.typography.titleSmall)
                    special.forEach { cap ->
                        SpecialGrantRow(
                            appId = app.id,
                            cap = cap,
                            level = byCap[cap.id] ?: GrantLevel.DENIED.name,
                            onRemove = { onGrant(cap, GrantLevel.DENIED) },
                        )
                    }
                }
                if (Capability.NETWORK_USER in caps) {
                    ServersSection(
                        appId = app.id,
                        granted = (byCap[Capability.NETWORK_USER.id] ?: GrantLevel.DENIED.name) != GrantLevel.DENIED.name,
                    )
                }
                if (Capability.WEB_EMBED in caps) {
                    EmbedsSection(
                        appId = app.id,
                        slug = app.workspaceSlug,
                        granted = (byCap[Capability.WEB_EMBED.id] ?: GrantLevel.DENIED.name) != GrantLevel.DENIED.name,
                    )
                }
                if (Capability.BACKGROUND in caps) {
                    BackgroundSection(
                        appId = app.id,
                        granted = (byCap[Capability.BACKGROUND.id] ?: GrantLevel.DENIED.name) != GrantLevel.DENIED.name,
                    )
                }
                if (app.workspaceSlug != null) AgentToolsSection(appId = app.id, slug = app.workspaceSlug)
                OpenWithSection(appId = app.id)
                if (caps.any { it.id.startsWith("files.") }) {
                    val tree = app.filesTreeUri
                    Text(
                        when {
                            !tree.isNullOrBlank() -> "Files: picked folder"
                            else -> "Files: Downloads, or pick a folder"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!tree.isNullOrBlank() && onClearFolder != null) {
                        TextButton(onClick = onClearFolder) { Text("Clear folder") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GrantRow(
    cap: Capability,
    level: String,
    onGrant: (Capability, GrantLevel) -> Unit,
    caption: String? = null,
) {
    val denied = level == GrantLevel.DENIED.name
    val kind = if (cap.kind == CapabilityKind.BRIDGE) "Launcher bridge" else "Standard web API"
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("${cap.title} ($kind)", style = MaterialTheme.typography.titleSmall)
                Text(level, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (denied) {
                Button(onClick = { onGrant(cap, cap.defaultGrant) }) { Text("Allow") }
            } else {
                TextButton(onClick = { onGrant(cap, GrantLevel.DENIED) }) { Text("Revoke") }
            }
        }
        Text(cap.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!caption.isNullOrBlank()) {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * PARITY P3: servers the user lets this app reach (`network.user`), for example a home server.
 * Same address rule as manifest origins; they count as consented origins in the app's CSP.
 */
@Composable
private fun ServersSection(appId: String, granted: Boolean) {
    val repo = DeftApp.instance.repository
    val scope = rememberCoroutineScope()
    var servers by remember(appId) { mutableStateOf<List<String>>(emptyList()) }
    var input by remember(appId) { mutableStateOf("") }
    var error by remember(appId) { mutableStateOf<String?>(null) }
    LaunchedEffect(appId) { servers = repo.userServers(appId) }
    Text("Servers", style = MaterialTheme.typography.titleSmall)
    Text(
        if (granted) {
            "This app can connect to these servers. Changes apply when you go back to the app."
        } else {
            "Allow \"${Capability.NETWORK_USER.title}\" above to let the app use these servers."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    servers.forEach { origin ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(origin, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch {
                    repo.removeUserServer(appId, origin)
                    servers = repo.userServers(appId)
                }
            }) { Text("Remove") }
        }
    }
    OutlinedTextField(
        value = input,
        onValueChange = {
            input = it.take(260)
            error = null
        },
        label = { Text("https://my-server.example.com") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(
        enabled = input.isNotBlank(),
        onClick = {
            scope.launch {
                val problem = repo.addUserServer(appId, input)
                error = problem
                if (problem == null) input = ""
                servers = repo.userServers(appId)
            }
        },
    ) { Text("Add server") }
}

/**
 * W5: the agent tools the app declares, with one switch "Let the agent use this app's tools". Off takes effect at the
 * agent's next request (it re-reads this setting before every one) and a call already offered is refused when it runs.
 */
@Composable
private fun AgentToolsSection(appId: String, slug: String) {
    val repo = DeftApp.instance.repository
    val scope = rememberCoroutineScope()
    var tools by remember(appId) { mutableStateOf<List<Pair<app.deft.registry.DeftTool, Boolean>>>(emptyList()) }
    var enabled by remember(appId) { mutableStateOf(true) }
    LaunchedEffect(appId) {
        tools = repo.toolChoices(appId, slug)
        enabled = repo.toolsEnabled(appId)
    }
    if (tools.isEmpty()) return
    Text("Agent tools", style = MaterialTheme.typography.titleSmall)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Let the agent use this app's tools", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(
            checked = enabled,
            onCheckedChange = { on ->
                enabled = on
                scope.launch { repo.setToolsEnabled(appId, on) }
            },
        )
    }
    tools.forEach { (tool, consented) ->
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(tool.name + if (tool.isolate) " (isolated)" else "", style = MaterialTheme.typography.bodyMedium)
            Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!consented) {
                Text(
                    "Not allowed yet: open the app or Apply it to review this tool.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * PARITY G14: the sites listed in the manifest `embeds`, each allowed or not on its own. Only
 * allowed sites (and only while "Code from other sites" is allowed) reach the app's CSP.
 */
@Composable
private fun EmbedsSection(appId: String, slug: String?, granted: Boolean) {
    val repo = DeftApp.instance.repository
    val scope = rememberCoroutineScope()
    var sites by remember(appId) { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    LaunchedEffect(appId) { sites = repo.embedChoices(appId, slug) }
    Text("Code from other sites", style = MaterialTheme.typography.titleSmall)
    Text(
        if (granted) {
            "Code from the allowed sites runs inside this app with all of its permissions. " +
                "Changes apply when you go back to the app."
        } else {
            "Allow \"${Capability.WEB_EMBED.title}\" above to let the app load code from these sites."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (sites.isEmpty()) {
        Text("The app lists no sites.", style = MaterialTheme.typography.bodySmall)
    }
    sites.forEach { (site, allowed) ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(site, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (allowed) "Allowed" else "Not allowed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val toggle: () -> Unit = {
                scope.launch {
                    repo.setEmbedApproved(appId, slug, site, !allowed)
                    sites = repo.embedChoices(appId, slug)
                }
            }
            if (allowed) {
                TextButton(onClick = toggle) { Text("Remove") }
            } else {
                Button(onClick = toggle) { Text("Allow") }
            }
        }
    }
}

/**
 * PARITY E1: "Always" choices from the "Open with" picker that name this app. "Ask again" clears
 * one, so the picker shows the list next time.
 */
@Composable
private fun OpenWithSection(appId: String) {
    val context = LocalContext.current
    val defaults = remember { app.deft.ui.handlers.HandlerDefaults(context) }
    var aliases by remember(appId) { mutableStateOf(defaults.forApp(appId)) }
    if (aliases.isEmpty()) return
    Text("Opens without asking", style = MaterialTheme.typography.titleSmall)
    aliases.forEach { alias ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                alias.label.replaceFirstChar { it.uppercase() } + " from other apps",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                defaults.clear(alias)
                aliases = defaults.forApp(appId)
            }) { Text("Ask again") }
        }
    }
}

/**
 * PARITY D1: the app's last background runs, from the audit log. The "Run in the background"
 * grant above is the kill switch; "Run now" starts one run by hand (trigger `manual`).
 */
@Composable
private fun BackgroundSection(appId: String, granted: Boolean) {
    val context = LocalContext.current
    val repo = DeftApp.instance.repository
    val runs by remember(appId) { repo.observeBackgroundRuns(appId) }.collectAsState(initial = emptyList())
    Text("Background runs", style = MaterialTheme.typography.titleSmall)
    Text(
        if (granted) {
            "Each run lasts at most 30 seconds. Revoke \"${Capability.BACKGROUND.title}\" above to stop all runs at once."
        } else {
            "Allow \"${Capability.BACKGROUND.title}\" above to let the app run when it is closed."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (granted) {
        TextButton(onClick = {
            app.deft.bridge.BridgeService.backgroundTrigger(
                context,
                appId,
                app.deft.background.BackgroundPolicy.MANUAL,
            )
        }) { Text("Run now") }
    }
    if (runs.isEmpty()) {
        Text("No runs yet.", style = MaterialTheme.typography.bodySmall)
    }
    runs.forEach { row ->
        Text(backgroundRunLine(row), style = MaterialTheme.typography.bodySmall)
    }
}

private fun backgroundRunLine(row: app.deft.data.UsageLogEntity): String {
    val meta = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(row.metadata.orEmpty()) as? kotlinx.serialization.json.JsonObject
    }.getOrNull()
    val trigger = (meta?.get("trigger") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"
    val ms = (meta?.get("ms") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
    val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        .format(java.util.Date(row.timestamp))
    val result = when (row.outcome) {
        "ok" -> "finished"
        "timeout" -> "stopped at 30 s"
        "cancelled" -> "stopped"
        "error" -> "failed"
        "not_granted" -> "not allowed"
        "not_declared" -> "trigger not declared"
        "queue_full" -> "skipped, too many waiting"
        "rate_limited" -> "skipped, hourly limit"
        else -> row.outcome
    }
    val duration = ms?.let { " · %.1f s".format(it / 1000.0) }.orEmpty()
    return "$time · $trigger · $result$duration"
}

/** PARITY G0: special access is never a one-tap Allow here; "Review" opens the grant sheet. */
@Composable
private fun SpecialGrantRow(
    appId: String,
    cap: Capability,
    level: String,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val denied = level == GrantLevel.DENIED.name
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(cap.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        !denied -> "Allowed"
                        !cap.available -> "Not available yet"
                        else -> "Not allowed. Asks for your screen lock."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                !denied -> TextButton(onClick = onRemove) { Text("Remove") }
                cap.available -> Button(onClick = {
                    context.startActivity(SpecialGrantActivity.intent(context, appId, cap))
                }) { Text("Review") }
            }
        }
        Text(cap.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ConfirmActionDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
