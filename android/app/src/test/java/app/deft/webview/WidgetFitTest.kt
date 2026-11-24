package app.deft.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFitTest {
    @Test
    fun frameSizesFollowTheGrid() {
        // 411 dp phone, pad 12, 28 dp canvas inset: cell (411 - 56 - 36) / 4 = 79
        assertEquals(79 to 84, WidgetFit.framePx("1x1", 411, 12))
        assertEquals(170 to 180, WidgetFit.framePx("2x2", 411, 12))
        assertEquals(352 to 180, WidgetFit.framePx("4x2", 411, 12))
        assertEquals(79 to 84, WidgetFit.framePx("1x1"))
        assertNull(WidgetFit.framePx("5x1"))
        assertNull(WidgetFit.framePx("2"))
        assertNull(WidgetFit.framePx("2x0"))
    }

    @Test
    fun padComesFromTokensCss() {
        assertEquals(12, WidgetFit.padOf("  --deft-radius-outer: x;\n    --deft-pad: 12px;\n"))
        assertEquals(8, WidgetFit.padOf("--deft-pad:8px"))
        assertEquals(WidgetFit.DEFAULT_PAD, WidgetFit.padOf(null))
        assertEquals(WidgetFit.DEFAULT_PAD, WidgetFit.padOf("no pad here"))
    }

    @Test
    fun contentLargerThanTheFrameWarns() {
        val f = WidgetFit.findings("2x2", 176 to 120, """{"w":176,"h":120,"sw":176,"sh":300}""")
        assertEquals(1, f.size)
        assertEquals("WARN", f[0].level)
        assertTrue(f[0].text.contains("tall"))
        val wide = WidgetFit.findings("1x1", 84 to 56, """{"w":84,"h":56,"sw":120,"sh":56}""")
        assertTrue(wide.single().text.contains("wide"))
    }

    @Test
    fun fittingContentAndUnreadableProbesAreQuiet() {
        assertTrue(WidgetFit.findings("2x2", 176 to 120, """{"sw":177,"sh":121}""").isEmpty())
        assertTrue(WidgetFit.findings("2x2", 176 to 120, "not json").isEmpty())
    }
}
