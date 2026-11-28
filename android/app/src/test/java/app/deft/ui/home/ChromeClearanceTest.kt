package app.deft.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeClearanceTest {
    @Test
    fun convertsDevicePxToCssPxRoundingUp() {
        assertEquals(0, ChromeClearance.cssPx(0, 2.625f))
        assertEquals(0, ChromeClearance.cssPx(500, 0f))
        assertEquals(200, ChromeClearance.cssPx(525, 2.625f))
        assertEquals(201, ChromeClearance.cssPx(526, 2.625f))
        assertEquals(240, ChromeClearance.cssPx(240, 1f))
    }

    @Test
    fun scriptSetsTheVariableTheStylesheetUses() {
        val js = ChromeClearance.js(212)
        assertTrue(js.contains("setProperty('--chrome-clearance','212px')"))
        val css = HomeCanvasDefaults.files.getValue(HomeCanvasDefaults.STYLES)
        assertTrue(css.contains("--chrome-clearance: 240px;"))
        assertTrue(css.contains("28px var(--chrome-clearance);"))
    }
}
