package app.deft.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import app.deft.DeftApp
import app.deft.bridge.BridgeService
import app.deft.data.NetworkHostSummary
import kotlinx.coroutines.launch

/** Hosts listed on the card; the full log keeps [app.deft.data.NetworkLog.MAX_ROWS] rows. */
private const val SHOWN_HOSTS = 12

/**
 * The app's "Block network" switch and the hosts it reached lately, with
 * how many requests went out and how many were stopped. On: the app's CSP has no remote origin,
 * open sockets close and `net.*` calls fail. Changes apply when you go back to the app.
 */
@Composable
internal fun NetworkSection(appId: String) {
    val repo = DeftApp.instance.repository
    val scope = rememberCoroutineScope()
    var blocked by remember(appId) { mutableStateOf(false) }
    var hosts by remember(appId) { mutableStateOf<List<NetworkHostSummary>>(emptyList()) }
    LaunchedEffect(appId) {
        blocked = repo.networkBlocked(appId)
        hosts = repo.networkLog.hosts(appId)
    }
    Text("Network", style = MaterialTheme.typography.titleSmall)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text("Block network", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (blocked) {
                    "The app cannot reach any server. Its own files still work."
                } else {
                    "The app reaches only the servers you allowed. Changes apply when you go back to the app."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = blocked,
            onCheckedChange = { on ->
                blocked = on
                scope.launch {
                    repo.setNetworkBlocked(appId, on)
                    if (on) BridgeService.instanceOrNull()?.releaseNetwork(appId)
                }
            },
        )
    }
    if (hosts.isEmpty()) {
        Text("No requests recorded yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text("Recent hosts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        hosts.take(SHOWN_HOSTS).forEach { h ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(h.host, style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append(countText(h.allowed, "request"))
                        if (h.blocked > 0) append(", ${h.blocked} blocked")
                        append(" · ")
                        append(h.kinds.joinToString(", "))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (h.blocked > 0 && h.allowed == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (hosts.size > SHOWN_HOSTS) {
            Text("and ${hosts.size - SHOWN_HOSTS} more", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = {
            scope.launch {
                repo.networkLog.clear(appId)
                hosts = emptyList()
            }
        }) { Text("Clear log") }
    }
}

private fun countText(n: Int, word: String): String = "$n $word" + if (n == 1) "" else "s"
