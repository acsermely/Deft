package app.deft.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.deft.bridge.BridgeService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val webAppId = intent?.getStringExtra(EXTRA_WEB_APP_ID) ?: return
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: return
        BridgeService.alarmFired(context, webAppId, alarmId)
    }

    companion object {
        const val EXTRA_WEB_APP_ID = "webAppId"
        const val EXTRA_ALARM_ID = "alarmId"

        fun intent(context: Context, webAppId: String, alarmId: String): Intent =
            Intent(context, AlarmReceiver::class.java).apply {
                putExtra(EXTRA_WEB_APP_ID, webAppId)
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
    }
}
