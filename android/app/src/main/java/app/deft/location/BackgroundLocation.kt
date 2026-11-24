package app.deft.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import app.deft.DeftApp
import app.deft.background.AppForegroundService
import app.deft.background.FgsType
import app.deft.bridge.BridgeService
import app.deft.bridge.LocationSpec
import app.deft.catalog.Capability
import app.deft.catalog.isGranted
import app.deft.data.AppRepository
import app.deft.special.SpecialUse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * PARITY G5: `location.watchBackground` watches. Each one listens to Android's own GPS and
 * network providers ([LocationManager], no Google services) and only while the app's foreground
 * service of type `location` runs (D3): it ends when that service stops or changes type, when
 * `location` or `location.background` is turned off, and on uninstall. Fixes go to the app as
 * `location.fix`. While an app has a watch or a geofence, [SpecialUse] shows
 * "<App> is using: Your location all the time".
 */
object BackgroundLocation {
    const val EVENT_FIX = "location.fix"

    private class Watch(
        val id: String,
        val webAppId: String,
        val spec: LocationSpec.Watch,
    ) {
        @Volatile var lastGpsAt = 0L
        lateinit var listener: LocationListener
    }

    private val watches = ConcurrentHashMap<String, Watch>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    @Volatile private var listening = false

    private val fgsListener = AppForegroundService.Listener { webAppId, active ->
        if (!active || !fgsLocationActive(webAppId)) stopAll(DeftApp.instance, webAppId)
    }

    private fun listenToFgs() {
        if (listening) return
        synchronized(this) {
            if (listening) return
            AppForegroundService.addListener(fgsListener)
            listening = true
        }
    }

    /** The app holds both `location` and `location.background`. */
    suspend fun allowed(repository: AppRepository, webAppId: String): Boolean =
        repository.grantLevel(webAppId, Capability.LOCATION).isGranted() &&
            repository.grantLevel(webAppId, Capability.LOCATION_BACKGROUND).isGranted()

    /** The app's foreground service runs with type `location`. */
    fun fgsLocationActive(webAppId: String): Boolean {
        val s = AppForegroundService.session(webAppId) ?: return false
        return s.running && s.type == FgsType.LOCATION
    }

    fun count(webAppId: String): Int = watches.values.count { it.webAppId == webAppId }

    private fun has(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    sealed class StartResult {
        data class Started(val watchId: String) : StartResult()
        data class Failed(val message: String) : StartResult()
    }

    fun start(context: Context, webAppId: String, spec: LocationSpec.Watch): StartResult {
        val ctx = context.applicationContext
        val lm = ctx.getSystemService(LocationManager::class.java)
            ?: return StartResult.Failed("This phone has no location service")
        val fine = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = fine || has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!coarse) return StartResult.Failed(NO_PERMISSION)
        val available = lm.allProviders.toSet()
        val providers = buildList {
            if (fine && LocationManager.GPS_PROVIDER in available) add(LocationManager.GPS_PROVIDER)
            if (LocationManager.NETWORK_PROVIDER in available) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) return StartResult.Failed("This phone has no GPS or network location")
        listenToFgs()
        val watch = Watch(newId(), webAppId, spec)
        watch.listener = LocationListener { loc -> onFix(watch, loc) }
        var registered = 0
        for (p in providers) {
            try {
                lm.requestLocationUpdates(p, spec.intervalMs, spec.minDistanceM, watch.listener, Looper.getMainLooper())
                registered++
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        if (registered == 0) {
            lm.removeUpdates(watch.listener)
            return StartResult.Failed(NO_PERMISSION)
        }
        watches[watch.id] = watch
        // The service may have stopped while the listener was being added.
        if (!fgsLocationActive(webAppId)) {
            stop(ctx, webAppId, watch.id)
            return StartResult.Failed(NO_FGS)
        }
        return StartResult.Started(watch.id)
    }

    /** Ends one watch; false when the app has none with this id. */
    fun stop(context: Context, webAppId: String, watchId: String): Boolean {
        val w = watches[watchId]?.takeIf { it.webAppId == webAppId } ?: return false
        watches.remove(watchId)
        runCatching { context.applicationContext.getSystemService(LocationManager::class.java)?.removeUpdates(w.listener) }
        scope.launch { syncUse(context.applicationContext, webAppId) }
        return true
    }

    /** Ends every watch of the app (service stopped, grant off, uninstall). */
    fun stopAll(context: Context, webAppId: String) {
        val lm = context.applicationContext.getSystemService(LocationManager::class.java)
        for (w in watches.values.filter { it.webAppId == webAppId }) {
            watches.remove(w.id)
            runCatching { lm?.removeUpdates(w.listener) }
        }
        scope.launch { syncUse(context.applicationContext, webAppId) }
    }

    /**
     * PARITY G5: after a grant changed to "no", ends the app's watches and removes its
     * geofences when it lost `location` or `location.background`.
     */
    suspend fun recheckGrants(context: Context, repository: AppRepository, webAppId: String) {
        if (allowed(repository, webAppId)) return
        stopAll(context, webAppId)
        Geofences.removeAll(context, webAppId)
        syncUse(context, webAppId)
    }

    /**
     * Registers the stored geofences again and shows their "is using" notification: after boot
     * (Android forgets proximity alerts) and when the launcher process starts (an update or a
     * force stop drops them too). Apps that are gone or lost a grant lose their geofences.
     */
    suspend fun restoreGeofences(context: Context, repository: AppRepository) {
        val ctx = context.applicationContext
        Geofences.restoreAll(ctx) { id -> repository.getApp(id) != null && allowed(repository, id) }
        for (id in Geofences.apps(ctx)) syncUse(ctx, id)
    }

    /** Uninstall: watches, geofences and the notification go. */
    fun forgetApp(context: Context, webAppId: String) {
        stopAll(context, webAppId)
        Geofences.removeAll(context, webAppId)
        SpecialUse.stop(context, webAppId, Capability.LOCATION_BACKGROUND)
    }

    /** Shows the "is using" notification while the app has a watch or a geofence, else hides it. */
    suspend fun syncUse(context: Context, webAppId: String) {
        val ctx = context.applicationContext
        if (count(webAppId) > 0 || Geofences.has(ctx, webAppId)) {
            if (!SpecialUse.isActive(webAppId, Capability.LOCATION_BACKGROUND)) {
                SpecialUse.start(ctx, webAppId, Capability.LOCATION_BACKGROUND)
            }
        } else {
            SpecialUse.stop(ctx, webAppId, Capability.LOCATION_BACKGROUND)
        }
    }

    private fun onFix(watch: Watch, loc: Location) {
        if (watches[watch.id] !== watch) return
        val provider = loc.provider ?: return
        val now = System.currentTimeMillis()
        if (!LocationSpec.acceptFix(provider, now, watch.lastGpsAt, watch.spec.intervalMs)) return
        if (provider == LocationManager.GPS_PROVIDER) watch.lastGpsAt = now
        val fix = LocationSpec.Fix(
            lat = loc.latitude,
            lng = loc.longitude,
            accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
            altitude = if (loc.hasAltitude()) loc.altitude else null,
            speed = if (loc.hasSpeed()) loc.speed else null,
            bearing = if (loc.hasBearing()) loc.bearing else null,
            time = loc.time,
            provider = provider,
        )
        val payload = LocationSpec.fixPayload(watch.id, fix)
        scope.launch {
            val svc = BridgeService.instanceOrNull()
            if (svc != null) svc.emit(watch.webAppId, EVENT_FIX, payload)
            else DeftApp.instance.repository.enqueueEvent(watch.webAppId, EVENT_FIX, payload)
        }
    }

    private fun newId(): String {
        val b = ByteArray(8)
        random.nextBytes(b)
        return "w" + b.joinToString("") { "%02x".format(it) }
    }

    const val NO_FGS = "start fgs type location first"
    const val NO_PERMISSION = "Android has not given Deft the location permission"
}
