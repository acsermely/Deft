package app.deft.location

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import app.deft.bridge.LocationSpec
import app.deft.catalog.Capability

// DEVICE-UNCHECKED (background location and geofences): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a run tracker records with the screen off; the notification stays visible.
// Remove this note once checked.
/**
 * Each app's geofences, as proximity alerts on Android's own [LocationManager] (no
 * Google services). The list is kept in private SharedPreferences, so it can be registered again
 * after a reboot ([restoreAll]); Android forgets proximity alerts when the phone restarts.
 * Alerts reach [GeofenceReceiver] (not exported) through a PendingIntent whose data names the
 * app and the geofence. Coordinates are user data and are never logged.
 */
object Geofences {
    private const val TAG = "Geofences"
    private const val PREFS = "fl_geofences"
    private const val KEY_PREFIX = "g/"
    const val SCHEME = "deft-geofence"

    private val lock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(webAppId: String) = KEY_PREFIX + webAppId

    /** The app's geofences that have not expired. */
    fun list(context: Context, webAppId: String, now: Long = System.currentTimeMillis()): List<LocationSpec.Geofence> =
        synchronized(lock) {
            val all = LocationSpec.decode(prefs(context).getString(key(webAppId), null))
            val live = all.filter { !it.expired(now) }
            if (live.size != all.size) {
                // Android dropped these alerts itself; drop the stored rows and the PendingIntents.
                for (g in all) if (g.expired(now)) unregister(context, webAppId, g.id)
                save(context, webAppId, live)
            }
            live
        }

    fun has(context: Context, webAppId: String): Boolean = list(context, webAppId).isNotEmpty()

    private fun save(context: Context, webAppId: String, list: List<LocationSpec.Geofence>) {
        val edit = prefs(context).edit()
        if (list.isEmpty()) edit.remove(key(webAppId)) else edit.putString(key(webAppId), LocationSpec.encode(list))
        edit.commit()
    }

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    sealed class AddResult {
        data object Added : AddResult()
        data object TooMany : AddResult()
        data class Failed(val message: String) : AddResult()
    }

    /** Adds or replaces [fence] for [webAppId] and registers its proximity alert. */
    fun add(context: Context, webAppId: String, fence: LocationSpec.Geofence, now: Long = System.currentTimeMillis()): AddResult {
        synchronized(lock) {
            val next = LocationSpec.withAdded(list(context, webAppId, now), fence, now) ?: return AddResult.TooMany
            val error = register(context, webAppId, fence, now)
            if (error != null) return AddResult.Failed(error)
            save(context, webAppId, next)
            return AddResult.Added
        }
    }

    /** Removes one geofence; false when the app has none with this id. */
    fun remove(context: Context, webAppId: String, id: String): Boolean {
        synchronized(lock) {
            val current = LocationSpec.decode(prefs(context).getString(key(webAppId), null))
            if (current.none { it.id == id }) return false
            unregister(context, webAppId, id)
            save(context, webAppId, current.filter { it.id != id })
            return true
        }
    }

    /** Removes every geofence of the app (revoke, uninstall). */
    fun removeAll(context: Context, webAppId: String) {
        synchronized(lock) {
            val current = LocationSpec.decode(prefs(context).getString(key(webAppId), null))
            for (g in current) unregister(context, webAppId, g.id)
            save(context, webAppId, emptyList())
        }
    }

    /** App ids that have stored geofences. */
    fun apps(context: Context): List<String> =
        prefs(context).all.keys.filter { it.startsWith(KEY_PREFIX) }.map { it.removePrefix(KEY_PREFIX) }

    /**
     * Registers every stored geofence again (after boot, and when the launcher process starts,
     * which covers an update or a force stop). [stillAllowed] drops apps that lost their grants.
     * Registering the same PendingIntent again replaces the old alert, so this is safe to repeat.
     */
    suspend fun restoreAll(context: Context, stillAllowed: suspend (String) -> Boolean) {
        val now = System.currentTimeMillis()
        for (webAppId in apps(context)) {
            if (!stillAllowed(webAppId)) {
                removeAll(context, webAppId)
                continue
            }
            for (g in list(context, webAppId, now)) {
                val error = register(context, webAppId, g, now)
                if (error != null) Log.w(TAG, "geofence not restored: $error")
            }
        }
    }

    private fun register(context: Context, webAppId: String, fence: LocationSpec.Geofence, now: Long): String? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return "This phone has no location service"
        if (!hasFineLocation(context)) return "Android has not given Deft the precise location permission"
        return try {
            lm.addProximityAlert(fence.lat, fence.lng, fence.radiusM, fence.remainingMs(now), pendingIntent(context, webAppId, fence.id, create = true)!!)
            null
        } catch (e: SecurityException) {
            "Android has not given Deft the location permission"
        } catch (e: IllegalArgumentException) {
            "Android did not accept this geofence"
        }
    }

    private fun unregister(context: Context, webAppId: String, id: String) {
        val pi = pendingIntent(context, webAppId, id, create = false) ?: return
        runCatching { context.getSystemService(LocationManager::class.java)?.removeProximityAlert(pi) }
        pi.cancel()
    }

    fun dataUri(webAppId: String, id: String): Uri =
        Uri.Builder().scheme(SCHEME).authority("g").appendPath(webAppId).appendPath(id).build()

    /** (webAppId, id) from [dataUri], or null. */
    fun parse(uri: Uri?): Pair<String, String>? {
        if (uri == null || uri.scheme != SCHEME) return null
        val segs = uri.pathSegments
        if (segs.size != 2) return null
        return segs[0] to segs[1]
    }

    /**
     * Mutable because [LocationManager] adds [LocationManager.KEY_PROXIMITY_ENTERING] to it; the
     * intent is explicit (our own receiver), and the receiver reads the app and the geofence
     * from the data, which the sender cannot change.
     */
    private fun pendingIntent(context: Context, webAppId: String, id: String, create: Boolean): PendingIntent? {
        val ctx = context.applicationContext
        val intent = Intent(ctx, GeofenceReceiver::class.java)
            .setAction(GeofenceReceiver.ACTION)
            .setData(dataUri(webAppId, id))
        val flags = PendingIntent.FLAG_MUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(ctx, 0, intent, flags)
    }

    /** Grants an app needs for its geofences to stay. */
    val NEEDED: Set<Capability> = setOf(Capability.LOCATION, Capability.LOCATION_BACKGROUND)
}
