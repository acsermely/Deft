package app.deft.push

import android.content.Context
import android.util.Log
import app.deft.DeftApp
import app.deft.background.BackgroundTriggers
import app.deft.background.BackgroundWake
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeService
import app.deft.bridge.NotifySpec
import app.deft.bridge.OriginNotifications
import app.deft.bridge.PushSpec
import app.deft.catalog.Capability
import app.deft.catalog.isGranted

/**
 * PARITY D6: hands a push message to its app, following [PushSpec.route]. Each message is an
 * audit row `push.message` with the route and the size in bytes; never the contents.
 */
object PushDelivery {
    private const val TAG = "PushDelivery"

    suspend fun deliver(context: Context, webAppId: String, bytes: ByteArray) {
        val repository = DeftApp.instance.repository
        val app = repository.getApp(webAppId)
        if (app == null) {
            PushRegistry.unregister(context, webAppId)
            return
        }
        if (Capability.PUSH !in app.requiredCapabilities || !repository.grantLevel(webAppId, Capability.PUSH).isGranted()) {
            PushRegistry.unregister(context, webAppId)
            audit(webAppId, BridgeErrors.NOT_GRANTED, bytes.size)
            return
        }
        if (PushSpec.tooLarge(bytes)) {
            audit(webAppId, OUTCOME_TOO_LARGE, bytes.size)
            return
        }
        val service = BridgeService.instanceOrNull()
        val live = service?.isLive(webAppId) == true
        val wants = BackgroundTriggers.wants(BackgroundWake.candidate(repository, app), BackgroundTriggers.PUSH)
        val notification = PushSpec.notification(bytes)
        val route = PushSpec.route(live, wants, notification != null)
        val payload = PushSpec.payload(bytes)
        // Live: straight to the page. Otherwise the event waits for the next open in every case.
        if (service != null) service.emit(webAppId, PushSpec.EVENT_MESSAGE, payload)
        else repository.enqueueEvent(webAppId, PushSpec.EVENT_MESSAGE, payload)
        var outcome = route.name.lowercase()
        when (route) {
            PushSpec.Route.TRIGGER -> {
                val started = BridgeService.backgroundTrigger(context, webAppId, BackgroundTriggers.PUSH, payload)
                // Android may refuse to wake a closed launcher; show the notification the message asks for.
                if (!started && notification != null) {
                    post(context, webAppId, app.name, notification)
                    outcome = "trigger_blocked_notify"
                }
            }
            PushSpec.Route.NOTIFY -> post(context, webAppId, app.name, notification!!)
            PushSpec.Route.EVENT, PushSpec.Route.QUEUE -> Unit
        }
        audit(webAppId, outcome, bytes.size)
    }

    /** `push.endpointChanged` / `push.unregistered` for an app that still holds `push`. */
    suspend fun notifyApp(webAppId: String, event: String, payloadJson: String) {
        val repository = DeftApp.instance.repository
        repository.getApp(webAppId) ?: return
        if (!repository.grantLevel(webAppId, Capability.PUSH).isGranted()) return
        val service = BridgeService.instanceOrNull()
        if (service != null) service.emit(webAppId, event, payloadJson)
        else repository.enqueueEvent(webAppId, event, payloadJson)
    }

    private fun post(context: Context, webAppId: String, appName: String, n: PushSpec.Notification) {
        val spec = NotifySpec.Post(
            id = "push-${System.currentTimeMillis()}",
            channel = NotifySpec.DEFAULT_CHANNEL,
            title = n.title,
            body = n.body,
            url = null,
            actions = emptyList(),
            reply = null,
            progress = null,
            ongoing = false,
            silent = false,
            group = null,
            imageBase64 = null,
        )
        try {
            OriginNotifications.post(context, webAppId, appName, spec)
        } catch (e: Exception) {
            Log.w(TAG, "push notification not shown: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun audit(webAppId: String, outcome: String, size: Int) {
        DeftApp.instance.repository.log(
            webAppId, "inbound", Capability.PUSH.id, PushSpec.EVENT_MESSAGE, outcome, "{\"bytes\":$size}",
        )
    }

    const val OUTCOME_TOO_LARGE = "too_large"
}
