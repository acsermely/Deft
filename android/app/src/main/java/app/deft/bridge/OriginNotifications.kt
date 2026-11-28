package app.deft.bridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationChannelGroupCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.deft.R
import app.deft.ui.host.WebViewHostActivity

/**
 * Notifications posted for web apps. Each app gets its own channel group in system
 * settings; channel ids and notification tags carry the app id, so apps only see their own.
 */
object OriginNotifications {
    private const val LEGACY_CHANNEL = "webapp_notify"
    const val EXTRA_NOTIFY_EVENT = "deft.notifyEvent"

    /** Simple post used by alarms and older callers. */
    fun post(context: Context, webAppId: String, title: String, body: String, url: String? = null) {
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(LEGACY_CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.notify_channel))
                .build(),
        )
        val open = WebViewHostActivity.intent(context, webAppId, url)
        val pending = PendingIntent.getActivity(
            context,
            (webAppId + title).hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, LEGACY_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify((webAppId + System.currentTimeMillis()).hashCode(), n) }
    }

    private fun ensureGroup(context: Context, webAppId: String, appName: String) {
        NotificationManagerCompat.from(context).createNotificationChannelGroup(
            NotificationChannelGroupCompat.Builder(NotifySpec.groupId(webAppId)).setName(appName).build(),
        )
    }

    fun createChannel(context: Context, webAppId: String, appName: String, channel: NotifySpec.Channel) {
        ensureGroup(context, webAppId, appName)
        val importance = when (channel.importance) {
            NotifySpec.Importance.MIN -> NotificationManagerCompat.IMPORTANCE_MIN
            NotifySpec.Importance.LOW -> NotificationManagerCompat.IMPORTANCE_LOW
            NotifySpec.Importance.DEFAULT -> NotificationManagerCompat.IMPORTANCE_DEFAULT
            NotifySpec.Importance.HIGH -> NotificationManagerCompat.IMPORTANCE_HIGH
        }
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(NotifySpec.androidChannelId(webAppId, channel.id), importance)
                .setName(channel.name)
                .setDescription(channel.description)
                .setGroup(NotifySpec.groupId(webAppId))
                .build(),
        )
    }

    fun channels(context: Context, webAppId: String): List<Pair<String, String>> {
        val prefix = NotifySpec.androidChannelId(webAppId, "")
        return NotificationManagerCompat.from(context).notificationChannelsCompat
            .filter { it.id.startsWith(prefix) }
            .map { it.id.removePrefix(prefix) to (it.name?.toString() ?: "") }
    }

    fun deleteChannel(context: Context, webAppId: String, id: String) {
        NotificationManagerCompat.from(context).deleteNotificationChannel(NotifySpec.androidChannelId(webAppId, id))
    }

    /** Uninstall: every notification and channel of the app goes. */
    fun removeAll(context: Context, webAppId: String) {
        val nm = NotificationManagerCompat.from(context)
        active(context, webAppId).forEach { nm.cancel(NotifySpec.tag(webAppId, it), 0) }
        runCatching { nm.deleteNotificationChannelGroup(NotifySpec.groupId(webAppId)) }
    }

    fun active(context: Context, webAppId: String): List<String> {
        val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return emptyList()
        return nm.activeNotifications.mapNotNull { NotifySpec.idFromTag(webAppId, it.tag) }.distinct()
    }

    fun cancel(context: Context, webAppId: String, id: String) {
        NotificationManagerCompat.from(context).cancel(NotifySpec.tag(webAppId, id), 0)
    }

    private fun requestCode(webAppId: String, id: String, what: String) = "$webAppId|$id|$what".hashCode()

    private fun actionIntent(context: Context, webAppId: String, id: String, action: String, mutable: Boolean): PendingIntent {
        val intent = Intent(context, NotifyActionReceiver::class.java).apply {
            this.action = NotifyActionReceiver.ACTION
            putExtra(NotifyActionReceiver.EXTRA_APP, webAppId)
            putExtra(NotifyActionReceiver.EXTRA_ID, id)
            putExtra(NotifyActionReceiver.EXTRA_ACTION, action)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(context, requestCode(webAppId, id, action), intent, flags)
    }

    /** Opens the app and, once its page subscribes, delivers `notify.action {id, action}`. */
    private fun openIntent(context: Context, webAppId: String, id: String, action: String, url: String?): PendingIntent {
        val event = NotifyActionReceiver.payload(id, action, null)
        val open = WebViewHostActivity.intent(context, webAppId, url).putExtra(EXTRA_NOTIFY_EVENT, event)
        return PendingIntent.getActivity(
            context,
            requestCode(webAppId, id, "open:$action"),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun post(context: Context, webAppId: String, appName: String, spec: NotifySpec.Post) {
        val nm = NotificationManagerCompat.from(context)
        val channelId = NotifySpec.androidChannelId(webAppId, spec.channel)
        if (nm.getNotificationChannelCompat(channelId) == null) {
            if (spec.channel != NotifySpec.DEFAULT_CHANNEL) throw IllegalArgumentException("unknown channel ${spec.channel}; create it first")
            createChannel(context, webAppId, appName, NotifySpec.Channel(NotifySpec.DEFAULT_CHANNEL, "Notifications", NotifySpec.Importance.DEFAULT, null))
        }
        val b = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setSubText(appName)
            .setContentIntent(openIntent(context, webAppId, spec.id, "tap", spec.url))
            .setDeleteIntent(actionIntent(context, webAppId, spec.id, "dismiss", mutable = false))
            .setAutoCancel(!spec.ongoing)
            .setOngoing(spec.ongoing)
            .setOnlyAlertOnce(spec.silent)
            .setSilent(spec.silent)
        val bitmap = spec.imageBase64?.let { b64 ->
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 1 })
            }.getOrNull()
        }
        when {
            bitmap != null -> b.setLargeIcon(bitmap).setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).bigLargeIcon(null as android.graphics.Bitmap?))
            spec.body.length > 60 -> b.setStyle(NotificationCompat.BigTextStyle().bigText(spec.body))
        }
        spec.progress?.let { b.setProgress(it.max, it.value, it.indeterminate) }
        spec.group?.let { b.setGroup("deft:$webAppId:$it") }
        for (a in spec.actions) {
            val pi = if (a.open) openIntent(context, webAppId, spec.id, a.id, spec.url)
            else actionIntent(context, webAppId, spec.id, a.id, mutable = false)
            b.addAction(0, a.title, pi)
        }
        spec.reply?.let { r ->
            val input = RemoteInput.Builder(NotifyActionReceiver.KEY_REPLY).setLabel(r.label).build()
            b.addAction(
                NotificationCompat.Action.Builder(0, r.title, actionIntent(context, webAppId, spec.id, r.actionId, mutable = true))
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .build(),
            )
        }
        nm.notify(NotifySpec.tag(webAppId, spec.id), 0, b.build())
    }
}
