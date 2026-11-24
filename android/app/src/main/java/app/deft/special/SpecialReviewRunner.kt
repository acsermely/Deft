package app.deft.special

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.deft.DeftApp
import app.deft.R
import app.deft.a11y.DeftAccessibilityService
import app.deft.bridge.A11ySpec
import app.deft.catalog.Capability
import app.deft.catalog.GrantLevel
import app.deft.catalog.Risk
import app.deft.ui.dashboard.DashboardActivity

/**
 * PARITY G0: the daily "still want this?" check for special access. Runs when the launcher
 * process starts and from a daily inexact alarm (re-armed at boot). No WorkManager.
 */
object SpecialReviewRunner {
    const val CHANNEL = "special_review"
    private const val PREFS = "special_review"
    private const val ALARM_REQUEST = 0x5EC1A1

    private fun key(webAppId: String, capId: String) = "$webAppId|$capId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun reminderId(webAppId: String, capId: String): Int = ("special-review|" + key(webAppId, capId)).hashCode()

    /** Arms the daily alarm once per process lifetime of the alarm (boot and force-stop clear it). */
    fun ensureScheduled(context: Context) {
        val ctx = context.applicationContext
        val intent = Intent(ctx, SpecialReviewReceiver::class.java).setAction(SpecialReviewReceiver.ACTION_CHECK)
        val existing = PendingIntent.getBroadcast(
            ctx,
            ALARM_REQUEST,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (existing != null) return
        val pi = PendingIntent.getBroadcast(
            ctx,
            ALARM_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + AlarmManager.INTERVAL_DAY,
            AlarmManager.INTERVAL_DAY,
            pi,
        )
    }

    suspend fun runCheck(context: Context, now: Long = System.currentTimeMillis()) {
        val ctx = context.applicationContext
        val repo = DeftApp.instance.repository
        val prefs = prefs(ctx)
        for (row in repo.grantedSpecial()) {
            val cap = Capability.fromId(row.capability) ?: continue
            val k = key(row.webAppId, cap.id)
            val reminded = prefs.getLong(k, -1L).takeIf { it >= 0 }
            val action = SpecialReview.decide(row.grantedAt, repo.lastUsed(row.webAppId, cap.id), reminded, now)
            when (action) {
                SpecialReview.Action.NONE -> Unit
                SpecialReview.Action.REMIND -> {
                    val name = repo.getApp(row.webAppId)?.name ?: continue
                    postReminder(ctx, row.webAppId, name, cap, buttons = !a11yWatching())
                    prefs.edit().putLong(k, now).apply()
                }
                SpecialReview.Action.REVOKE -> {
                    val name = repo.getApp(row.webAppId)?.name ?: "An app"
                    // Not the user's denial: the app may ask again (see GrantEntity.deniedByUser).
                    repo.setGrant(row.webAppId, cap, GrantLevel.DENIED, byUser = false)
                    repo.log(row.webAppId, "special", cap.id, null, "auto_revoked")
                    postRevoked(ctx, row.webAppId, name, cap)
                }
            }
        }
    }

    /**
     * True while a web app holds `accessibility` and the launcher's service runs: it could press
     * "Keep" in the shade (SystemUI windows stay usable for it), so the reminder then has no
     * buttons and keeping happens on the dashboard, which accessibility cannot touch (PARITY G9).
     */
    private suspend fun a11yWatching(): Boolean =
        DeftAccessibilityService.instance != null &&
            DeftApp.instance.repository.specialHolder(A11ySpec.GROUP) != null

    /** "Keep" from the reminder: restarts the 30/90-day clock. */
    suspend fun keep(context: Context, webAppId: String, cap: Capability) {
        if (cap.risk != Risk.SPECIAL) return
        if (a11yWatching()) return
        val repo = DeftApp.instance.repository
        repo.keepGrant(webAppId, cap)
        repo.log(webAppId, "special", cap.id, null, "kept")
        forget(context, webAppId, cap)
    }

    /** "Remove" from the reminder. */
    suspend fun remove(context: Context, webAppId: String, cap: Capability) {
        if (cap.risk != Risk.SPECIAL) return
        val repo = DeftApp.instance.repository
        repo.setGrant(webAppId, cap, GrantLevel.DENIED)
        repo.log(webAppId, "special", cap.id, null, "removed")
    }

    /** Clears reminder state and the reminder notification for one grant. */
    fun forget(context: Context, webAppId: String, cap: Capability) {
        prefs(context).edit().remove(key(webAppId, cap.id)).apply()
        NotificationManagerCompat.from(context.applicationContext).cancel(reminderId(webAppId, cap.id))
    }

    fun forgetApp(context: Context, webAppId: String) {
        for (cap in Capability.entries) {
            if (cap.risk == Risk.SPECIAL) forget(context, webAppId, cap)
        }
    }

    private fun ensureChannel(ctx: Context) {
        NotificationManagerCompat.from(ctx).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Permission reminders")
                .setDescription("Asks whether apps should keep special access they have not used.")
                .build(),
        )
    }

    private fun openDashboard(ctx: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            requestCode,
            DashboardActivity.intent(ctx, "apps"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun action(ctx: Context, action: String, webAppId: String, cap: Capability): PendingIntent {
        val intent = Intent(ctx, SpecialReviewReceiver::class.java)
            .setAction(action)
            .putExtra(SpecialReviewReceiver.EXTRA_WEB_APP_ID, webAppId)
            .putExtra(SpecialReviewReceiver.EXTRA_CAPABILITY, cap.id)
        return PendingIntent.getBroadcast(
            ctx,
            (action + key(webAppId, cap.id)).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun postReminder(ctx: Context, webAppId: String, name: String, cap: Capability, buttons: Boolean) {
        ensureChannel(ctx)
        val id = reminderId(webAppId, cap.id)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Still want $name to have \"${cap.title}\"?")
            .setContentText("It has not used it for 30 days. It is removed after 90 days without use.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$name has not used \"${cap.title}\" for 30 days. If it stays unused, Deft removes it after 90 days.",
                ),
            )
            .setContentIntent(openDashboard(ctx, id))
            .apply {
                if (buttons) {
                    addAction(0, "Keep", action(ctx, SpecialReviewReceiver.ACTION_KEEP, webAppId, cap))
                    addAction(0, "Remove", action(ctx, SpecialReviewReceiver.ACTION_REMOVE, webAppId, cap))
                }
            }
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    private fun postRevoked(ctx: Context, webAppId: String, name: String, cap: Capability) {
        ensureChannel(ctx)
        val id = reminderId(webAppId, cap.id)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Removed from $name: ${cap.title}")
            .setContentText("It was not used for 90 days. You can allow it again from the app's card.")
            .setContentIntent(openDashboard(ctx, id))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}
