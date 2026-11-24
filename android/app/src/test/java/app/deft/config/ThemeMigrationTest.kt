package app.deft.config

import app.deft.ui.theme.DeftTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeMigrationTest {
    private fun migrate(name: String?, palette: String? = null, mode: String? = null, accent: String? = null) =
        ThemeMigration.migrate(name, palette, mode, accent)

    @Test
    fun terminalThemesMapToTheirNearestAccent() {
        assertEquals("#bd93f9", migrate("dracula", mode = "dark").settings.accent)
        assertEquals("#88c0d0", migrate("nord", mode = "dark").settings.accent)
        assertEquals("#f472b6", migrate("synthwave", mode = "dark").settings.accent)
        assertEquals("#7aa2f7", migrate("tokyo-night", mode = "dark").settings.accent)
        assertEquals("#0f6f73", migrate("cupcake", mode = "light").settings.accent)
    }

    @Test
    fun lightMapsToTheDefaultRed() {
        val r = migrate("light", mode = "light")
        assertEquals(DeftTokens.DEFAULT_ACCENT, r.settings.accent)
        assertEquals("light", r.settings.appearance)
    }

    @Test
    fun appearanceComesFromTheOldMode() {
        assertEquals("dark", migrate("tokyo-night", mode = "dark").settings.appearance)
        assertEquals("light", migrate("cupcake", mode = null).settings.appearance)
        assertEquals("dark", migrate("dracula", mode = null).settings.appearance)
    }

    @Test
    fun systemBecomesWallpaperAccent() {
        val r = migrate("system", mode = "auto")
        assertEquals(ThemeSettings.WALLPAPER, r.settings.accent)
        assertEquals("auto", r.settings.appearance)
    }

    @Test
    fun userPalettePrimaryBecomesTheAccentWithOneWarning() {
        val palette = """{"modes":["dark"],"dark":{"base-100":"#111111","primary":"#ff8800"}}"""
        val r = migrate("tokyo-night", palette = palette, mode = "dark")
        assertEquals("#ff8800", r.settings.accent)
        assertEquals(1, r.warnings.size)
    }

    @Test
    fun bundledPrimaryInPaletteJsonIsNotAnOverride() {
        val palette = """{"modes":["dark"],"dark":{"primary":"#7aa2f7"}}"""
        val r = migrate("tokyo-night", palette = palette, mode = "dark")
        assertEquals("#7aa2f7", r.settings.accent)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun unknownThemeWithNothingElseGetsTheDefaults() {
        val r = migrate(null)
        assertEquals(DeftTokens.DEFAULT_ACCENT, r.settings.accent)
        assertEquals("auto", r.settings.appearance)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun presetNamesAndAppearancesNormalise() {
        assertEquals("#bd93f9", ThemeSettings.normalizeAccent("Violet"))
        assertNull(ThemeSettings.normalizeAccent("nope"))
        assertEquals("wallpaper", ThemeSettings.normalizeAccent("Wallpaper"))
        assertEquals("dark", ThemeSettings.normalizeAppearance("BLACK"))
        assertEquals("auto", ThemeSettings.normalizeAppearance("sepia"))
    }
}
