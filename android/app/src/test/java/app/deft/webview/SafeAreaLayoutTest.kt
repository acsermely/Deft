package app.deft.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeAreaLayoutTest {
    @Test
    fun okWhenBothPresent() {
        val raw = """{"title":"x","viewportFitCover":true,"safeArea":true}"""
        assertTrue(SafeAreaLayout.warningTexts(raw).isEmpty())
    }

    @Test
    fun warnsWhenNotchCssMissing() {
        val raw = """{"viewportFitCover":true,"safeArea":false}"""
        assertEquals(
            listOf("missing env(safe-area-inset-*) (notch/home indicator)"),
            SafeAreaLayout.warningTexts(raw),
        )
    }

    @Test
    fun warnsWhenViewportFitMissing() {
        val raw = """{"viewportFitCover":false,"safeArea":true}"""
        assertEquals(
            listOf("missing viewport-fit=cover (notch/cutout)"),
            SafeAreaLayout.warningTexts(raw),
        )
    }

    @Test
    fun skipsProbeErrors() {
        assertTrue(SafeAreaLayout.warningTexts("""{"error":"boom"}""").isEmpty())
        assertTrue(SafeAreaLayout.warningTexts("").isEmpty())
    }
}
