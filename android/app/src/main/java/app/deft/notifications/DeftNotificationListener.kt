package app.deft.notifications

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import app.deft.DeftApp
import app.deft.background.BackgroundTriggers
import app.deft.background.BackgroundWake
import app.deft.bridge.BridgeService
import app.deft.bridge.NotificationsSpec
import app.deft.catalog.Capability
import app.deft.catalog.isGranted
import app.deft.special.SpecialUse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * PARITY G8: the launcher's notification listener (capability `notifications.listen`). Android
 * binds it once the user turns on Notification access for the launcher; the FL grant decides
 * which one web app (the holder of exclusive group `notif-listener`) gets anything.
 *
 * - The holder is read again for every event, so revoking the grant or uninstalling the holder
 *   stops forwarding at once. With no holder, notifications are read and dropped.
 * - The launcher's own notifications (every web app's `notify.post` included) are never forwarded.
 * - Events reach only an open page of the holder and are never queued (message text is not
 *   stored by the launcher). While the holder is closed, a non-ongoing posted notification wakes
 *   its background module (trigger `notification`) if it declares that trigger and holds `background`.
 * - While a holder exists and the listener is connected, "<App> is using: Read all your
 *   notifications" shows ([SpecialUse]).
 *
 * Notification text is never logged.
 */
class DeftNotificationListener : NotificationListenerService() {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val recent = NotificationsSpec.RecentPosts()
    private val labels = ConcurrentHashMap<String, String>()

    @Volatile private var connected = false

    @Volatile private var usingFor: String? = null
    private var lastAudit = 0L

    override fun onListenerConnected() {
        connected = true
        instance = this
        scope.launch { syncUse(currentHolder()) }
    }

    override fun onListenerDisconnected() {
        connected = false
        if (instance === this) instance = null
        scope.launch { syncUse(null) }
    }

    override fun onDestroy() {
        connected = false
        if (instance === this) instance = null
        usingFor?.let { SpecialUse.stop(this, it, CAP) }
        usingFor = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null || !NotificationsSpec.forwardable(sbn.packageName, packageName)) return
        val shaped = runCatching { shape(sbn, rankingMap, privacy()) }.getOrNull() ?: return
        val ongoing = sbn.isOngoing
        scope.launch {
            if (recent.isRepeat(sbn.key, NotificationsSpec.content(shaped))) return@launch
            deliver(NotificationsSpec.EVENT_POSTED, shaped.toString(), wake = NotificationsSpec.wakesBackground(ongoing))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        if (sbn == null || !NotificationsSpec.forwardable(sbn.packageName, packageName)) return
        val key = sbn.key
        scope.launch {
            recent.forget(key)
            deliver(NotificationsSpec.EVENT_REMOVED, NotificationsSpec.removedPayload(key, reason), wake = false)
        }
    }

    private suspend fun currentHolder(): String? =
        DeftApp.instance.repository.specialHolder(NotificationsSpec.GROUP)

    private suspend fun deliver(event: String, payload: String, wake: Boolean) {
        val repo = DeftApp.instance.repository
        val holder = currentHolder()
        syncUse(holder)
        if (holder == null || !repo.grantLevel(holder, CAP).isGranted()) return
        val svc = BridgeService.instanceOrNull()
        // Live only: emit() would queue the text to disk if the page closed after an isLive check.
        if (svc != null && svc.emitLive(holder, event, payload)) {
            audit(holder, event)
        } else if (wake) {
            runCatching { BackgroundWake.wake(this, BackgroundTriggers.NOTIFICATION, payload, only = holder) }
        }
    }

    private suspend fun audit(holder: String, event: String) {
        val now = System.currentTimeMillis()
        if (!NotificationsSpec.auditDue(lastAudit, now)) return
        lastAudit = now
        DeftApp.instance.repository.log(holder, "notifications", CAP.id, event, "ok")
    }

    /** Shows or moves the "in use" notification to match [holder] while connected. */
    private suspend fun syncUse(holder: String?) {
        val want = if (connected) holder else null
        val cur = usingFor
        if (want == cur && (want == null || SpecialUse.isActive(want, CAP))) return
        if (cur != null && cur != want) SpecialUse.stop(this, cur, CAP)
        usingFor = if (want != null && SpecialUse.start(this, want, CAP)) want else null
    }

    // ---- Android reading -------------------------------------------------------------------

    private fun privacy(): NotificationsSpec.Privacy {
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (!locked) return NotificationsSpec.Privacy.UNLOCKED
        // Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS (hidden name); unreadable counts as "hide".
        val allow = runCatching {
            Settings.Secure.getInt(contentResolver, "lock_screen_allow_private_notifications", 1)
        }.getOrDefault(0)
        return NotificationsSpec.Privacy(locked = true, hideSensitiveOnLockScreen = allow == 0)
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private fun shape(sbn: StatusBarNotification, rankingMap: RankingMap?, privacy: NotificationsSpec.Privacy): JsonObject {
        val n = sbn.notification
        val ex = n.extras
        val pub = n.publicVersion?.extras
        val channelVis = runCatching {
            val r = Ranking()
            if (rankingMap?.getRanking(sbn.key, r) == true) r.channel?.lockscreenVisibility else null
        }.getOrNull() ?: NotificationsSpec.NO_OVERRIDE
        val raw = NotificationsSpec.Raw(
            key = sbn.key,
            pkg = sbn.packageName,
            appLabel = label(sbn.packageName),
            title = (ex.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString(),
            text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString(),
            subText = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            time = sbn.postTime,
            category = n.category,
            ongoing = sbn.isOngoing,
            visibility = n.visibility,
            channelLockscreen = channelVis,
            publicTitle = pub?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            publicText = pub?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            hasPublicVersion = n.publicVersion != null,
            actions = n.actions.orEmpty().map { a ->
                NotificationsSpec.Action(a.title?.toString(), a.remoteInputs.orEmpty().any { it.allowFreeFormInput })
            },
        )
        return NotificationsSpec.shape(raw, privacy)
    }

    /** The other apps' notifications in the shared shape. */
    fun activeShaped(): List<JsonObject> {
        val privacy = privacy()
        val ranking = runCatching { currentRanking }.getOrNull()
        return runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { NotificationsSpec.forwardable(it.packageName, packageName) }
            .sortedByDescending { it.postTime }
            .mapNotNull { runCatching { shape(it, ranking, privacy) }.getOrNull() }
    }

    private fun find(key: String): StatusBarNotification? =
        runCatching { getActiveNotifications(arrayOf(key)) }.getOrNull()
            ?.firstOrNull { it.key == key && NotificationsSpec.forwardable(it.packageName, packageName) }

    fun dismiss(key: String): Boolean {
        find(key) ?: return false
        return runCatching { cancelNotification(key) }.isSuccess
    }

    enum class ActResult { OK, NO_SUCH, NO_ACTION, NOT_REPLY, GONE }

    fun reply(key: String, actionIndex: Int, text: String): ActResult {
        val sbn = find(key) ?: return ActResult.NO_SUCH
        val action = sbn.notification.actions?.getOrNull(actionIndex) ?: return ActResult.NO_ACTION
        val input = action.remoteInputs.orEmpty().firstOrNull { it.allowFreeFormInput } ?: return ActResult.NOT_REPLY
        val fill = Intent()
        RemoteInput.addResultsToIntent(arrayOf(input), fill, Bundle().apply { putCharSequence(input.resultKey, text) })
        RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return try {
            action.actionIntent.send(this, 0, fill)
            ActResult.OK
        } catch (_: PendingIntent.CanceledException) {
            ActResult.GONE
        }
    }

    /** Sends the notification's tap action; the caller made sure the holder is on screen. */
    fun open(key: String): ActResult {
        val sbn = find(key) ?: return ActResult.NO_SUCH
        val n = sbn.notification
        val pi = n.contentIntent ?: return ActResult.NO_ACTION
        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }
        return try {
            pi.send(this, 0, null, null, null, null, options)
            if (n.flags and Notification.FLAG_AUTO_CANCEL != 0) runCatching { cancelNotification(key) }
            ActResult.OK
        } catch (_: PendingIntent.CanceledException) {
            ActResult.GONE
        }
    }

    companion object {
        private val CAP = Capability.NOTIFICATIONS_LISTEN

        @Volatile var instance: DeftNotificationListener? = null
            private set

        fun component(context: Context) = ComponentName(context, DeftNotificationListener::class.java)

        /** Asks Android to bind the listener again (access is on, but it is not connected). */
        fun rebind(context: Context) {
            runCatching { requestRebind(component(context)) }
                .onFailure { Log.w("DeftNotificationListener", "rebind failed: ${it.javaClass.simpleName}") }
        }

        /** After the grant changed: show or move the "in use" notification. */
        fun syncUse(context: Context) {
            val l = instance
            if (l == null) {
                rebind(context)
                return
            }
            l.scope.launch { l.syncUse(l.currentHolder()) }
        }
    }
}
