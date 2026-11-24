package app.deft.special

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.deft.DeftApp
import app.deft.R
import app.deft.catalog.Capability
import app.deft.catalog.isGranted
import app.deft.ui.dashboard.DashboardActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * PARITY G0: "<App> is using <power>" while a [Capability.persistentWhileActive] power is in use.
 * The module that implements the power calls [start] when it begins and [stop] when it ends.
 * Revoking the grant or uninstalling the app stops it too.
 */
object SpecialUse {
    const val CHANNEL = "special_use"

    private val active = ConcurrentHashMap.newKeySet<String>()

    private fun key(webAppId: String, cap: Capability) = "$webAppId|${cap.id}"

    fun notificationId(webAppId: String, cap: Capability): Int = ("special-use|" + key(webAppId, cap)).hashCode()

    /** Posts the ongoing notification. Returns false (and posts nothing) without an active grant. */
    suspend fun start(context: Context, webAppId: String, cap: Capability): Boolean {
        val repo = DeftApp.instance.repository
        if (!repo.grantLevel(webAppId, cap).isGranted()) return false
        val app = repo.getApp(webAppId) ?: return false
        val ctx = context.applicationContext
        val nm = NotificationManagerCompat.from(ctx)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Special access in use")
                .setDescription("Shows while an app uses a power such as reading your notifications or your screen.")
                .build(),
        )
        val open = PendingIntent.getActivity(
            ctx,
            notificationId(webAppId, cap),
            DashboardActivity.intent(ctx, "apps"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${app.name} is using: ${cap.title}")
            .setContentText("Tap to see or remove this permission.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        active += key(webAppId, cap)
        runCatching { nm.notify(notificationId(webAppId, cap), n) }
        return true
    }

    fun stop(context: Context, webAppId: String, cap: Capability) {
        active -= key(webAppId, cap)
        NotificationManagerCompat.from(context.applicationContext).cancel(notificationId(webAppId, cap))
    }

    fun stopAll(context: Context, webAppId: String) {
        for (cap in Capability.entries) {
            if (cap.persistentWhileActive) stop(context, webAppId, cap)
        }
    }

    fun isActive(webAppId: String, cap: Capability): Boolean = key(webAppId, cap) in active
}
