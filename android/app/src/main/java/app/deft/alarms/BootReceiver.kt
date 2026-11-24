package app.deft.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.deft.DeftApp
import app.deft.bridge.BridgeService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val handler = CoroutineExceptionHandler { _, t -> Log.e(TAG, "boot work failed", t) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + handler).launch {
            try {
                val repo = DeftApp.instance.repository
                val now = System.currentTimeMillis()
                val appContext = context.applicationContext
                // PARITY G0: alarms do not survive a reboot; re-arm the daily special-access review.
                // The process start that delivered this broadcast has already run today's check.
                step("special review") { app.deft.special.SpecialReviewRunner.ensureScheduled(appContext) }
                // One failing alarm (or a refused service start) does not cost the others or the steps below.
                val rows = step("alarms") { repo.pendingAlarms() }.orEmpty()
                for (row in rows) {
                    step("alarm") {
                        if (row.triggerAt > now) {
                            AlarmScheduler.schedule(appContext, row)
                        } else {
                            BridgeService.alarmFired(appContext, row.webAppId, row.id)
                        }
                    }
                }
                // PARITY G5: Android forgets proximity alerts on reboot; register the geofences again.
                step("geofences") { app.deft.location.BackgroundLocation.restoreGeofences(appContext, repo) }
                // PARITY D2: `boot` trigger. BOOT_COMPLETED is exempt from Android 12+ background
                // start limits, so the service may be started from here.
                step("boot trigger") {
                    app.deft.background.BackgroundWake.wake(
                        appContext,
                        app.deft.background.BackgroundTriggers.BOOT,
                        "{}",
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    private inline fun <T> step(name: String, block: () -> T): T? =
        runCatching(block).onFailure { Log.w(TAG, "boot step $name failed", it) }.getOrNull()

    private companion object {
        const val TAG = "BootReceiver"
    }
}
