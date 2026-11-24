package app.deft.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import app.deft.data.WebAppEntity

@Composable
fun AllAppsPage(
    webApps: List<WebAppEntity>,
    nativeApps: List<NativeApp>,
    onOpen: (Launchable) -> Unit,
    onDashboard: () -> Unit,
    onSettings: () -> Unit,
    onAi: () -> Unit,
    hasApiKey: Boolean,
    onMyApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Apps",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onMyApps) { Text("My Apps") }
                IconButton(onClick = onAi, modifier = Modifier.alpha(if (hasApiKey) 1f else 0.45f)) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = "AI",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = onDashboard) { Text("Dashboard") }
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (webApps.isEmpty()) {
                item {
                    Text(
                        "No web apps yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(webApps, key = { it.id }) { app ->
                    val item = Launchable.Web(app)
                    AppListRow(item) { onOpen(item) }
                }
            }
            item {
                Text(
                    "Native",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
            }
            items(nativeApps, key = { it.packageName + it.activityName }) { app ->
                val item = Launchable.Native(app)
                AppListRow(item) { onOpen(item) }
            }
        }
    }
}
