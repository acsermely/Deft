package app.deft.ui.dashboard

import app.deft.data.WebAppEntity
import app.deft.workspace.BundledApps

/**
 * The Apps tab lists every app the launcher knows, so each one's permissions can be found there (special-access
 * notices and the agent link to this tab for any app). Local apps the user or the agent made come first, then apps
 * installed from the web, then the bundled samples.
 */
internal data class DashboardAppGroups(
    val mine: List<WebAppEntity>,
    val installed: List<WebAppEntity>,
    val bundled: List<WebAppEntity>,
) {
    companion object {
        fun of(apps: List<WebAppEntity>): DashboardAppGroups {
            val byName = compareBy<WebAppEntity> { it.name.lowercase() }.thenBy { it.id }
            val bundled = apps.filter { isBundled(it) }
            val mine = apps.filter { !isBundled(it) && it.workspaceSlug != null }
            val installed = apps.filter { !isBundled(it) && it.workspaceSlug == null }
            return DashboardAppGroups(mine.sortedWith(byName), installed.sortedWith(byName), bundled)
        }

        fun isBundled(app: WebAppEntity): Boolean =
            app.tier == BundledApps.TIER_BUNDLED || (app.workspaceSlug == null && app.id.startsWith("bundled:"))
    }
}
