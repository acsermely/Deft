package app.deft.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.deft.DeftApp
import app.deft.agent.AgentModelChoices
import app.deft.config.AgentConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The thin line under the prompt input: `model · effort`. The model opens the model menu; the effort opens a menu
 * with the effort levels and the Fast switch. Every change is saved to agent.toml at once.
 */
@Composable
fun AgentModelLine(enabled: Boolean = true, modifier: Modifier = Modifier) {
    val store = DeftApp.instance.configStore
    val config by store.config.collectAsState()
    val agent = config.agent
    val scope = rememberCoroutineScope()
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }

    fun save(next: AgentConfig) {
        scope.launch(Dispatchers.IO) { runCatching { store.write("agent.toml", next.toToml()) } }
    }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val effortLabel = agent.effort.replaceFirstChar { it.uppercase() } + if (agent.fast) " · Fast" else ""
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f, fill = false)) {
            LineItem(
                AgentModelChoices.find(agent.model)?.label ?: agent.model.substringAfterLast('/'),
                muted,
                enabled,
            ) { modelMenu = true }
            LauncherMenu(expanded = modelMenu, onDismiss = { modelMenu = false }) {
                AgentModelChoices.ALL.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(if (choice.free) "${choice.label} (free)" else choice.label) },
                        trailingIcon = { if (agent.model == choice.id) Icon(Icons.Outlined.Check, contentDescription = "Selected") },
                        onClick = {
                            modelMenu = false
                            save(agent.copy(model = choice.id))
                        },
                    )
                }
                if (AgentModelChoices.isFree(agent.model)) {
                    Text(
                        AgentModelChoices.FREE_TRAINING_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Text("·", style = MaterialTheme.typography.labelSmall, color = muted)
        Box {
            LineItem(effortLabel, muted, enabled) { effortMenu = true }
            LauncherMenu(expanded = effortMenu, onDismiss = { effortMenu = false }) {
                AgentModelChoices.EFFORTS.forEach { level ->
                    DropdownMenuItem(
                        text = { Text(level.replaceFirstChar { it.uppercase() }) },
                        trailingIcon = { if (agent.effort == level) Icon(Icons.Outlined.Check, contentDescription = "Selected") },
                        onClick = {
                            effortMenu = false
                            save(agent.copy(effort = level))
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Fast") },
                    trailingIcon = { Switch(checked = agent.fast, onCheckedChange = { save(agent.copy(fast = it)) }) },
                    onClick = { save(agent.copy(fast = !agent.fast)) },
                )
            }
        }
    }
}

/** The launcher's menu look, as the composer's attach menu: sheet background, no tint, 12dp corners. */
@Composable
private fun LauncherMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(12.dp),
    ) { content() }
}

@Composable
private fun LineItem(text: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (enabled) color else color.copy(alpha = color.alpha * 0.5f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    )
}
