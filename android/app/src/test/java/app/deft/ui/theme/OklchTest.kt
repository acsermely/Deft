package app.deft.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OklchTest {
    @Test
    fun hexRoundTripsThroughOklch() {
        for (hex in listOf("#1A1B26", "#7AA2F7", "#BD93F9", "#0F6F73", "#FFFFFF", "#000000", "#F472B6")) {
            val argb = (0xFF000000L or hex.removePrefix("#").toLong(16))
            val back = OklchMath.fromArgb(argb).toArgb()
            for (shift in listOf(16, 8, 0)) {
                val a = (argb shr shift) and 0xFF
                val b = (back shr shift) and 0xFF
                assertTrue("$hex channel $shift: $a vs $b", kotlin.math.abs(a - b) <= 1)
            }
        }
    }

    @Test
    fun whiteAndBlackAreAtTheExtremes() {
        assertEquals(1.0, OklchMath.fromArgb(0xFFFFFFFFL).l, 1e-6)
        assertEquals(0.0, OklchMath.fromArgb(0xFF000000L).l, 1e-6)
        assertEquals(21.0, OklchMath.contrast(0xFFFFFFFFL, 0xFF000000L), 1e-6)
    }

    @Test
    fun parsesOklchAndHexAndRejectsGarbage() {
        val red = OklchMath.parse("oklch(0.58 0.21 27)")!!
        assertEquals(0.58, red.l, 1e-9)
        assertEquals(27.0, red.h, 1e-9)
        assertEquals(0.5, OklchMath.parse("oklch(50% 0.1 10 / 0.5)")!!.alpha, 1e-9)
        assertNull(OklchMath.parse("oklch(2 0.1 10)"))
        assertNull(OklchMath.parse("tomato"))
        assertNull(OklchMath.parse("#12345"))
    }

    @Test
    fun cssOmitsAlphaWhenOpaque() {
        assertEquals("oklch(0.58 0.21 27)", Oklch(0.58, 0.21, 27.0).css())
        assertEquals("oklch(1 0 0 / 0.62)", Oklch(1.0, 0.0, 0.0, 0.62).css())
    }

    @Test
    fun compositeBlendsAlphaOverTheBackground() {
        val half = 0x80FFFFFFL
        val out = OklchMath.composite(half, 0xFF000000L)
        val r = (out shr 16) and 0xFF
        assertTrue(r in 127..129)
    }
}
