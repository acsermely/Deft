package app.deft.ui.home

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.deft.DeftApp
import app.deft.data.WebAppEntity
import app.deft.registry.DeftManifestParser
import app.deft.registry.DeftShortcut
import app.deft.ui.host.WebViewHostActivity
import app.deft.workspace.BundledApps
import app.deft.workspace.MergedAppTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** PARITY C3: launcher shortcuts from the app's (merged) manifest. */
object AppShortcuts {
    fun load(context: Context, app: WebAppEntity): List<DeftShortcut> {
        val base = app.workspaceSlug?.let { DeftApp.instance.myApps.projectDir(it) }
            ?: BundledApps.idFromWebAppId(app.id)?.let { BundledApps.dir(context, it) }
            ?: return emptyList()
        val manifest = MergedAppTree.of(context, base, app.id).manifestFile()
        if (!manifest.isFile) return emptyList()
        return runCatching { DeftManifestParser.parse(manifest.readText()).shortcuts }.getOrDefault(emptyList())
    }

    /** "./#new" and "/compose" both resolve on the app's own origin. */
    fun absoluteUrl(origin: String, url: String): String = origin.trimEnd('/') + "/" + url.removePrefix("./").removePrefix("/")
}

/**
 * Click opens the item; long-press on a web app shows its shortcuts. Native apps keep a plain
 * click. Wrap the item's content with the returned modifier and call [menu] inside it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberShortcutClick(item: Launchable?, enabled: Boolean = true, onOpen: () -> Unit): Pair<Modifier, @Composable () -> Unit> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shortcuts by remember(item) { mutableStateOf<List<DeftShortcut>>(emptyList()) }
    var open by remember(item) { mutableStateOf(false) }
    val web = (item as? Launchable.Web)?.app
    val modifier = Modifier.combinedClickable(
        enabled = enabled && item != null,
        onClick = onOpen,
        onLongClick = if (web == null) null else {
            {
                scope.launch {
                    shortcuts = withContext(Dispatchers.IO) { AppShortcuts.load(context, web) }
                    open = shortcuts.isNotEmpty()
                }
            }
        },
    )
    val menu: @Composable () -> Unit = {
        if (web != null) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                shortcuts.forEach { sc ->
                    DropdownMenuItem(
                        text = { Text(sc.name) },
                        onClick = {
                            open = false
                            context.startActivity(
                                WebViewHostActivity.intent(context, web.id, AppShortcuts.absoluteUrl(web.origin, sc.url)),
                            )
                        },
                    )
                }
            }
        }
    }
    return modifier to menu
}
