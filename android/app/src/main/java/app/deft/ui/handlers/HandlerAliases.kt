package app.deft.ui.handlers

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.deft.DeftApp
import app.deft.catalog.Capability
import app.deft.catalog.isGranted
import app.deft.data.AppRepository
import app.deft.data.InstalledWebApp
import app.deft.registry.DeftHandles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// DEVICE-UNCHECKED (inbound "Open with" handlers): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: "Open with" a PDF from the Files app opens the FL app with the content readable via the bridge.
// Remove this note once checked.
/**
 * Keeps the launcher's "Open with" aliases enabled only while an installed app
 * handles them, so Deft does not show up in other apps' choosers for nothing.
 * Re-evaluated on install, uninstall, manifest change, `share.receive` grant change and process start.
 */
object HandlerAliases {
    private const val TAG = "HandlerAliases"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /**
     * What [app] can actually receive: declared `handles`, with `mimeTypes` dropped unless the app
     * holds `share.receive` (files arrive in its share inbox and are read with `intents.readShare`).
     */
    suspend fun effectiveHandles(repository: AppRepository, app: InstalledWebApp): DeftHandles? {
        val declared = repository.handlesFor(app) ?: return null
        val canRead = Capability.SHARE_RECEIVE in app.requiredCapabilities &&
            repository.grantLevel(app.id, Capability.SHARE_RECEIVE).isGranted()
        val effective = if (canRead) declared else declared.copy(mimeTypes = emptyList())
        return effective.takeUnless { it.mimeTypes.isEmpty() && it.schemes.isEmpty() }
    }

    suspend fun sync(context: Context, repository: AppRepository) = lock.withLock {
        val all = repository.allApps().mapNotNull { effectiveHandles(repository, it) }
        val on = HandlerMatch.enabledAliases(all)
        val pm = context.packageManager
        for (alias in HandlerAlias.entries) {
            val component = ComponentName(context.packageName, alias.className)
            val want = if (alias in on) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            val now = runCatching { pm.getComponentEnabledSetting(component) }.getOrNull() ?: continue
            // DEFAULT means the manifest value, which is disabled for every alias.
            val effectiveNow = if (now == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                now
            }
            if (effectiveNow != want) {
                runCatching { pm.setComponentEnabledSetting(component, want, PackageManager.DONT_KILL_APP) }
                    .onFailure { Log.w(TAG, "alias ${alias.key} not updated", it) }
            }
        }
    }

    /** Off the caller's thread; never fails the caller. */
    fun syncSoon(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { sync(app, DeftApp.instance.repository) }
                .onFailure { Log.w(TAG, "sync failed", it) }
        }
    }
}

/** The "Always" choice per alias (alias key → webAppId). Cleared on the dashboard or on uninstall. */
class HandlerDefaults(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fl_handler_defaults", Context.MODE_PRIVATE)

    fun get(alias: HandlerAlias): String? = prefs.getString(alias.key, null)

    fun set(alias: HandlerAlias, webAppId: String) {
        prefs.edit().putString(alias.key, webAppId).apply()
    }

    fun clear(alias: HandlerAlias) {
        prefs.edit().remove(alias.key).apply()
    }

    fun forApp(webAppId: String): List<HandlerAlias> =
        HandlerAlias.entries.filter { get(it) == webAppId }

    fun removeApp(webAppId: String) {
        val edit = prefs.edit()
        forApp(webAppId).forEach { edit.remove(it.key) }
        edit.apply()
    }
}
