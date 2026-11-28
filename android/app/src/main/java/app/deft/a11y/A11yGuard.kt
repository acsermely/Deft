package app.deft.a11y

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Window
import app.deft.bridge.A11ySpec
import java.util.Collections
import java.util.WeakHashMap

// DEVICE-UNCHECKED (accessibility service): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: an automation sample taps a button in another app; grant screens stay unreadable.
// Remove this note once checked.
/**
 * Knows when a guarded launcher screen ([A11ySpec.guardedLauncherActivity]: consent,
 * confirm, special grant, handler picker, keystore unlock, dashboard, pickers) is started, so
 * the accessibility service can leave every launcher window out and refuse to act while it shows.
 * The service runs in the launcher's process, so this is read directly.
 *
 * A gesture dispatched before such a screen opened could still land on it (gestures last up to
 * 10 s). Guarded screens therefore drop touches while a holder's gesture is in flight
 * ([gestureStarted] / [gestureEnded]); the user taps again once it ends.
 *
 * The home screen's AI agent panel (Send, Apply, the key field) counts as a guarded screen while
 * it is open ([agentPanelShown]): the home screen then drops touches during a holder's gesture too.
 */
object A11yGuard : Application.ActivityLifecycleCallbacks {
    private val started: MutableSet<Activity> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val homeStarted: MutableSet<Activity> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    @Volatile private var gestureUntil = 0L

    @Volatile private var agentPanelOpen = 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    /** True while a guarded launcher screen is started (visible or about to be). */
    fun guardedVisible(): Boolean = agentPanelGuards() || synchronized(started) { started.isNotEmpty() }

    private fun agentPanelGuards(): Boolean = agentPanelOpen > 0 && synchronized(homeStarted) { homeStarted.isNotEmpty() }

    /** The home screen's agent panel was composed ([shown] true) or left composition. Main thread. */
    fun agentPanelShown(shown: Boolean) {
        agentPanelOpen = (agentPanelOpen + if (shown) 1 else -1).coerceAtLeast(0)
    }

    /** A holder's gesture of [durationMs] begins now. */
    fun gestureStarted(durationMs: Long) {
        gestureUntil = SystemClock.uptimeMillis() + durationMs + GRACE_MS
    }

    fun gestureEnded() {
        gestureUntil = SystemClock.uptimeMillis() + GRACE_MS
    }

    private fun gestureInFlight(): Boolean = SystemClock.uptimeMillis() < gestureUntil

    private fun guarded(activity: Activity) = A11ySpec.guardedLauncherActivity(activity.javaClass.name)

    /** Activities whose touches are dropped during a gesture: guarded ones always, home while the agent panel is open. */
    private fun touchGuarded(activity: Activity) = guarded(activity) || activity.javaClass.name == HOME

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!touchGuarded(activity)) return
        val window = activity.window ?: return
        val current = window.callback ?: return
        if (current !is TouchGuard) window.callback = TouchGuard(current, always = guarded(activity))
    }

    override fun onActivityStarted(activity: Activity) {
        if (guarded(activity)) started += activity
        if (activity.javaClass.name == HOME) homeStarted += activity
    }

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        started -= activity
        homeStarted -= activity
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        started -= activity
        homeStarted -= activity
    }

    private class TouchGuard(private val inner: Window.Callback, private val always: Boolean) : Window.Callback by inner {
        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (gestureInFlight() && (always || agentPanelGuards())) return true
            return inner.dispatchTouchEvent(event)
        }
    }

    private const val GRACE_MS = 150L
    private const val HOME = "${A11ySpec.LAUNCHER_NAMESPACE}.ui.home.HomeActivity"
}
