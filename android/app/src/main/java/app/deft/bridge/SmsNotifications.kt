package app.deft.bridge

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.deft.R
import app.deft.ui.host.WebViewHostActivity

object SmsNotifications {
    private const val CHANNEL = "incoming_sms"

    fun notify(context: Context, webAppId: String, from: String) {
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.sms_channel))
                .build(),
        )
        val open = WebViewHostActivity.intent(context, webAppId)
        val pending = android.app.PendingIntent.getActivity(
            context,
            webAppId.hashCode(),
            open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SMS from $from")
            .setContentText("Open the SMS web app to read it. Message body is not shown here.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(from.hashCode(), n) }
    }

    fun notifyMms(context: Context, webAppId: String, from: String) {
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.sms_channel))
                .build(),
        )
        val open = WebViewHostActivity.intent(context, webAppId)
        val pending = android.app.PendingIntent.getActivity(
            context,
            webAppId.hashCode() + 1,
            open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MMS from $from")
            .setContentText("Open the SMS web app to read it. Attachment is not shown here.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify((from + "mms").hashCode(), n) }
    }
}
