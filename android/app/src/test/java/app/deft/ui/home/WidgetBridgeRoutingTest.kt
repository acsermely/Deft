package app.deft.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetBridgeRoutingTest {
    private val canvas = "https://home.app.deft.internal"
    private val weather = "https://weather.app.deft.internal"
    private val steps = "https://steps.app.deft.internal"
    private val placed = setOf(weather, steps)

    private fun decide(origin: String, main: Boolean = false, set: Set<String> = placed) =
        WidgetRouting.decide(origin, main, canvas, set)

    private fun reason(d: WidgetRouting.Decision) = (d as WidgetRouting.Decision.Reject).reason

    @Test
    fun subFrameOfAPlacedWidgetIsRouted() {
        assertEquals(WidgetRouting.Decision.Route(weather), decide(weather))
        assertEquals(WidgetRouting.Decision.Route(steps), decide(steps))
    }

    @Test
    fun originIsCanonicalisedBeforeTheLookup() {
        assertEquals(WidgetRouting.Decision.Route(weather), decide("HTTPS://Weather.App.Deft.Internal"))
        assertEquals(WidgetRouting.Decision.Route(weather), decide("https://weather.app.deft.internal:443"))
        assertEquals(WidgetRouting.Decision.Route(weather), decide("https://weather.app.deft.internal/widget.html"))
    }

    @Test
    fun mainFrameIsNeverRouted() {
        assertEquals(WidgetRouting.REASON_MAIN_FRAME, reason(decide(weather, main = true)))
    }

    @Test
    fun theCanvasItselfKeepsNoBridgeEvenInAFrame() {
        assertEquals(WidgetRouting.REASON_CANVAS, reason(decide(canvas)))
        assertEquals(WidgetRouting.REASON_CANVAS, reason(decide(canvas, set = placed + canvas)))
        assertEquals(WidgetRouting.REASON_MAIN_FRAME, reason(decide(canvas, main = true)))
    }

    @Test
    fun anAppThatIsNotPlacedIsRejected() {
        assertEquals(WidgetRouting.REASON_NOT_PLACED, reason(decide("https://other.app.deft.internal")))
        assertEquals(WidgetRouting.REASON_NOT_PLACED, reason(decide(weather, set = emptySet())))
    }

    @Test
    fun lookalikeOriginsAreRejected() {
        for (o in listOf(
            "http://weather.app.deft.internal",
            "https://weather.app.deft.internal:8443",
            "https://evil.weather.app.deft.internal",
            "https://weather.app.deft.internal.evil.com",
            "https://user@weather.app.deft.internal",
            "https://weather.app.deft.internal@evil.com",
        )) {
            assertTrue(o, decide(o) is WidgetRouting.Decision.Reject)
        }
    }

    @Test
    fun opaqueAndMalformedOriginsAreRejected() {
        for (o in listOf("null", "", "about:blank", "file:///x", "data:text/html,x", "https://", "not a url")) {
            assertEquals(o, WidgetRouting.REASON_OPAQUE, reason(decide(o)))
        }
    }

    @Test
    fun broadRuleCoversAppOriginsAndTheCanvasOnly() {
        assertEquals("https://*.app.deft.internal", WidgetRouting.BROAD_RULE)
        assertTrue(canvas.endsWith(".app.deft.internal")) // why the exact set is enforced per message
    }

    @Test
    fun openUrlAcceptsOnlySameOriginPaths() {
        assertEquals("", WidgetRouting.openUrl(weather, null))
        assertEquals("", WidgetRouting.openUrl(weather, "  "))
        assertEquals("$weather/", WidgetRouting.openUrl(weather, "/"))
        assertEquals("$weather/day.html?d=1#x", WidgetRouting.openUrl(weather, "/day.html?d=1#x"))
        for (p in listOf("day.html", "//evil.com/x", "https://evil.com/", "/a\\b", "/a\u0000b", "/a\nb", "/" + "x".repeat(1100), "@evil.com")) {
            assertNull(p, WidgetRouting.openUrl(weather, p))
        }
    }

    private val nonce = "n0nce"

    @Test
    fun attributionPrefersTheSourceOrigin() {
        val a = WidgetRouting.attribute("$weather/main.js", "boom", placed, nonce)
        assertEquals(WidgetRouting.Attribution(weather, "boom"), a)
        // A line from the canvas's own scripts is not a widget line.
        assertNull(WidgetRouting.attribute("$canvas/main.js", "boom", placed, nonce))
        assertNull(WidgetRouting.attribute("", "boom", placed, nonce))
    }

    @Test
    fun attributionFallsBackToTheFramePrefixAndStripsIt() {
        val a = WidgetRouting.attribute("", "[deft-widget $nonce $steps] [uncaught] x is not defined @/w.js:3", placed, nonce)
        assertEquals(WidgetRouting.Attribution(steps, "[uncaught] x is not defined @/w.js:3"), a)
        // The source wins over a prefix that names another widget.
        val b = WidgetRouting.attribute("$weather/w.js", "[deft-widget $nonce $steps] oops", placed, nonce)
        assertEquals(WidgetRouting.Attribution(weather, "oops"), b)
        // An unplaced origin in the prefix attributes to nobody.
        assertNull(WidgetRouting.attribute("", "[deft-widget $nonce https://other.app.deft.internal] oops", placed, nonce))
    }

    @Test
    fun theCanvasCannotForgeALineIntoAWidgetLog() {
        // No nonce, a wrong nonce, the old nonce-less form: all plain text of an unattributed source.
        assertNull(WidgetRouting.attribute("", "[deft-widget $weather] fake", placed, nonce))
        assertNull(WidgetRouting.attribute("", "[deft-widget wrong $weather] fake", placed, nonce))
        assertNull(WidgetRouting.attribute("$canvas/main.js", "[deft-widget $weather] fake", placed, nonce))
        assertNull(WidgetRouting.attribute("", "[deft-widget  $weather] fake", placed, ""))
    }

    @Test
    fun noncesAreUnique() {
        assertTrue(WidgetRouting.newNonce() != WidgetRouting.newNonce())
        assertTrue(WidgetRouting.newNonce().length >= 32)
    }

    @Test
    fun frameScriptRunsOnlyInFramesOfAnotherOrigin() {
        val js = WidgetRouting.frameScript("/*client*/", canvas, nonce)
        assertTrue(js.startsWith("if(self!==top&&location.origin!=='$canvas'&&location.origin!=='null'){"))
        assertTrue(js.contains("/*client*/"))
        assertTrue(js.contains("[deft-widget $nonce "))
        assertTrue(js.trimEnd().endsWith("}"))
    }

    @Test
    fun aFrameSaysByeOnPagehideAndOnlyByeIsTakenForIt() {
        val js = WidgetRouting.frameScript("/*client*/", canvas, nonce)
        assertTrue(js.contains("addEventListener('pagehide'"))
        assertTrue(js.contains("postMessage('${WidgetRouting.BYE}')"))
        assertTrue(js.indexOf("self.jsBridge") < js.indexOf("/*client*/")) // the port is taken before the page runs
        assertTrue(WidgetRouting.isBye(WidgetRouting.BYE))
        assertTrue(!WidgetRouting.isBye(null))
        assertTrue(!WidgetRouting.isBye("""{"type":"hello"}"""))
        assertTrue(!WidgetRouting.isBye("""{"type":"deft:bye","id":"1"}"""))
    }

    @Test
    fun openNeedsAFreshTouch() {
        assertTrue(!WidgetRouting.gestureFresh(10_000, 0))
        assertTrue(WidgetRouting.gestureFresh(10_000, 9_000))
        assertTrue(WidgetRouting.gestureFresh(10_000, 10_000 - WidgetRouting.GESTURE_WINDOW_MS))
        assertTrue(!WidgetRouting.gestureFresh(10_000, 10_000 - WidgetRouting.GESTURE_WINDOW_MS - 1))
        assertTrue(!WidgetRouting.gestureFresh(10_000, 11_000)) // a touch "from the future" is not one
    }

    @Test
    fun rateGateDropsAFlood() {
        val g = WidgetRouting.RateGate(limit = 3, windowMs = 1_000)
        assertTrue(g.allow(0)); assertTrue(g.allow(1)); assertTrue(g.allow(2))
        assertTrue(!g.allow(3))
        assertTrue(!g.allow(999))
        assertTrue(g.allow(1_000)) // next window
    }

    @Test
    fun trailingDotAndIdnOriginsAreNotPlaced() {
        for (o in listOf("https://weather.app.deft.internal.", "https://wéather.app.deft.internal", "HTTPS://WEATHER.APP.DEFT.INTERNAL.:443")) {
            assertTrue(o, decide(o) is WidgetRouting.Decision.Reject)
        }
    }

    @Test
    fun setupTapNeedsGestureFreshTouchAndAWaitingPlacedApp() {
        val waiting = setOf("myapp:steps")
        val url = "deft://setup/myapp%3Asteps"
        assertEquals("myapp:steps", WidgetRouting.setupTarget(url, true, true, waiting))
        assertNull(WidgetRouting.setupTarget("fl://setup/myapp%3Asteps", true, true, waiting)) // the old form is gone
        assertNull(WidgetRouting.setupTarget(url, false, true, waiting))
        assertNull(WidgetRouting.setupTarget(url, true, false, waiting))
        assertNull(WidgetRouting.setupTarget(url, true, true, emptySet()))
        assertNull(WidgetRouting.setupTarget("deft://setup/myapp%3Aother", true, true, waiting))
        assertNull(WidgetRouting.setupTarget("https://home.app.deft.internal/", true, true, waiting))
        assertNull(WidgetRouting.setupTarget("deft://setup/%zz", true, true, waiting))
    }
}
