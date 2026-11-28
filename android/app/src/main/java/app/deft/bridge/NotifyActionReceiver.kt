package app.deft.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import app.deft.DeftApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Notification buttons, inline replies and dismissals that do not open the app.
 * Each becomes `notify.action {id, action, reply?}` for the app; if the app is closed the event
 * waits in the queue until its page subscribes. it also wakes the app's background
 * module when the app declares the `notify.action` trigger.
 */
class NotifyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val webAppId = intent.getStringExtra(EXTRA_APP) ?: return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
        // A button press or reply clears the notification; the app posts a new one if it wants to.
        if (action != "dismiss") OriginNotifications.cancel(context, webAppId, id)
        val payload = payload(id, action, reply)
        val done = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val service = BridgeService.instanceOrNull()
                if (service != null) service.emit(webAppId, EVENT, payload)
                else DeftApp.instance.repository.enqueueEvent(webAppId, EVENT, payload)
                // The app's background module may handle the action while it is closed.
                // A notification action may start the service on Android 12+.
                runCatching {
                    app.deft.background.BackgroundWake.wake(
                        context.applicationContext,
                        app.deft.background.BackgroundTriggers.NOTIFY_ACTION,
                        payload,
                        only = webAppId,
                    )
                }
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val ACTION = "app.deft.NOTIFY_ACTION"
        const val EXTRA_APP = "webAppId"
        const val EXTRA_ID = "notifyId"
        const val EXTRA_ACTION = "notifyAction"
        const val KEY_REPLY = "deft.reply"
        const val EVENT = "notify.action"

        fun payload(id: String, action: String, reply: String?): String = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("action", JsonPrimitive(action))
            if (reply != null) put("reply", JsonPrimitive(reply))
        }.toString()
    }
}
