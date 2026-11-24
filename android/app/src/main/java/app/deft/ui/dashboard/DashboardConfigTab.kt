package app.deft.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.deft.DeftApp
import app.deft.device.DeviceTierProbe
import app.deft.device.DeviceTierStore
import app.deft.ui.theme.DeftTheme
import app.deft.agent.AgentKeyStore
import app.deft.agent.AgentWeb
import app.deft.config.DeftConfig
import app.deft.config.ThemeSettings
import app.deft.config.Toml
import app.deft.ui.theme.toCssHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardConfigTab(config: DeftConfig) {
    val store = DeftApp.instance.configStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var accent by remember { mutableStateOf(config.theme.accent) }

    var dockRadius by remember { mutableStateOf(config.shell.dockRadius.toString()) }
    var dockAlpha by remember { mutableStateOf(config.shell.dockAlpha.toString()) }
    var searchCenter by remember { mutableStateOf(config.shell.searchCenter.toString()) }
    var wallpaper by remember { mutableStateOf(config.shell.wallpaper.toCssHex()) }
    var typeScale by remember { mutableStateOf(config.shell.typeScale.toString()) }
    var recentsCount by remember { mutableStateOf(config.shell.recentsCount.toString()) }

    var searchOpenDp by remember { mutableStateOf(config.home.searchOpenDp.toString()) }
    var searchSnap by remember { mutableStateOf(config.home.searchSnap.toString()) }
    var agentOpenDp by remember { mutableStateOf(config.home.agentOpenDp.toString()) }
    var agentSnap by remember { mutableStateOf(config.home.agentSnap.toString()) }

    var maxText by remember { mutableStateOf(config.limits.maxTextBytes.toString()) }
    var maxBinary by remember { mutableStateOf(config.limits.maxBinaryBytes.toString()) }
    var maxFiles by remember { mutableStateOf(config.limits.maxFiles.toString()) }
    var maxPackage by remember { mutableStateOf(config.limits.maxPackageBytes.toString()) }

    var apiBase by remember { mutableStateOf(config.agent.apiBase) }
    var model by remember { mutableStateOf(config.agent.model) }
    var maxTokens by remember { mutableStateOf(config.agent.maxTokens.toString()) }
    var contextWindow by remember { mutableStateOf(config.agent.contextWindow.toString()) }

    var apiKey by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(AgentKeyStore.get(context) != null) }

    var rawOpen by remember { mutableStateOf(false) }
    var rawPath by remember { mutableStateOf("theme.toml") }
    var rawBody by remember { mutableStateOf("") }
    val files = remember { store.listFiles() }

    LaunchedEffect(config) {
        accent = config.theme.accent
        dockRadius = config.shell.dockRadius.toString()
        dockAlpha = config.shell.dockAlpha.toString()
        searchCenter = config.shell.searchCenter.toString()
        wallpaper = config.shell.wallpaper.toCssHex()
        typeScale = config.shell.typeScale.toString()
        recentsCount = config.shell.recentsCount.toString()
        searchOpenDp = config.home.searchOpenDp.toString()
        searchSnap = config.home.searchSnap.toString()
        agentOpenDp = config.home.agentOpenDp.toString()
        agentSnap = config.home.agentSnap.toString()
        maxText = config.limits.maxTextBytes.toString()
        maxBinary = config.limits.maxBinaryBytes.toString()
        maxFiles = config.limits.maxFiles.toString()
        maxPackage = config.limits.maxPackageBytes.toString()
        apiBase = config.agent.apiBase
        model = config.agent.model
        maxTokens = config.agent.maxTokens.toString()
        contextWindow = config.agent.contextWindow.toString()
    }

    fun runWrite(block: () -> Unit) {
        scope.launch {
            error = withContext(Dispatchers.IO) {
                runCatching { block(); null }.exceptionOrNull()?.message
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        Text("Accent: ${config.theme.accent}", style = MaterialTheme.typography.bodySmall)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeSettings.PRESETS.forEach { (name, value) ->
                FilterChip(
                    selected = config.theme.accent == value,
                    onClick = { runWrite { store.setTheme(value) } },
                    label = { Text(name) },
                )
            }
            FilterChip(
                selected = config.theme.accent == ThemeSettings.WALLPAPER,
                onClick = { runWrite { store.setTheme(ThemeSettings.WALLPAPER) } },
                label = { Text("Wallpaper") },
            )
        }
        Field("custom accent (#hex or oklch)", accent) { accent = it }
        Button(
            onClick = { runWrite { store.setTheme(accent.trim()) } },
            colors = ButtonDefaults.buttonColors(containerColor = DeftTheme.colors.accent, contentColor = DeftTheme.colors.onAccent),
        ) { Text("Save accent") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Reduce transparency", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = config.theme.reduceTransparency,
                onCheckedChange = { on -> runWrite { store.setReduceTransparency(on) } },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Monochrome icons (Android 13+ themed layer, label colour)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = config.theme.monochromeIcons,
                onCheckedChange = { on -> runWrite { store.setMonochromeIcons(on) } },
            )
        }
        Text("Appearance: ${config.theme.appearance}", style = MaterialTheme.typography.bodySmall)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeSettings.APPEARANCES.forEach { name ->
                FilterChip(
                    selected = config.theme.appearance == name,
                    onClick = { runWrite { store.setTheme(config.theme.accent, name) } },
                    label = { Text(name) },
                )
            }
        }

        Text("Shell", style = MaterialTheme.typography.titleLarge)
        Field("dock_radius", dockRadius) { dockRadius = it }
        Field("dock_alpha", dockAlpha) { dockAlpha = it }
        Field("search_center", searchCenter) { searchCenter = it }
        Field("wallpaper", wallpaper) { wallpaper = it }
        Field("type_scale", typeScale) { typeScale = it }
        Field("recents_count", recentsCount) { recentsCount = it }
        Button(onClick = {
            runWrite {
                store.write(
                    "shell.toml",
                    Toml.encode(
                        listOf(
                            "dock_radius" to (dockRadius.toFloatOrNull() ?: 28f),
                            "dock_alpha" to (dockAlpha.toFloatOrNull() ?: 0.35f),
                            "search_center" to (searchCenter.toBooleanStrictOrNull() ?: true),
                            "wallpaper" to wallpaper.trim(),
                            "type_scale" to (typeScale.toFloatOrNull() ?: 1f),
                            "recents_count" to (recentsCount.toIntOrNull() ?: 4),
                        ),
                    ),
                )
            }
        }) { Text("Save shell") }

        Text("Home", style = MaterialTheme.typography.titleLarge)
        Field("search_open_dp", searchOpenDp) { searchOpenDp = it }
        Field("search_snap", searchSnap) { searchSnap = it }
        Field("agent_open_dp", agentOpenDp) { agentOpenDp = it }
        Field("agent_snap", agentSnap) { agentSnap = it }
        Button(onClick = {
            runWrite {
                store.write(
                    "home.toml",
                    Toml.encode(
                        listOf(
                            "search_open_dp" to (searchOpenDp.toFloatOrNull() ?: 240f),
                            "search_snap" to (searchSnap.toFloatOrNull() ?: 0.42f),
                            "agent_open_dp" to (agentOpenDp.toFloatOrNull() ?: 240f),
                            "agent_snap" to (agentSnap.toFloatOrNull() ?: 0.42f),
                        ),
                    ),
                )
            }
        }) { Text("Save home") }

        Text("App limits", style = MaterialTheme.typography.titleLarge)
        Text(
            "Clamped on save. Text 512 KiB–4 MiB, images 2–8 MiB, files 256–1024, package 10–64 MiB.",
            style = MaterialTheme.typography.bodySmall,
        )
        Field("max_text_bytes", maxText) { maxText = it }
        Field("max_binary_bytes", maxBinary) { maxBinary = it }
        Field("max_files", maxFiles) { maxFiles = it }
        Field("max_package_bytes", maxPackage) { maxPackage = it }
        Button(onClick = {
            runWrite {
                store.write(
                    "limits.toml",
                    Toml.encode(
                        listOf(
                            "max_text_bytes" to (maxText.toIntOrNull() ?: 524288),
                            "max_binary_bytes" to (maxBinary.toIntOrNull() ?: 2_097_152),
                            "max_files" to (maxFiles.toIntOrNull() ?: 256),
                            "max_package_bytes" to (maxPackage.toIntOrNull() ?: 26_214_400),
                        ),
                    ),
                )
            }
        }) { Text("Save limits") }

        Text("Agent", style = MaterialTheme.typography.titleLarge)
        Field("api_base", apiBase) { apiBase = it }
        Field("model", model) { model = it }
        Field("max_tokens", maxTokens) { maxTokens = it }
        Field("context_window", contextWindow) { contextWindow = it }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(AgentWeb.SETTING, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Off: the agent asks before its first web.fetch. Pages it reads are untrusted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = config.agent.webRead,
                onCheckedChange = { on -> runWrite { AgentWeb.writeSetting(store, config.agent, on) } },
            )
        }
        Button(onClick = {
            runWrite {
                store.write(
                    "agent.toml",
                    Toml.encode(
                        listOf(
                            "api_base" to apiBase.trim(),
                            "model" to model.trim(),
                            "max_tokens" to (maxTokens.toIntOrNull() ?: 16384),
                            "context_window" to (contextWindow.toIntOrNull() ?: 128_000),
                            "web_read" to config.agent.webRead,
                        ),
                    ),
                )
            }
        }) { Text("Save agent") }

        Text("API key", style = MaterialTheme.typography.titleMedium)
        Text(
            if (hasKey) "A key is stored on this device." else "No key stored.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("OpenRouter key") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (apiKey.isBlank()) {
                    error = "Enter a key first."
                    return@Button
                }
                AgentKeyStore.set(context, apiKey)
                apiKey = ""
                hasKey = true
                error = null
            }) { Text("Save key") }
            TextButton(onClick = {
                AgentKeyStore.clear(context)
                apiKey = ""
                hasKey = false
            }) { Text("Clear key") }
        }

        Text("Reset", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { runWrite { store.reset("theme.toml") } }) { Text("Reset theme") }
            TextButton(onClick = { runWrite { store.reset("shell.toml") } }) { Text("Reset shell") }
            TextButton(onClick = { runWrite { store.reset("home.toml") } }) { Text("Reset home") }
            TextButton(onClick = { runWrite { store.reset("agent.toml") } }) { Text("Reset agent") }
            TextButton(onClick = { runWrite { store.reset("limits.toml") } }) { Text("Reset limits") }
        }
        TextButton(onClick = { runWrite { store.reset(null) } }) { Text("Reset all config") }
        TextButton(onClick = {
            DeviceTierStore.reset(context)
            DeviceTierProbe.schedule(context) { runWrite { store.reload() } }
            runWrite { store.reload() }
        }) { Text("Reset device tier probe") }
        Text(
            "Reset copies the current tree to a backup under the app files directory first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TextButton(onClick = {
            rawOpen = !rawOpen
            if (rawOpen) {
                rawBody = runCatching { store.read(rawPath) }.getOrDefault("")
            }
        }) { Text(if (rawOpen) "Hide TOML" else "Edit TOML") }
        if (rawOpen) {
            Text("Files", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                files.forEach { path ->
                    FilterChip(
                        selected = rawPath == path,
                        onClick = {
                            rawPath = path
                            rawBody = runCatching { store.read(path) }.getOrDefault("")
                        },
                        label = { Text(path.substringAfterLast('/')) },
                    )
                }
            }
            OutlinedTextField(
                value = rawBody,
                onValueChange = { rawBody = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                label = { Text(rawPath) },
            )
            Button(onClick = { runWrite { store.write(rawPath, rawBody) } }) { Text("Save TOML") }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
    )
}
