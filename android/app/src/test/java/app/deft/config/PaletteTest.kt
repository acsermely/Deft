package app.deft.config

import app.deft.ui.theme.DeftTokens
import app.deft.ui.theme.Oklch
import app.deft.ui.theme.OklchMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    private fun resolve(accent: String, appearance: String = "auto", wallpaper: String? = null, systemDark: Boolean = true) =
        Palette.resolve(ThemeSettings(accent, appearance), wallpaper, systemDark)

    @Test
    fun defaultAccentIsSignalRedAndMeetsOnAccentContrast() {
        val r = resolve(DeftTokens.DEFAULT_ACCENT)
        assertEquals(DeftTokens.defaultAccent.l, r.accent.l, 1e-9)
        assertTrue(OklchMath.contrast(r.onAccent.toArgb(), r.accent.toArgb()) >= Palette.MIN_ON_ACCENT_CONTRAST)
    }

    @Test
    fun everyHueAndLightnessKeepsOnAccentAboveFourPointFive() {
        for (l in listOf(0.2, 0.45, 0.6, 0.75, 0.9)) {
            for (h in 0 until 360 step 30) {
                val (accent, on) = Palette.fitAccent(Oklch(l, 0.2, h.toDouble()))
                assertTrue("l=$l h=$h", OklchMath.contrast(on.toArgb(), accent.toArgb()) >= 4.5)
            }
        }
    }

    @Test
    fun errorHueMovesAwayWhenAccentIsClose() {
        val error = DeftTokens.appearanceDark.error
        val shifted = Palette.shiftedError(error, 27.0)
        assertEquals(57.0, shifted.h, 1e-9)
        assertTrue(OklchMath.hueDistance(shifted.h, 27.0) >= 30.0)
    }

    @Test
    fun errorHueStaysWhenAccentIsFar() {
        val error = DeftTokens.appearanceDark.error
        assertEquals(error.h, Palette.shiftedError(error, 260.0).h, 1e-9)
    }

    @Test
    fun errorHueShiftsOnTheSideItAlreadyIsOn() {
        val shifted = Palette.shiftedError(Oklch(0.7, 0.19, 50.0), 60.0)
        assertEquals(30.0, shifted.h, 1e-9)
    }

    @Test
    fun wallpaperWithoutAnExtractedAccentFallsBackToTheDefault() {
        val r = resolve(ThemeSettings.WALLPAPER, wallpaper = null)
        assertEquals(DeftTokens.defaultAccent.h, r.accent.h, 1e-9)
        assertEquals(DeftTokens.defaultAccent.l, r.accent.l, 1e-9)
    }

    @Test
    fun wallpaperAccentIsUsedWhenExtracted() {
        val r = resolve(ThemeSettings.WALLPAPER, wallpaper = "#3366CC")
        val expected = OklchMath.fromHex("#3366CC")!!
        assertEquals(expected.h, r.accent.h, 1e-6)
        assertTrue(OklchMath.contrast(r.onAccent.toArgb(), r.accent.toArgb()) >= 4.5)
    }

    @Test
    fun unparseableAccentFallsBackToTheDefault() {
        assertEquals(DeftTokens.defaultAccent.h, resolve("not a colour").accent.h, 1e-9)
    }

    @Test
    fun autoFollowsTheSystemAndExplicitAppearanceDoesNot() {
        assertEquals("dark", resolve(DeftTokens.DEFAULT_ACCENT, "auto", systemDark = true).effective)
        assertEquals("light", resolve(DeftTokens.DEFAULT_ACCENT, "auto", systemDark = false).effective)
        assertEquals("light", resolve(DeftTokens.DEFAULT_ACCENT, "light", systemDark = true).effective)
    }

    @Test
    fun cssPublishesAccentAppearanceAndShim() {
        val css = Palette.css(resolve(DeftTokens.DEFAULT_ACCENT, "dark"))
        assertTrue(css.contains("--deft-accent: oklch(0.657 0.179 40.4);"))
        assertTrue(css.contains("--deft-appearance: dark;"))
        assertTrue(css.contains("color-scheme: dark;"))
        assertFalse(css.contains("light-dark("))
        assertTrue(css.contains("--deft-color-primary: var(--deft-accent);"))
        assertTrue(css.contains("--deft-muted: var(--deft-label-2);"))
        assertEquals(1, Regex("--deft-muted:").findAll(css).count())
    }

    @Test
    fun autoUsesLightDarkPairs() {
        val css = Palette.css(resolve(DeftTokens.DEFAULT_ACCENT, "auto"))
        assertTrue(css.contains("color-scheme: light dark;"))
        assertTrue(css.contains("--deft-bg: light-dark("))
        assertTrue(css.contains("--deft-error: light-dark(oklch("))
    }

    @Test
    fun colorTokensFollowTheEffectiveAppearance() {
        val colors = Palette.colors(resolve(DeftTokens.DEFAULT_ACCENT, "light"))
        assertEquals("light", colors.mode)
    }
}
