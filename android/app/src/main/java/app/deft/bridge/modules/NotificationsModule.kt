package app.deft.bridge.modules

import android.content.Context
import app.deft.DeftApp
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.HostSurfaces
import app.deft.bridge.NotificationsSpec
import app.deft.notifications.DeftNotificationListener
import app.deft.notifications.DeftNotificationListener.ActResult
import app.deft.special.SystemAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray

/**
 * `notifications.*` (PARITY G8, SPECIAL exclusive `notifications.listen`): read, dismiss, reply
 * to and open other apps' notifications through [DeftNotificationListener]. Only the holder of
 * group `notif-listener` gets here (the grant check in `BridgeService.invoke`; checked again).
 * The launcher's own notifications are invisible to every method. `open` needs the app on
 * screen; the others also work in background runs.
 */
class NotificationsModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = withContext(Dispatchers.IO) {
        val holder = DeftApp.instance.repository.specialHolder(NotificationsSpec.GROUP)
        if (holder != request.webAppId) {
            return@withContext BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "notifications.listen is revoked")
        }
        if (!SystemAccess.NOTIFICATION_LISTENER.isGranted(context)) {
            return@withContext BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Notification access")
        }
        val listener = DeftNotificationListener.instance
        if (listener == null) {
            DeftNotificationListener.rebind(context)
            return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Notification access is starting; try again in a moment")
        }
        when (request.method) {
            "notifications.active" -> {
                val rows = listener.activeShaped()
                BridgeOutcome.Ok(JsonArray(rows), metadata = """{"count":${rows.size}}""")
            }
            "notifications.dismiss" -> withKey(request) { key ->
                if (listener.dismiss(key)) BridgeOutcome.Ok() else noSuch()
            }
            "notifications.reply" -> when (val r = NotificationsSpec.parseReply(request.args)) {
                is NotificationsSpec.Parsed.Bad -> BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, r.message)
                is NotificationsSpec.Parsed.Ok -> outcome(listener.reply(r.value.key, r.value.actionIndex, r.value.text))
            }
            "notifications.open" -> {
                if (HostSurfaces.forRequest(request) == null) {
                    return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
                }
                withKey(request) { key -> outcome(listener.open(key)) }
            }
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private inline fun withKey(request: BridgeRequest, block: (String) -> BridgeOutcome): BridgeOutcome =
        when (val k = NotificationsSpec.parseKey(request.args)) {
            is NotificationsSpec.Parsed.Bad -> BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, k.message)
            is NotificationsSpec.Parsed.Ok -> block(k.value)
        }

    private fun noSuch() = BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "no such notification")

    private fun outcome(r: ActResult): BridgeOutcome = when (r) {
        ActResult.OK -> BridgeOutcome.Ok()
        ActResult.NO_SUCH -> noSuch()
        ActResult.NO_ACTION -> BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "no such action")
        ActResult.NOT_REPLY -> BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "that action takes no reply")
        ActResult.GONE -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The other app no longer accepts this")
    }
}
