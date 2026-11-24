package app.deft.agent

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import app.deft.R
import app.deft.ui.home.HomeActivity

object AgentNotify {
    const val CHANNEL = "agent"
    private const val DONE_ID = 0xA6E47

    fun idForTab(tabId: String?): Int {
        if (tabId.isNullOrBlank()) return DONE_ID
        return DONE_ID xor tabId.hashCode()
    }

    fun screenLocked(context: Context): Boolean {
        val app = context.applicationContext
        val locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val interactive = app.getSystemService(PowerManager::class.java)?.isInteractive != false
        return locked || !interactive
    }

    fun post(
        context: Context,
        title: String,
        body: String,
        openAgent: Boolean = false,
        id: Int? = null,
        tabId: String? = null,
    ) {
        val app = context.applicationContext
        val nm = NotificationManagerCompat.from(app)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(app.getString(R.string.agent_channel))
                .build(),
        )
        val open = Intent(app, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(HomeActivity.EXTRA_OPEN_AGENT, openAgent)
        if (!tabId.isNullOrBlank()) open.putExtra(HomeActivity.EXTRA_AGENT_TAB, tabId)
        val pending = PendingIntent.getActivity(
            app,
            (title + (tabId ?: "")).hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val avatar = IconCompat.createWithResource(app, R.drawable.ic_agent_notify_color)
        val agent = Person.Builder()
            .setName(app.getString(R.string.agent_notify_title))
            .setIcon(avatar)
            .setBot(true)
            .build()
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_agent_notify)
            .setLargeIcon(avatar.toIcon(app))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(
                NotificationCompat.MessagingStyle(agent)
                    .addMessage(body, System.currentTimeMillis(), agent),
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching { nm.notify(id ?: idForTab(tabId), n) }
    }

    fun postDone(context: Context, title: String, body: String, tabId: String = "") {
        val key = tabId.takeIf { it.isNotBlank() }
        post(context, title, body, openAgent = true, id = idForTab(key), tabId = key)
    }

    fun cancelTab(context: Context, tabId: String?) {
        NotificationManagerCompat.from(context.applicationContext).cancel(idForTab(tabId))
    }
}
