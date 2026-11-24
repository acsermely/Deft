package app.deft.bridge

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.concurrent.ConcurrentHashMap

/**
 * Real taps on an app's WebView, seen natively by the host (page code cannot fake them).
 * A confirm-each action (SMS send, call) right after a tap needs no dialog; each tap covers
 * one action, so a tap on "Practice" cannot be spent on a burst of messages.
 *
 * Only a genuine tap counts: one finger, released within [ViewConfiguration.getLongPressTimeout]
 * and without moving past the touch slop. The end of a scroll, a fling, a long-press or a
 * pinch is not a tap.
 */
object UserGesture {
    const val WINDOW_MS = 5_000L

    private data class Down(val time: Long, val x: Float, val y: Float, var valid: Boolean = true)

    private val lastTap = ConcurrentHashMap<String, Long>()
    private val downs = ConcurrentHashMap<String, Down>()

    /** Feed every touch event of the app's WebView. Returns false so the WebView still gets it. */
    fun track(view: View, event: MotionEvent, webAppId: String): Boolean {
        val config = ViewConfiguration.get(view.context)
        onTouch(
            webAppId = webAppId,
            action = event.actionMasked,
            x = event.rawX,
            y = event.rawY,
            time = event.eventTime,
            slopPx = config.scaledTouchSlop,
            maxPressMs = ViewConfiguration.getLongPressTimeout().toLong(),
        )
        return false
    }

    /** Pure state machine behind [track]; `action` is a [MotionEvent] masked action. */
    fun onTouch(
        webAppId: String,
        action: Int,
        x: Float,
        y: Float,
        time: Long,
        slopPx: Int,
        maxPressMs: Long,
    ) {
        when (action) {
            MotionEvent.ACTION_DOWN -> downs[webAppId] = Down(time, x, y)
            MotionEvent.ACTION_MOVE -> downs[webAppId]?.let { d ->
                if (moved(d, x, y, slopPx)) d.valid = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> downs[webAppId]?.valid = false
            MotionEvent.ACTION_UP -> {
                val d = downs.remove(webAppId) ?: return
                if (d.valid && !moved(d, x, y, slopPx) && time - d.time in 0..maxPressMs) {
                    tapped(webAppId, time)
                }
            }
        }
    }

    private fun moved(d: Down, x: Float, y: Float, slopPx: Int): Boolean {
        val dx = x - d.x
        val dy = y - d.y
        return dx * dx + dy * dy > slopPx.toFloat() * slopPx
    }

    fun tapped(webAppId: String, now: Long = SystemClock.uptimeMillis()) {
        lastTap[webAppId] = now
        lastSeen[webAppId] = now
        askAt[webAppId] = now
        tapStarted[webAppId] = now
    }

    private val lastSeen = ConcurrentHashMap<String, Long>()

    /**
     * The window the permission and sheet asks read: the last genuine tap, or the last allowed ask. Kept apart from
     * [lastSeen] so an allow never extends [recent] (paste, speech, auth, confirm skip), which real taps alone set.
     */
    private val askAt = ConcurrentHashMap<String, Long>()

    /** When the app's current genuine tap happened. Only a real tap moves it, never a refresh after an allow. */
    private val tapStarted = ConcurrentHashMap<String, Long>()

    /** True within [WINDOW_MS] of a genuine tap, without spending it (for reads such as paste). */
    fun recent(webAppId: String, now: Long = SystemClock.uptimeMillis()): Boolean {
        val at = lastSeen[webAppId] ?: return false
        return now - at in 0..WINDOW_MS
    }

    /** True once per tap, and only within [WINDOW_MS] of it. */
    fun consume(webAppId: String, now: Long = SystemClock.uptimeMillis()): Boolean {
        val at = lastTap.remove(webAppId) ?: return false
        return now - at in 0..WINDOW_MS
    }

    /**
     * The kinds of ask one tap can cover: Android's runtime dialog, the launcher's special sheet, and the
     * consent screen opened for one plain launcher grant (a non-special capability).
     */
    enum class AskKind { SYSTEM, SPECIAL, CONSENT }

    private val askSpent = ConcurrentHashMap<String, Long>()

    /**
     * True once per tap and kind, and only within [WINDOW_MS] of the last gesture. Separate from [consume], which a
     * confirm sheet may already have used for the same tap (BridgeService), and separate per kind, so Android's
     * dialog does not use up the sheet's turn (or the other way round). One tap can cover one ask of each kind.
     */
    fun spendForAsk(webAppId: String, kind: AskKind, now: Long = SystemClock.uptimeMillis()): Boolean {
        val seen = askAt[webAppId] ?: return false
        if ((now - seen) !in 0..WINDOW_MS) return false
        val tap = tapStarted[webAppId] ?: return false
        val key = "$webAppId|${kind.name}"
        val spent = askSpent[key]
        if (spent != null && spent >= tap) return false
        askSpent[key] = tap
        return true
    }

    /**
     * The user just allowed an ask, so the Allow tap counts as a new gesture: a follow-up ask in the same chain
     * is not cut off by the window that the system dialog's own time ran out. Not called on deny, cancel or timeout.
     */
    fun refreshAfterAllow(webAppId: String, now: Long = SystemClock.uptimeMillis()) {
        askAt[webAppId] = now
    }

    /** Debug reset: forgets the app's ask windows and spent asks. Genuine-tap state is left alone. */
    fun forgetAsks(webAppId: String) {
        askAt.remove(webAppId)
        tapStarted.remove(webAppId)
        val prefix = "$webAppId|"
        askSpent.keys.filter { it.startsWith(prefix) }.forEach { askSpent.remove(it) }
    }
}
