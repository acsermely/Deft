package app.deft.wallpaper

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.roundToInt

/** Pure rules for the live wallpaper. No Android types; unit tested. */
object WallpaperSpec {
    const val TOUCH_EVENT = "wallpaper.touch"
    const val OFFSETS_EVENT = "wallpaper.offsets"

    /** Shortest gap between two `wallpaper.touch` moves, and between two `wallpaper.offsets` (30 a second). */
    const val MIN_EVENT_GAP_MS = 33L

    /** Frame rate while visible, and with battery saver on. */
    const val FPS = 30
    const val FPS_SAVER = 15

    /** The holder's use is written to the audit log at most this often (keeps the special grant from looking unused). */
    const val USE_LOG_GAP_MS = 24L * 60 * 60 * 1000

    /** MotionEvent action codes (android.view.MotionEvent), so this file needs no Android types. */
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_CANCEL = 3

    /** `action` of `wallpaper.touch` for a MotionEvent masked action; null for the ones not sent (more fingers, hover). */
    fun touchAction(masked: Int): String? = when (masked) {
        ACTION_DOWN -> "down"
        ACTION_MOVE -> "move"
        ACTION_UP -> "up"
        ACTION_CANCEL -> "cancel"
        else -> null
    }

    /**
     * Whether a touch with [action] goes to the page. Down, up, cancel and tap always do; a move
     * only [MIN_EVENT_GAP_MS] after the last move sent ([lastMoveMs], null: none yet). A clock that
     * went backwards lets it through.
     */
    fun touchPasses(action: String, lastMoveMs: Long?, nowMs: Long): Boolean {
        if (action != "move") return true
        return lastMoveMs == null || nowMs < lastMoveMs || nowMs - lastMoveMs >= MIN_EVENT_GAP_MS
    }

    /**
     * How long to wait before sending `wallpaper.offsets`, [lastSentMs] being the last one sent
     * (null: none yet). 0 means now. The caller keeps only the newest offsets while it waits, so
     * the page always ends up with the last ones.
     */
    fun offsetsDelay(lastSentMs: Long?, nowMs: Long): Long {
        if (lastSentMs == null || nowMs < lastSentMs) return 0
        val since = nowMs - lastSentMs
        return if (since >= MIN_EVENT_GAP_MS) 0 else MIN_EVENT_GAP_MS - since
    }

    /**
     * Frames per second the page may draw: 0 while hidden (nothing is drawn and the page is
     * paused), [FPS_SAVER] with battery saver on, else [FPS].
     */
    fun targetFps(visible: Boolean, powerSave: Boolean): Int = when {
        !visible -> 0
        powerSave -> FPS_SAVER
        else -> FPS
    }

    /** Milliseconds between two animation frames at [fps]; 0 for 0 (no frames). */
    fun frameGapMs(fps: Int): Int = if (fps <= 0) 0 else (1000.0 / fps).roundToInt()

    /** Whether to write the daily use row now; [lastMs] null: not this process yet. */
    fun shouldLogUse(lastMs: Long?, nowMs: Long): Boolean =
        lastMs == null || nowMs < lastMs || nowMs - lastMs >= USE_LOG_GAP_MS

    /** `{x, y, action}`: CSS pixels of the page ([px] / [density]), one decimal. */
    fun touchPayload(xPx: Float, yPx: Float, density: Float, action: String): String {
        val d = if (density > 0f) density else 1f
        return buildJsonObject {
            put("x", JsonPrimitive(round1(xPx / d)))
            put("y", JsonPrimitive(round1(yPx / d)))
            put("action", JsonPrimitive(action))
        }.toString()
    }

    /** `{xOffset, yOffset}`, each 0 to 1 as Android gives them (0.5 when the home screen has one page). */
    fun offsetsPayload(xOffset: Float, yOffset: Float): String = buildJsonObject {
        put("xOffset", JsonPrimitive(clamp01(xOffset)))
        put("yOffset", JsonPrimitive(clamp01(yOffset)))
    }.toString()

    /**
     * Script run at document start in the wallpaper page: `requestAnimationFrame` callbacks run
     * at most once every [gapMs] (all callbacks of one frame still run together), so a page that
     * animates every frame draws at the target rate. The launcher changes the gap later through
     * `window.__deftWallpaperFrameGap(ms)`.
     */
    fun frameCapJs(gapMs: Int): String =
        "(function(){if(window.__deftWallpaperFrameGap)return;" +
            "var raf=window.requestAnimationFrame.bind(window),caf=window.cancelAnimationFrame.bind(window);" +
            "var gap=$gapMs,due=0,cur=-1,seq=0,live=new Map();" +
            "Object.defineProperty(window,'__deftWallpaperFrameGap',{value:function(ms){gap=+ms||0;}});" +
            "window.requestAnimationFrame=function(cb){var id=++seq;" +
            "var step=function(t){if(!live.has(id))return;" +
            "if(t!==cur&&t<due){live.set(id,raf(step));return;}" +
            "if(t!==cur){cur=t;due=t+gap-2;}live.delete(id);cb(t);};" +
            "live.set(id,raf(step));return id;};" +
            "window.cancelAnimationFrame=function(id){var r=live.get(id);if(r!==undefined){caf(r);live.delete(id);}};" +
            "})();"

    private fun round1(v: Float): Double = (v * 10.0).roundToInt() / 10.0

    private fun clamp01(v: Float): Double = if (v.isNaN()) 0.0 else (v.coerceIn(0f, 1f) * 10_000f).roundToInt() / 10_000.0
}
