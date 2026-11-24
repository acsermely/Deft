package app.deft.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewRecordingTest {
    @Test
    fun summarisesCallsSubscriptionsEventsAndConsole() {
        val r = PreviewRecording()
        r.call("sensors.list"); r.result("sensors.list", true, null)
        r.call("clipboard.readText"); r.result("clipboard.readText", false, "not_granted")
        r.call("clipboard.readText"); r.result("clipboard.readText", false, "not_granted")
        r.call("sensors.subscribe")
        r.subscribe("sensors.reading")
        for (i in 0..19) r.event("sensors.reading", """{"id":"s1","values":[${i * 0.1},-1.5,${i % 2}]}""")
        r.event("clipboard.changed", "{}")
        r.console("ERROR", "[uncaught] boom @main.js:3")
        r.console("WARN", "slow")
        r.console("INFO", "ignored")
        assertEquals(
            """
            bridge calls: sensors.list ok, clipboard.readText error not_granted ×2, sensors.subscribe pending
            subscriptions: sensors.reading
            events: sensors.reading ×20 values [0] 0..1.9 [1] {-1.5} [2] {0,1}; clipboard.changed ×1
            console: 1 errors, 1 warnings
            ERROR [uncaught] boom @main.js:3
            WARN slow
            """.trimIndent(),
            r.format(),
        )
    }

    @Test
    fun emptyAndCapped() {
        assertEquals(
            "bridge calls: none\nsubscriptions: none\nevents: none\nconsole: no errors or warnings",
            PreviewRecording().format(),
        )
        val r = PreviewRecording()
        repeat(200) { r.console("ERROR", "e".repeat(300)) }
        val text = r.format()
        assertTrue(text.length <= PreviewRecording.MAX_TEXT)
        assertTrue(text, text.contains("+188 more"))
    }

    @Test
    fun numericValuesAndNumbers() {
        assertNull(PreviewRecording.numericValues("""{"x":1}"""))
        assertEquals(listOf(1.0, null, 2.5), PreviewRecording.numericValues("""{"values":[1,"a",2.5]}"""))
        assertEquals("0.123", PreviewRecording.num(0.12345))
        assertEquals("-2", PreviewRecording.num(-2.0))
        assertEquals("0", PreviewRecording.num(-0.0001))
    }
}
