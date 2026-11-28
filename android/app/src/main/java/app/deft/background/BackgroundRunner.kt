package app.deft.background

import app.deft.bridge.GrantedPrefixes
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import app.deft.webview.RenderGone
import app.deft.DeftApp
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeService
import app.deft.catalog.Capability
import app.deft.catalog.isGranted
import app.deft.data.InstalledWebApp
import app.deft.webview.LocalOriginPathHandler
import app.deft.workspace.BundledApps
import app.deft.workspace.DepStore
import app.deft.workspace.OverlayStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// DEVICE-UNCHECKED (headless background runner): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a background task runs after the app process is killed and shows in the audit log.
// Remove this note once checked.
/**
 * Host-scheduled, budgeted background runs, owned by [BridgeService].
 *
 * [trigger] is the only way in. A trigger runs the app's manifest `background` module when the
 * app declares the `background` capability, holds its grant and declares the trigger (the
 * dashboard's [BackgroundPolicy.MANUAL] is always allowed). Scheduling follows [RunnerQueue]:
 * one run per app and 3 at once, 10 waiting, `periodic` merged, 60 runs an hour, 30 s each. Every run, and
 * every refused or dropped trigger, is an audit row `background.run` with the trigger name and,
 * for runs, the duration in milliseconds. Payloads are never logged.
 */
class BackgroundRunner(
    private val service: BridgeService,
    private val budgetMs: Long = RunnerQueue.BUDGET_MS,
) {
    private val queue = RunnerQueue(budgetMs = budgetMs)
    private val lock = Any()
    private val sessions = ConcurrentHashMap<String, BackgroundSession>()
    private val seeded = ConcurrentHashMap.newKeySet<String>()
    private val main = Handler(Looper.getMainLooper())

    /** Per app: keeps the CPU awake from the trigger until its run (and the runs queued behind it) end. */
    private val wakeLocks = ConcurrentHashMap<String, PowerManager.WakeLock>()

    /** Per app: bumped by [cancel], so a run polled before it does not start after it (under [lock]). */
    private val cancels = HashMap<String, Int>()

    /** Per app: triggers handed to [trigger] whose [offer] has not finished (under [lock]). */
    private val offering = HashMap<String, Int>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Log.e(TAG, "background run failed", t) },
    )

    /** Audit rows outlive [shutdown]: the row of a run cut short by it is still written. */
    private val auditScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Log.e(TAG, "audit failed", t) },
    )

    private val repository get() = service.repository

    /**
     * Main thread: the blank page of each app's last clean run, kept [IDLE_MS] for the next run of that app. A batch of
     * queued runs of one app then costs one WebView (and renderer start) instead of N. Never shared across apps: the
     * page is bound to the app's profile and bridge. Destroyed on idle, on a run of another app, cancel and shutdown.
     */
    private val parked = HashMap<String, WebView>()
    private val parkExpiry = HashMap<String, Runnable>()

    @Volatile private var closed = false

    /**
     * Entry point for triggers (alarms, SMS, calls, notification actions, boot,
     * network, charging, periodic and push are wired here). [name] is a trigger name as in the manifest
     * (`periodic` for any `periodic:N`); [payloadJson] reaches the module as `payload`.
     */
    fun trigger(webAppId: String, name: String, payloadJson: String) {
        // Held before returning: the receiver or alarm that called this may let the CPU sleep.
        holdAwake(webAppId)
        synchronized(lock) { offering[webAppId] = (offering[webAppId] ?: 0) + 1 }
        scope.launch {
            try {
                offer(webAppId, name, payloadJson)
            } finally {
                synchronized(lock) {
                    val left = (offering[webAppId] ?: 1) - 1
                    if (left > 0) offering[webAppId] = left else offering.remove(webAppId)
                }
                releaseIfIdle(webAppId)
            }
        }
    }

    /** `background.done` from the app's run. */
    fun done(webAppId: String) {
        main.post { sessions[webAppId]?.end(OUTCOME_OK) }
    }

    /** Kill switch and uninstall: drops waiting triggers and ends the run now. */
    fun cancel(webAppId: String) {
        synchronized(lock) {
            queue.clear(webAppId)
            cancels[webAppId] = (cancels[webAppId] ?: 0) + 1
        }
        main.post {
            discardParked(webAppId)
            sessions[webAppId]?.end(OUTCOME_CANCELLED)
        }
    }

    /** The service is going away: every run ends. */
    fun shutdown() {
        closed = true
        val end = Runnable {
            for (session in sessions.values.toList()) session.end(OUTCOME_CANCELLED)
            for (id in parked.keys.toList()) discardParked(id)
            for (id in wakeLocks.keys.toList()) wakeLocks.remove(id)?.let { releaseLock(it) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) end.run() else main.post(end)
        scope.cancel()
    }

    private suspend fun offer(webAppId: String, name: String, payloadJson: String) {
        val app = repository.getApp(webAppId) ?: return
        refusal(app, name)?.let { reason ->
            audit(webAppId, name, reason, null)
            return
        }
        val now = System.currentTimeMillis()
        val urgentMs = BackgroundTriggers.urgentMs(name)
        val trigger = BackgroundTrigger(name, payloadJson, now, urgentMs?.let { now + it })
        // An urgent trigger joins the app's run already going instead of
        // waiting up to its whole budget behind it, unless that run ends before the trigger's
        // time is up (then it waits for the next run, ahead of the ordinary triggers).
        if (trigger.urgent) {
            val joined = withContext(Dispatchers.Main) {
                val session = sessions[webAppId] ?: return@withContext false
                queue.joins(session.startedAt, SystemClock.elapsedRealtime(), trigger, System.currentTimeMillis()) &&
                    session.deliver(trigger)
            }
            if (joined) {
                audit(webAppId, name, OUTCOME_JOINED, null)
                return
            }
        }
        if (seeded.add(webAppId)) {
            val earlier = repository.backgroundRunTimes(webAppId, now - RunnerQueue.HOUR_MS)
            synchronized(lock) { queue.seed(webAppId, earlier, now) }
        }
        val offered = synchronized(lock) { queue.offer(webAppId, trigger) }
        when (offered) {
            RunnerQueue.Offer.ACCEPTED -> pump(webAppId)
            RunnerQueue.Offer.QUEUE_FULL -> audit(webAppId, name, OUTCOME_QUEUE_FULL, null)
            RunnerQueue.Offer.COALESCED -> Unit
        }
    }

    /** Why [app] may not run [name] now, as an audit outcome; null when it may. */
    private suspend fun refusal(app: InstalledWebApp, name: String): String? {
        if (Capability.BACKGROUND !in app.requiredCapabilities) return OUTCOME_NOT_DECLARED
        if (!repository.grantLevel(app.id, Capability.BACKGROUND).isGranted()) return BridgeErrors.NOT_GRANTED
        val spec = repository.backgroundFor(app) ?: return OUTCOME_NOT_DECLARED
        if (name != BackgroundPolicy.MANUAL && !BackgroundPolicy.declares(spec.triggers, name)) return OUTCOME_NOT_DECLARED
        return null
    }

    private suspend fun pump(webAppId: String) {
        while (!closed) {
            val (poll, generation) = synchronized(lock) {
                queue.poll(webAppId, System.currentTimeMillis()) to (cancels[webAppId] ?: 0)
            }
            for (dropped in poll.dropped) audit(webAppId, dropped.name, OUTCOME_RATE_LIMITED, null)
            for (expired in poll.expired) audit(webAppId, expired.name, OUTCOME_EXPIRED, null)
            val next = poll.start ?: return releaseIfIdle(webAppId)
            val problem = runCatching { startRun(webAppId, next, generation) }.getOrElse {
                Log.e(TAG, "run did not start for $webAppId", it)
                OUTCOME_ERROR
            }
            if (problem == null) return
            // Not started: free the slot and try the next waiting trigger.
            synchronized(lock) { queue.finished(webAppId) }
            audit(webAppId, next.name, problem, null)
        }
    }

    /**
     * Starts the run; null when it started, else the audit outcome. [generation] is the app's
     * cancel count when the trigger was polled; a [cancel] since then means `cancelled`.
     */
    private suspend fun startRun(webAppId: String, trigger: BackgroundTrigger, generation: Int): String? {
        // Read fresh: the grant or the manifest may have changed while the trigger waited.
        val app = repository.getApp(webAppId) ?: return OUTCOME_NOT_DECLARED
        refusal(app, trigger.name)?.let { return it }
        val dir = servingDir(app) ?: return OUTCOME_NOT_DECLARED
        val context = DeftApp.instance
        val policy = repository.cspPolicyFor(app)
        val prefixes = grantedPrefixesOf(app)
        val assets = LocalOriginPathHandler.loader(
            dir,
            LocalOriginPathHandler.hostOfOrigin(app.origin),
            policy = policy,
            overlay = OverlayStore.dir(context, app.id),
            depStore = DepStore.root(context),
        )
        return withContext(Dispatchers.Main) {
            if (closed) return@withContext OUTCOME_CANCELLED
            // Kill switch or uninstall while the run was being prepared.
            if (synchronized(lock) { cancels[webAppId] ?: 0 } != generation) return@withContext OUTCOME_CANCELLED
            // A fresh timeout for this run's budget.
            holdAwake(webAppId)
            // Only this app's own parked page is reused; any other app's page is closed first.
            val reuse = parked[webAppId]?.also { parked.remove(webAppId); parkExpiry.remove(webAppId)?.let(main::removeCallbacks) }
            for (other in parked.keys.toList()) discardParked(other)
            val session = BackgroundSession(
                context = context.applicationContext,
                app = app,
                assets = assets,
                policy = policy,
                grantedPrefixes = prefixes,
                service = service,
                budgetMs = budgetMs,
                debugSlug = app.workspaceSlug,
                reuse = reuse,
                park = { view -> park(webAppId, view) },
            ) { ended, outcome -> onEnded(webAppId, trigger, ended, outcome) }
            sessions[webAppId] = session
            if (session.start(trigger)) {
                null
            } else {
                sessions.remove(webAppId, session)
                reuse?.let { RenderGone.dispose(it) }
                BridgeErrors.UNSUPPORTED
            }
        }
    }

    /** Main thread: keeps [view] (blank, bridge detached) for [IDLE_MS]. False when the runner is closed. */
    private fun park(webAppId: String, view: WebView): Boolean {
        if (closed) return false
        discardParked(webAppId)
        // A dead renderer drops the parked page; the process must survive it.
        view.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                RenderGone.log(TAG, webAppId, detail)
                if (parked[webAppId] === view) {
                    parked.remove(webAppId)
                    parkExpiry.remove(webAppId)?.let(main::removeCallbacks)
                }
                RenderGone.dispose(view)
                return true
            }
        }
        parked[webAppId] = view
        val expire = Runnable { discardParked(webAppId) }
        parkExpiry[webAppId] = expire
        main.postDelayed(expire, IDLE_MS)
        return true
    }

    /** Main thread: closes the app's parked page, if any. */
    private fun discardParked(webAppId: String) {
        parkExpiry.remove(webAppId)?.let(main::removeCallbacks)
        val view = parked.remove(webAppId) ?: return
        view.webViewClient = WebViewClient()
        runCatching { view.destroy() }
    }

    /** Main thread, once per started run. */
    private fun onEnded(webAppId: String, trigger: BackgroundTrigger, session: BackgroundSession, outcome: String) {
        sessions.remove(webAppId, session)
        val ms = if (session.startedAt > 0) SystemClock.elapsedRealtime() - session.startedAt else 0L
        synchronized(lock) { queue.finished(webAppId) }
        // Sockets and radios a run opened do not outlive it unless the app itself is open.
        if (!service.isLive(webAppId)) {
            service.releaseNetwork(webAppId)
            service.releaseBluetooth(webAppId)
        }
        audit(webAppId, trigger.name, outcome, ms)
        // The freed slot goes to apps that waited for one first, then to this app's next trigger.
        scope.launch {
            val waiting = synchronized(lock) { queue.waitingApps() }
            for (id in waiting) if (id != webAppId) pump(id)
            pump(webAppId)
        }
    }

    /**
     * Acquires (or renews the timeout of) the app's partial wake lock: the run's budget plus a
     * margin, so a run stuck past its budget cannot keep the CPU awake.
     */
    private fun holdAwake(webAppId: String) {
        if (closed) return
        val lock = wakeLocks.getOrPut(webAppId) {
            val power = DeftApp.instance.getSystemService(PowerManager::class.java) ?: return
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeTag(webAppId)).apply { setReferenceCounted(false) }
        }
        runCatching { lock.acquire(budgetMs + WAKE_MARGIN_MS) }.onFailure { Log.w(TAG, "wake lock not held", it) }
    }

    /** Releases the app's wake lock when no run is going on and nothing waits for one. */
    private fun releaseIfIdle(webAppId: String) {
        val idle = synchronized(lock) {
            webAppId !in offering && !queue.isRunning(webAppId) && queue.queued(webAppId) == 0
        }
        if (!idle || sessions.containsKey(webAppId)) return
        wakeLocks.remove(webAppId)?.let { releaseLock(it) }
    }

    private fun releaseLock(lock: PowerManager.WakeLock) {
        runCatching { if (lock.isHeld) lock.release() }
    }

    private fun audit(webAppId: String, trigger: String, outcome: String, ms: Long?) {
        val metadata = auditMetadata(trigger, ms)
        auditScope.launch {
            repository.log(webAppId, "background", Capability.BACKGROUND.id, AUDIT_METHOD, outcome, metadata)
        }
    }

    private suspend fun grantedPrefixesOf(app: InstalledWebApp): Set<String> = grantedPrefixesOf(repository, app)

    companion object {
        private const val TAG = "BackgroundRunner"

        /** Wake lock time past a run's budget (the budget timer ends the run before this). */
        const val WAKE_MARGIN_MS = 5_000L

        /** How long the last clean run's blank page stays parked waiting for the app's next queued run. */
        const val IDLE_MS = 8_000L

        /** `deft:bg:<short>`: short, and without the app id itself in system dumps. */
        fun wakeTag(webAppId: String): String = "deft:bg:" + Integer.toHexString(webAppId.hashCode())

        /**
         * Same files the host serves; URL-installed apps have none and cannot run in the background.
         * The live wallpaper serves the same files.
         */
        fun servingDir(app: InstalledWebApp): File? {
            val context = DeftApp.instance
            return app.workspaceSlug?.let { context.myApps.projectDir(it) }
                ?: BundledApps.idFromWebAppId(app.id)?.let { BundledApps.dir(context, it) }
        }

        /** Same rule as the host (`WebViewHostActivity.grantedPrefixesOf`), also the live wallpaper's. */
        suspend fun grantedPrefixesOf(repository: app.deft.data.AppRepository, app: InstalledWebApp): Set<String> =
            GrantedPrefixes.forApp(repository, app)

        /** Audit `method` of every run row. */
        const val AUDIT_METHOD = "background.run"

        const val OUTCOME_OK = "ok"
        const val OUTCOME_TIMEOUT = "timeout"
        const val OUTCOME_CANCELLED = "cancelled"
        const val OUTCOME_ERROR = "error"
        const val OUTCOME_NOT_DECLARED = "not_declared"
        const val OUTCOME_QUEUE_FULL = "queue_full"
        const val OUTCOME_RATE_LIMITED = "rate_limited"

        /** An urgent trigger was delivered into the app's run already going. */
        const val OUTCOME_JOINED = "joined"

        /** An urgent trigger's time was up before a run could start; it was not run. */
        const val OUTCOME_EXPIRED = "expired"

        /** Android refused to start the service for the trigger (background start limits). */
        const val OUTCOME_SERVICE_BLOCKED = "service_blocked"

        private val blockedScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Log.e(TAG, "audit failed", t) },
        )

        private fun auditMetadata(trigger: String, ms: Long?): String = buildJsonObject {
            put("trigger", JsonPrimitive(trigger.take(40)))
            if (ms != null) put("ms", JsonPrimitive(ms))
        }.toString()

        /** Audit row for a trigger lost because the service could not be started. */
        fun auditBlocked(webAppId: String, trigger: String) {
            val metadata = auditMetadata(trigger, null)
            blockedScope.launch {
                DeftApp.instance.repository.log(
                    webAppId, "background", Capability.BACKGROUND.id, AUDIT_METHOD, OUTCOME_SERVICE_BLOCKED, metadata,
                )
            }
        }

        /** Outcomes of runs that started; they count toward the hourly limit after a restart. */
        val RAN_OUTCOMES = listOf(OUTCOME_OK, OUTCOME_TIMEOUT, OUTCOME_CANCELLED, OUTCOME_ERROR)
    }
}
