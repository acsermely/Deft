package app.deft.push

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import org.unifiedpush.android.connector.UnifiedPush
import java.util.concurrent.ConcurrentHashMap

// DEVICE-UNCHECKED (UnifiedPush): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: an ntfy-backed sample receives a push with the app closed.
// Remove this note once checked.
/**
 * One UnifiedPush instance per web app (instance name = webAppId) and its endpoint,
 * kept in private SharedPreferences. The connector library keeps the instance tokens itself.
 * Endpoints are secrets of the app's server and are never logged.
 */
object PushRegistry {
    private const val PREFS = "fl_push"
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keyRegistered(webAppId: String) = "reg/$webAppId"
    private fun keyEndpoint(webAppId: String) = "ep/$webAppId"

    fun endpoint(context: Context, webAppId: String): String? = prefs(context).getString(keyEndpoint(webAppId), null)

    /** Installed distributor apps (package names), sorted. */
    fun distributors(context: Context): List<String> = UnifiedPush.getDistributors(context).sorted()

    /** The distributor in use, or null (none chosen yet, or it was uninstalled). */
    fun distributor(context: Context): String? = UnifiedPush.getSavedDistributor(context)

    /**
     * Asks the distributor for an endpoint for [webAppId]. Picks the first installed distributor
     * when none is chosen yet. Returns null when no distributor is installed; otherwise a
     * deferred that [onEndpoint] completes with the endpoint, or [onFailed] with null.
     */
    fun register(context: Context, webAppId: String): CompletableDeferred<String?>? {
        val all = distributors(context)
        if (all.isEmpty()) return null
        if (distributor(context) == null) UnifiedPush.saveDistributor(context, all.first())
        val waiter = waiters.getOrPut(webAppId) { CompletableDeferred() }
        prefs(context).edit().putBoolean(keyRegistered(webAppId), true).commit()
        UnifiedPush.registerApp(context, webAppId)
        return waiter
    }

    /**
     * Drops the app's instance and endpoint (`push.unregister`, revoke, uninstall). Does nothing
     * for an app that never registered: the library would otherwise clear every app's instance
     * when no distributor is chosen.
     */
    fun unregister(context: Context, webAppId: String) {
        val p = prefs(context)
        if (!p.getBoolean(keyRegistered(webAppId), false) && p.getString(keyEndpoint(webAppId), null) == null) return
        p.edit().remove(keyRegistered(webAppId)).remove(keyEndpoint(webAppId)).commit()
        waiters.remove(webAppId)?.complete(null)
        runCatching { UnifiedPush.unregisterApp(context, webAppId) }
    }

    /** NEW_ENDPOINT from the distributor; returns the endpoint stored before, if any. */
    fun onEndpoint(context: Context, webAppId: String, endpoint: String): String? {
        val p = prefs(context)
        val previous = p.getString(keyEndpoint(webAppId), null)
        p.edit().putBoolean(keyRegistered(webAppId), true).putString(keyEndpoint(webAppId), endpoint).commit()
        waiters.remove(webAppId)?.complete(endpoint)
        return previous
    }

    /** REGISTRATION_FAILED: the library has dropped the instance. */
    fun onFailed(context: Context, webAppId: String) {
        prefs(context).edit().remove(keyRegistered(webAppId)).remove(keyEndpoint(webAppId)).commit()
        waiters.remove(webAppId)?.complete(null)
    }

    /** UNREGISTERED (by the distributor, or because it was uninstalled); true when the app had an endpoint. */
    fun onUnregistered(context: Context, webAppId: String): Boolean {
        val p = prefs(context)
        val had = p.getString(keyEndpoint(webAppId), null) != null
        p.edit().remove(keyRegistered(webAppId)).remove(keyEndpoint(webAppId)).commit()
        waiters.remove(webAppId)?.complete(null)
        return had
    }
}
