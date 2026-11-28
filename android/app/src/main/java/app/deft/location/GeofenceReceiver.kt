package app.deft.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import app.deft.DeftApp
import app.deft.background.BackgroundTriggers
import app.deft.background.BackgroundWake
import app.deft.bridge.BridgeService
import app.deft.bridge.LocationSpec
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// DEVICE-UNCHECKED (background location and geofences): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a run tracker records with the screen off; the notification stays visible.
// Remove this note once checked.
/**
 * A proximity alert from [LocationManager] for one app's geofence (see [Geofences]).
 * Not exported: only the launcher's own PendingIntent reaches it. The app gets event
 * `location.geofence {id, entering}` (queued while it is closed) and, if it declares the
 * `geofence` trigger and holds `background`, a background run with the same payload.
 */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val (webAppId, id) = Geofences.parse(intent.data) ?: return
        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        val appContext = context.applicationContext
        val pending = goAsync()
        val handler = CoroutineExceptionHandler { _, t -> Log.e(TAG, "geofence alert failed", t) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + handler).launch {
            try {
                val repo = DeftApp.instance.repository
                // A stale alert (removed, revoked, uninstalled) is dropped and unregistered.
                if (Geofences.list(appContext, webAppId).none { it.id == id } || !BackgroundLocation.allowed(repo, webAppId)) {
                    Geofences.remove(appContext, webAppId, id)
                    BackgroundLocation.syncUse(appContext, webAppId)
                    return@launch
                }
                val payload = LocationSpec.geofencePayload(id, entering)
                repo.log(webAppId, "location", "location.background", "location.geofence", "ok")
                val svc = BridgeService.instanceOrNull()
                if (svc != null) svc.emit(webAppId, EVENT, payload) else repo.enqueueEvent(webAppId, EVENT, payload)
                runCatching { BackgroundWake.wake(appContext, BackgroundTriggers.GEOFENCE, payload, only = webAppId) }
                    .onFailure { Log.w(TAG, "geofence trigger failed", it) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "app.deft.GEOFENCE"
        const val EVENT = "location.geofence"
        private const val TAG = "GeofenceReceiver"
    }
}
