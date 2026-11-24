package app.deft.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderProbeTest {
    @Test
    fun warnsOnWideOverflow() {
        val raw = """{"hOverflow":true,"clientWidth":360,"bodyBgUsesVar":true,"themeChangesBg":true}"""
        assertEquals(
            listOf("horizontal overflow at 360px (scrollWidth > clientWidth)"),
            RenderProbe.warningTexts(raw),
        )
    }

    @Test
    fun ignoresOverflowOnTinyViewport() {
        val raw = """{"hOverflow":true,"clientWidth":1,"bodyBgUsesVar":true,"themeChangesBg":false}"""
        assertTrue(RenderProbe.warningTexts(raw).isEmpty())
    }

    @Test
    fun warnsWhenBackgroundIsNotThemed() {
        val raw = """{"hOverflow":false,"clientWidth":360,"bodyBgUsesVar":false,"themeChangesBg":false}"""
        assertEquals(
            listOf("body background does not follow the theme"),
            RenderProbe.warningTexts(raw),
        )
    }

    @Test
    fun tokenBackgroundIsEnough() {
        val raw = """{"bodyBgUsesVar":true,"themeChangesBg":false}"""
        assertTrue(RenderProbe.warningTexts(raw).isEmpty())
    }

    @Test
    fun computedThemeChangeIsEnough() {
        val raw = """{"bodyBgUsesVar":false,"themeChangesBg":true}"""
        assertTrue(RenderProbe.warningTexts(raw).isEmpty())
    }

    @Test
    fun skipsErrorsAndOldProbes() {
        assertTrue(RenderProbe.warningTexts("""{"error":"boom"}""").isEmpty())
        assertTrue(RenderProbe.warningTexts("").isEmpty())
        assertTrue(RenderProbe.warningTexts("""{"viewportFitCover":true,"safeArea":true}""").isEmpty())
    }
}
