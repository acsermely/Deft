package app.deft.ui.theme

import app.deft.config.Palette
import app.deft.config.ThemeSettings
import app.deft.webview.DeftRuntimeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCssTest {
    private val resolved = Palette.resolve(ThemeSettings(DeftTokens.DEFAULT_ACCENT, "dark"), null, true)

    @Test
    fun chromeInjectionDoesNotOverwriteThemeSheet() {
        Palette.publish(resolved)
        val published = DeftRuntimeTheme.css()
        themeCssJs(Palette.colors(resolved))
        assertEquals(published, DeftRuntimeTheme.css())
    }

    @Test
    fun publishedSheetIsTheAccentAndAppearanceCss() {
        Palette.publish(resolved)
        assertEquals(Palette.css(resolved), DeftRuntimeTheme.css())
        assertTrue(DeftRuntimeTheme.css().contains("--deft-radius-box: var(--deft-radius-lg);"))
    }
}
