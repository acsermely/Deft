package app.deft.background

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.deft.DeftApp
import app.deft.R
import app.deft.bridge.BridgeService
import app.deft.bridge.MediaSessionSpec
import app.deft.catalog.Capability
import app.deft.data.AppRepository
import app.deft.ui.host.WebViewHostActivity
import app.deft.webview.WebViewHostRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

// DEVICE-UNCHECKED (typed foreground services): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a workout sample records a GPS track with the screen off, notification visible.
// Remove this note once checked.
/**
 * One typed foreground service for every app that called `fgs.start`. Each app gets its
 * own ongoing notification ("<App>: <title>", tap opens it, Stop ends it); the first one is the
 * service's own notification. While an app is listed here its host activity keeps its page running
 * when it leaves the screen (see [WebViewHostRuntime] and `WebViewHostActivity.onStop`).
 *
 * Started with `startService` from the bridge while the app is on screen, so Android allows the
 * `location` and `microphone` types (Android 14 starts those only from the foreground) and there is
 * no "must call startForeground" deadline to miss. All session changes happen on the main thread.
 */
class AppForegroundService : Service() {
    private var inForeground = false
    private var currentTypes = 0
    private var primaryId: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> intent.getStringExtra(EXTRA_WEB_APP_ID)?.let { stop(this, it, REASON_USER) }
            else -> {
                // A media button in the notification (older Androids draw these).
                val name = intent?.action?.takeIf { it.startsWith(ACTION_MEDIA_PREFIX) }?.removePrefix(ACTION_MEDIA_PREFIX)
                val action = MediaSessionSpec.Action.fromId(name)
                val id = intent?.getStringExtra(EXTRA_WEB_APP_ID)
                if (action != null && id != null) session(id)?.media?.press(action)
            }
        }
        refresh()
        return START_NOT_STICKY
    }

    /** Android 15: a `dataSync` service may run 6 hours a day; those apps stop, the rest go on. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        for (s in snapshot()) {
            if (s.type.serviceType and fgsType != 0) stop(this, s.webAppId, REASON_TIMEOUT)
        }
        refresh()
        // Android refused the narrower startForeground: the timed-out type is still held, and
        // keeping it past onTimeout crashes the process. Everyone stops.
        if (inForeground && currentTypes and fgsType != 0) {
            Log.w(TAG, "timed-out type still held; stopping every app")
            for (s in snapshot()) stop(this, s.webAppId, REASON_TIMEOUT)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
            currentTypes = 0
            primaryId = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        for (s in snapshot()) stop(this, s.webAppId, REASON_CLOSED)
        super.onDestroy()
    }

    /** Brings the service's types and the notifications in line with [sessions]. Main thread. */
    private fun refresh() {
        val list = snapshot()
        val nm = NotificationManagerCompat.from(this)
        if (list.isEmpty()) {
            if (inForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
            currentTypes = 0
            primaryId = null
            stopSelf()
            return
        }
        ensureChannel(this)
        val primary = list.firstOrNull { it.webAppId == primaryId } ?: list.first()
        val wanted = FgsType.union(list.map { it.type })
        if (!inForeground || wanted != currentTypes) {
            val grows = !inForeground || (wanted and currentTypes.inv()) != 0
            try {
                ServiceCompat.startForeground(this, FGS_ID, build(primary), wanted)
                inForeground = true
                currentTypes = wanted
            } catch (e: Exception) {
                // Only a new type can be refused; dropping a type keeps the wider set.
                Log.w(TAG, "startForeground refused: ${e.javaClass.simpleName}")
                val refused = if (!inForeground) list else list.filter { !it.running }
                if (grows && refused.isNotEmpty()) {
                    for (s in refused) {
                        removeSession(s.webAppId)
                        s.ready.complete("Android did not allow this kind of work to keep running")
                    }
                    if (inForeground) refresh() else stopSelf()
                    return
                }
            }
        }
        primaryId = primary.webAppId
        runCatching { nm.notify(FGS_ID, build(primary)) }
        nm.cancel(notificationId(primary.webAppId))
        for (s in list) {
            if (s.webAppId != primary.webAppId) runCatching { nm.notify(notificationId(s.webAppId), build(s)) }
        }
        for (s in list) {
            if (!s.running) {
                s.running = true
                WebViewHostRuntime.fgsStarted()
                notifyListeners(s.webAppId, true)
            }
            s.ready.complete(null)
        }
    }

    private fun build(s: Session): android.app.Notification {
        val code = notificationId(s.webAppId)
        val open = PendingIntent.getActivity(
            this,
            code,
            WebViewHostActivity.intent(this, s.webAppId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            code,
            Intent(this, AppForegroundService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_WEB_APP_ID, s.webAppId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Android 14 lets people swipe these away; the notification comes back while the app runs.
        val again = PendingIntent.getService(
            this,
            code,
            Intent(this, AppForegroundService::class.java).setAction(ACTION_SYNC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val media = s.media
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${s.appName}: ${s.title}")
            .setContentText(s.text ?: "Keeps running after you leave the app.")
            .setContentIntent(open)
            .setDeleteIntent(again)
        if (media == null) {
            builder.addAction(0, "Stop", stop)
        } else {
            // A media notification tied to the app's session; the app's name stays visible.
            val buttons = MediaSessionSpec.notificationButtons(media.actions, media.state)
            for (a in buttons) builder.addAction(mediaIcon(a), mediaLabel(a), mediaIntent(s.webAppId, a))
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            media.title?.let { builder.setContentTitle(it) }
            builder.setContentText(media.artist ?: s.text ?: s.title)
            builder.setSubText(s.appName)
            media.art?.let { builder.setLargeIcon(it) }
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(media.token)
                    .setShowActionsInCompactView(*buttons.indices.toList().toIntArray()),
            )
        }
        return builder
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun mediaIntent(webAppId: String, action: MediaSessionSpec.Action): PendingIntent =
        PendingIntent.getService(
            this,
            notificationId(webAppId),
            Intent(this, AppForegroundService::class.java)
                .setAction(ACTION_MEDIA_PREFIX + action.id)
                .putExtra(EXTRA_WEB_APP_ID, webAppId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun mediaIcon(action: MediaSessionSpec.Action): Int = when (action) {
        MediaSessionSpec.Action.PLAY -> android.R.drawable.ic_media_play
        MediaSessionSpec.Action.PAUSE -> android.R.drawable.ic_media_pause
        MediaSessionSpec.Action.NEXT -> android.R.drawable.ic_media_next
        MediaSessionSpec.Action.PREVIOUS -> android.R.drawable.ic_media_previous
        MediaSessionSpec.Action.SEEK_FORWARD -> android.R.drawable.ic_media_ff
        MediaSessionSpec.Action.SEEK_BACKWARD -> android.R.drawable.ic_media_rew
        else -> android.R.drawable.ic_menu_close_clear_cancel
    }

    private fun mediaLabel(action: MediaSessionSpec.Action): String = when (action) {
        MediaSessionSpec.Action.PLAY -> "Play"
        MediaSessionSpec.Action.PAUSE -> "Pause"
        MediaSessionSpec.Action.NEXT -> "Next"
        MediaSessionSpec.Action.PREVIOUS -> "Previous"
        MediaSessionSpec.Action.SEEK_FORWARD -> "Forward"
        MediaSessionSpec.Action.SEEK_BACKWARD -> "Back"
        MediaSessionSpec.Action.SEEK_TO -> "Seek"
        MediaSessionSpec.Action.STOP -> "Stop"
    }

    /** One running app. [ready] completes with null once Android accepted it, else with a message. */
    class Session(
        val webAppId: String,
        val appName: String,
        val type: FgsType,
        var title: String,
        var text: String?,
    ) {
        val ready = CompletableDeferred<String?>()
        var running = false

        /** The app's media session, only for a running `mediaPlayback` session. Main thread. */
        var media: AppMediaSession? = null
    }

    /** Told on the main thread when an app starts or stops keeping running. */
    fun interface Listener {
        fun onForegroundChanged(webAppId: String, active: Boolean)
    }

    companion object {
        private const val TAG = "AppForegroundService"
        const val CHANNEL = "app_running"
        const val FGS_ID = 0x46475301
        const val ACTION_STOP = "app.deft.FGS_STOP"
        const val ACTION_SYNC = "app.deft.FGS_SYNC"
        /** Followed by a [MediaSessionSpec.Action] id. */
        const val ACTION_MEDIA_PREFIX = "app.deft.FGS_MEDIA."
        const val EXTRA_WEB_APP_ID = "webAppId"

        const val REASON_USER = "user"
        const val REASON_REVOKED = "revoked"
        const val REASON_CLOSED = "closed"
        const val REASON_TIMEOUT = "timeout"

        @Volatile private var instance: AppForegroundService? = null
        private val lock = Any()
        private val sessions = LinkedHashMap<String, Session>()
        private val listeners = CopyOnWriteArrayList<Listener>()
        private val main = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun notificationId(webAppId: String): Int = ("fgs|$webAppId").hashCode()

        private fun snapshot(): List<Session> = synchronized(lock) { sessions.values.toList() }

        private fun removeSession(webAppId: String): Session? = synchronized(lock) { sessions.remove(webAppId) }

        fun session(webAppId: String): Session? = synchronized(lock) { sessions[webAppId] }

        /** True while [webAppId] keeps running (Android accepted its service). */
        fun isActive(webAppId: String): Boolean = session(webAppId)?.running == true

        fun addListener(l: Listener) { listeners += l }
        fun removeListener(l: Listener) { listeners -= l }

        private fun notifyListeners(webAppId: String, active: Boolean) {
            for (l in listeners) l.onForegroundChanged(webAppId, active)
        }

        fun ensureChannel(context: Context) {
            NotificationManagerCompat.from(context).createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Apps that keep running")
                    .setDescription("Shows while an app keeps running after you leave it. Tap Stop to end it.")
                    .build(),
            )
        }

        /** False when the person turned off notifications for the launcher or for this channel. */
        fun notificationsShown(context: Context): Boolean {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return false
            ensureChannel(context)
            val channel = nm.getNotificationChannel(CHANNEL) ?: return true
            return channel.importance != NotificationManager.IMPORTANCE_NONE
        }

        /**
         * Starts (or replaces) [webAppId]'s session and waits until Android accepted it. Returns
         * null on success, else a plain message. Call while the app is on screen.
         */
        suspend fun start(context: Context, session: Session): String? {
            val ctx = context.applicationContext
            val refused = withContext(Dispatchers.Main) {
                val old = session(session.webAppId)
                if (old != null && old.running && old.type == session.type) {
                    // Same app, same type: keep running, take the new text (and the media session).
                    session.running = true
                    session.media = old.media
                } else {
                    old?.media?.release()
                    if (old?.running == true) {
                        // A new type is a new start; the page keeps running while it is on screen anyway.
                        WebViewHostRuntime.fgsEnded()
                        notifyListeners(session.webAppId, false)
                    }
                }
                synchronized(lock) { sessions[session.webAppId] = session }
                try {
                    ctx.startService(Intent(ctx, AppForegroundService::class.java).setAction(ACTION_SYNC))
                    null
                } catch (e: IllegalStateException) {
                    // Android refuses services for apps in the background.
                    stop(ctx, session.webAppId, null)
                    "Open the app to do this"
                }
            }
            if (refused != null) return refused
            val result = withTimeoutOrNull(START_TIMEOUT_MS) { session.ready.await() }
            if (result == null && !session.ready.isCompleted) {
                withContext(Dispatchers.Main) { stop(ctx, session.webAppId, null) }
                return "Android did not start the service in time"
            }
            return result
        }

        /** New title or text for a running session; false when the app has none. */
        suspend fun update(context: Context, webAppId: String, title: String?, text: String?): Boolean =
            withContext(Dispatchers.Main) {
                val s = session(webAppId) ?: return@withContext false
                if (title != null) s.title = title
                if (text != null) s.text = text
                instance?.refresh()
                true
            }

        /**
         * Ends [webAppId]'s session on the main thread. [reason] non-null tells the app with
         * `fgs.stopped {reason}`; null is the app's own `fgs.stop`.
         */
        fun stop(context: Context, webAppId: String, reason: String?) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post { stop(context, webAppId, reason) }
                return
            }
            val s = removeSession(webAppId) ?: return
            s.media?.release()
            s.media = null
            NotificationManagerCompat.from(context.applicationContext).cancel(notificationId(webAppId))
            if (!s.ready.isCompleted) s.ready.complete("Stopped")
            if (s.running) {
                WebViewHostRuntime.fgsEnded()
                notifyListeners(webAppId, false)
            }
            instance?.refresh()
            if (reason != null && s.running) {
                val payload = """{"reason":"$reason"}"""
                scope.launch {
                    val svc = BridgeService.instanceOrNull()
                    if (svc != null) svc.emit(webAppId, "fgs.stopped", payload)
                    else DeftApp.instance.repository.enqueueEvent(webAppId, "fgs.stopped", payload)
                }
            }
        }

        /**
         * After a grant changed to "no", ends the app's session when it lost
         * `foreground` or the last grant its type needs (`location`, `microphone`, a device cap).
         * Losing `media.session` ends only the media session.
         */
        suspend fun recheckGrants(context: Context, repository: AppRepository, webAppId: String) {
            val s = session(webAppId) ?: return
            val granted = repository.grantedCapabilities(webAppId).toSet()
            if (!FgsType.stillAllowed(s.type, granted)) {
                stop(context, webAppId, REASON_REVOKED)
            } else if (Capability.MEDIA_SESSION !in granted) {
                // Without `media.session` the lock screen and headsets stop reaching the app.
                main.post { releaseMedia(webAppId) }
            }
        }

        /**
         * [webAppId]'s media session, created on first use, or null when the app has no
         * running foreground service of type `mediaPlayback`. Main thread.
         */
        fun media(context: Context, webAppId: String): AppMediaSession? {
            check(Looper.myLooper() == Looper.getMainLooper()) { "main thread only" }
            val s = session(webAppId)
            if (s == null || !s.running || s.type != FgsType.MEDIA_PLAYBACK) return null
            s.media?.let { return it }
            val ctx = context.applicationContext
            val open = PendingIntent.getActivity(
                ctx,
                notificationId(webAppId),
                WebViewHostActivity.intent(ctx, webAppId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return AppMediaSession(ctx, webAppId, open, ::emitMediaAction).also { s.media = it }
        }

        /** Redraws the app's notification after its media session changed. Main thread. */
        fun mediaChanged() {
            instance?.refresh()
        }

        /** `media.release`; the notification goes back to the plain one. Main thread. */
        fun releaseMedia(webAppId: String) {
            val s = session(webAppId) ?: return
            val m = s.media ?: return
            s.media = null
            m.release()
            instance?.refresh()
        }

        /** Buttons only matter to a page that is running; nothing is queued for later. */
        private fun emitMediaAction(webAppId: String, payload: String) {
            scope.launch {
                val svc = BridgeService.instanceOrNull() ?: return@launch
                if (svc.isLive(webAppId)) svc.emit(webAppId, "media.action", payload)
            }
        }

        private const val START_TIMEOUT_MS = 5_000L
    }
}
