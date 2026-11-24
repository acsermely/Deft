package app.deft.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import app.deft.bridge.BridgeService

/**
 * Required by ROLE_SMS. Does not compose a native reply; forwards into the SMS web app.
 */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.data?.schemeSpecificPart?.substringBefore('?').orEmpty()
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        BridgeService.compose(this, to, body)
        stopSelf()
        return START_NOT_STICKY
    }
}
