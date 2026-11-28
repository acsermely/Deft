package app.deft.webview

import android.webkit.WebView
import java.util.IdentityHashMap

/**
 * Chromium [WebView.pauseTimers] is process-wide. [WebView.onPause] also tears down
 * the shared geolocation / device-orientation dispatcher used by every WebView in
 * this process. With one activity per web app, pausing the background host therefore
 * kills GPS and compass in the foreground host.
 *
 * Keep every live WebView resumed while any host activity is resumed. Only pause
 * (and pause timers) when the last host goes to the background.
 */
object WebViewHostRuntime {
    private val lock = Any()
    private val views = IdentityHashMap<WebView, Unit>()
    private val gate = WebViewHostPauseGate()

    /** Live wallpaper views that are hidden but not yet paused (see [wallpaperHidden]). */
    private val hiddenWallpapers = IdentityHashMap<WebView, Unit>()

    fun attach(view: WebView) {
        val resumeNow = synchronized(lock) {
            views[view] = Unit
            gate.keepsViewsRunning
        }
        if (resumeNow) {
            view.onResume()
            view.resumeTimers()
        }
    }

    fun detach(view: WebView) {
        synchronized(lock) { views.remove(view) }
    }

    fun hostResumed() {
        val action = synchronized(lock) { gate.resume() }
        if (action == WebViewHostPauseGate.Action.RESUME_ALL) {
            applyResume()
        }
    }

    fun hostPaused() {
        val action = synchronized(lock) { gate.pause() }
        when (action) {
            WebViewHostPauseGate.Action.PAUSE_ALL -> applyPause()
            WebViewHostPauseGate.Action.PAUSE_VIEWS -> applyPause(keepTimers = true)
            else -> Unit
        }
    }

    /**
     * A background run needs JS timers and parsing, which [WebView.pauseTimers] stops
     * for the whole process. [view] is the run's off-screen WebView (not tracked here).
     */
    fun backgroundStarted(view: WebView) {
        val action = synchronized(lock) { gate.backgroundStarted() }
        if (action == WebViewHostPauseGate.Action.RESUME_TIMERS) view.resumeTimers()
    }

    /** The run's WebView is still alive here; destroy it afterwards. */
    fun backgroundEnded(view: WebView) {
        val action = synchronized(lock) { gate.backgroundEnded() }
        if (action == WebViewHostPauseGate.Action.PAUSE_TIMERS) view.pauseTimers()
    }

    /**
     * An app keeps running with a foreground service. Its page must not be paused when
     * its activity stops; [WebView.onPause] on any view would also end geolocation for it (see the
     * class comment), so every host view stays resumed until the last such service ends. Main thread.
     */
    fun fgsStarted() {
        val action = synchronized(lock) { gate.fgsStarted() }
        if (action == WebViewHostPauseGate.Action.RESUME_ALL) applyResume()
    }

    /** The service ended; pause as if the last host had just left (when none is in front). */
    fun fgsEnded() {
        val action = synchronized(lock) { gate.fgsEnded() }
        when (action) {
            WebViewHostPauseGate.Action.PAUSE_ALL -> applyPause()
            WebViewHostPauseGate.Action.PAUSE_VIEWS -> applyPause(keepTimers = true)
            else -> Unit
        }
    }

    /**
     * The live wallpaper became visible. Its page runs like a background run (timers
     * on even with no host in front). [view] is not tracked with the host views. Main thread.
     */
    fun wallpaperShown(view: WebView) {
        val action = synchronized(lock) {
            hiddenWallpapers.remove(view)
            gate.backgroundStarted()
        }
        view.onResume()
        if (action == WebViewHostPauseGate.Action.RESUME_TIMERS) view.resumeTimers()
    }

    /**
     * The live wallpaper was hidden (an app covers it, or the screen went off). The
     * view is paused now only when no host view runs: [WebView.onPause] would end geolocation for
     * the app in front (see the class comment). Otherwise it is paused together with the host
     * views when the last host leaves; until then the caller keeps it hidden. Main thread.
     */
    fun wallpaperHidden(view: WebView) {
        val (action, pauseNow) = synchronized(lock) {
            val a = gate.backgroundEnded()
            val now = !gate.keepsViewsRunning
            if (!now) hiddenWallpapers[view] = Unit
            a to now
        }
        if (pauseNow) view.onPause()
        if (action == WebViewHostPauseGate.Action.PAUSE_TIMERS) view.pauseTimers()
    }

    /**
     * A launcher WebView outside the hosts (home canvas, agent preview, verifier, lint page) is
     * on screen or working. [token] identifies it (repeat calls are no-ops); [view] is its
     * WebView. Timers are process-wide, so none are paused until it calls [ownerPaused]. The
     * owner resumes and pauses its own view. Main thread.
     */
    fun ownerResumed(token: Any, view: WebView) {
        val action = synchronized(lock) { gate.ownerResumed(token) }
        if (action == WebViewHostPauseGate.Action.RESUME_TIMERS) view.resumeTimers()
    }

    /** The owner of [token] left the screen or finished; call before destroying [view]. */
    fun ownerPaused(token: Any, view: WebView) {
        val action = synchronized(lock) { gate.ownerPaused(token) }
        if (action == WebViewHostPauseGate.Action.PAUSE_TIMERS) view.pauseTimers()
    }

    /** The wallpaper view is being destroyed (after [wallpaperHidden]). */
    fun wallpaperGone(view: WebView) {
        synchronized(lock) { hiddenWallpapers.remove(view) }
    }

    private fun snapshot(): List<WebView> = synchronized(lock) { views.keys.toList() }

    private fun applyResume() {
        val list = snapshot()
        for (view in list) {
            view.onResume()
            view.resumeTimers()
        }
    }

    private fun applyPause(keepTimers: Boolean = false) {
        val list = snapshot()
        for (view in list) view.onPause()
        // Hidden live wallpapers waited for this moment to pause.
        val wallpapers = synchronized(lock) { hiddenWallpapers.keys.toList().also { hiddenWallpapers.clear() } }
        for (view in wallpapers) view.onPause()
        if (!keepTimers) list.firstOrNull()?.pauseTimers()
    }
}

/**
 * Pure counter so inverted activity-lifecycle order cannot pause timers while a host is visible.
 * Background runs keep the process-wide timers running while no host is resumed.
 * While any app keeps running with a foreground service, nothing is paused.
 * Launcher WebViews outside the hosts ("owners": home canvas, agent preview, verifier, lint)
 * keep the timers running while resumed, like background runs.
 */
class WebViewHostPauseGate {
    var resumedHosts: Int = 0
        private set

    var backgroundRuns: Int = 0
        private set

    /** Apps running with a foreground service. */
    var foregroundServices: Int = 0
        private set

    val hasResumedHosts: Boolean get() = resumedHosts > 0

    private val owners = HashSet<Any>()

    /** Resumed owner views (see [ownerResumed]). */
    val resumedOwners: Int get() = owners.size

    /** Timers run without host views: a background run or a resumed owner view. */
    private val timersWithoutHosts: Boolean get() = backgroundRuns > 0 || owners.isNotEmpty()

    /** Host views and timers run: a host is in front or an app keeps running. */
    val keepsViewsRunning: Boolean get() = resumedHosts > 0 || foregroundServices > 0

    fun resume(): Action {
        val wasRunning = keepsViewsRunning
        resumedHosts++
        return if (!wasRunning) Action.RESUME_ALL else Action.NONE
    }

    fun pause(): Action {
        if (resumedHosts > 0) resumedHosts--
        return pauseIfIdle()
    }

    fun backgroundStarted(): Action {
        val hadTimers = keepsViewsRunning || timersWithoutHosts
        backgroundRuns++
        return if (!hadTimers) Action.RESUME_TIMERS else Action.NONE
    }

    fun backgroundEnded(): Action {
        if (backgroundRuns == 0) return Action.NONE
        backgroundRuns--
        return timersIfIdle()
    }

    /** An owner view is resumed; RESUME_TIMERS when timers may have been paused. */
    fun ownerResumed(token: Any): Action {
        val hadTimers = keepsViewsRunning || timersWithoutHosts
        if (!owners.add(token)) return Action.NONE
        return if (!hadTimers) Action.RESUME_TIMERS else Action.NONE
    }

    fun ownerPaused(token: Any): Action {
        if (!owners.remove(token)) return Action.NONE
        return timersIfIdle()
    }

    private fun timersIfIdle(): Action =
        if (!keepsViewsRunning && !timersWithoutHosts) Action.PAUSE_TIMERS else Action.NONE

    /** Normally called while the app is in front, so usually NONE. */
    fun fgsStarted(): Action {
        val wasRunning = keepsViewsRunning
        foregroundServices++
        return if (!wasRunning) Action.RESUME_ALL else Action.NONE
    }

    fun fgsEnded(): Action {
        if (foregroundServices == 0) return Action.NONE
        foregroundServices--
        return pauseIfIdle()
    }

    private fun pauseIfIdle(): Action = when {
        keepsViewsRunning -> Action.NONE
        timersWithoutHosts -> Action.PAUSE_VIEWS
        else -> Action.PAUSE_ALL
    }

    /**
     * RESUME_ALL / PAUSE_ALL: host views and timers. PAUSE_VIEWS: host views only, timers stay
     * for a background run or an owner view. RESUME_TIMERS / PAUSE_TIMERS: timers only, for background runs.
     */
    enum class Action { RESUME_ALL, PAUSE_ALL, PAUSE_VIEWS, RESUME_TIMERS, PAUSE_TIMERS, NONE }
}
