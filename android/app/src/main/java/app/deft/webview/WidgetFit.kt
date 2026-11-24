package app.deft.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * apps.verify: how a widget page fits its declared grid size. The frame size is an estimate of the home canvas
 * on this device: the screen width in dp minus the default canvas side inset, 4 columns, gaps of `--deft-pad`
 * (read from tokens.css), rows of 7 pads (components.css `.deft-widgets`). Good enough to catch clipping and
 * overflow, not a pixel-exact layout.
 */
object WidgetFit {
    const val PHONE_WIDTH = 411
    const val DEFAULT_PAD = 12
    /** Side padding of the default canvas `#stage` (HomeCanvasDefaults). */
    const val CANVAS_INSET = 28
    const val COLUMNS = 4

    private val padRule = Regex("--deft-pad:\\s*(\\d+)px")

    /** The root `--deft-pad` of tokens.css text (the first declaration), or [DEFAULT_PAD]. */
    fun padOf(tokensCss: String?): Int = tokensCss?.let { padRule.find(it)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it > 0 } ?: DEFAULT_PAD

    /** Probe run in the widget page: the viewport and the content size of html and body. */
    const val PROBE_JS = """
        (function(){
          var e = document.scrollingElement || document.documentElement, b = document.body;
          return JSON.stringify({
            w: window.innerWidth, h: window.innerHeight,
            sw: Math.max(e.scrollWidth, b ? b.scrollWidth : 0),
            sh: Math.max(e.scrollHeight, b ? b.scrollHeight : 0)
          });
        })()
    """

    /** Frame width and height in CSS px for a grid size like "2x2", or null when malformed. */
    fun framePx(size: String, widthDp: Int = PHONE_WIDTH, pad: Int = DEFAULT_PAD): Pair<Int, Int>? {
        val parts = size.split('x')
        if (parts.size != 2) return null
        val cols = parts[0].toIntOrNull()?.takeIf { it in 1..COLUMNS } ?: return null
        val rows = parts[1].toIntOrNull()?.takeIf { it in 1..4 } ?: return null
        val cell = (widthDp - 2 * CANVAS_INSET - (COLUMNS - 1) * pad) / COLUMNS
        return (cols * cell + (cols - 1) * pad) to (rows * pad * 7 + (rows - 1) * pad)
    }

    /** WARN findings for content larger than the frame (clipped by the canvas). Empty when it fits or the probe is unreadable. */
    fun findings(size: String, frame: Pair<Int, Int>, probeRaw: String): List<WebViewFinding> {
        val obj = runCatching { Json.parseToJsonElement(probeRaw) as JsonObject }.getOrNull() ?: return emptyList()
        val sw = (obj["sw"] as? JsonPrimitive)?.intOrNull ?: 0
        val sh = (obj["sh"] as? JsonPrimitive)?.intOrNull ?: 0
        val out = ArrayList<WebViewFinding>()
        if (sw > frame.first + 1) {
            out.add(WebViewFindings.finding("WARN", "widget", "size $size: content is ${sw}px wide in a ${frame.first}px frame; the rest is clipped"))
        }
        if (sh > frame.second + 1) {
            out.add(WebViewFindings.finding("WARN", "widget", "size $size: content is ${sh}px tall in a ${frame.second}px frame; the rest is clipped"))
        }
        return out
    }
}
