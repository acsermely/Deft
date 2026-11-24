package app.deft.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeftLintFormatTest {
    @Test
    fun formatsAndCaps() {
        val raw = """[{"id":"FL100","severity":"error","path":"main.js","line":12,"col":5,"message":"innerHTML assigns raw HTML"}]"""
        val diags = DeftLintFormat.parse(raw)
        assertEquals(1, diags.size)
        assertEquals(
            "main.js:12:5 FL100 error innerHTML assigns raw HTML",
            DeftLintFormat.lines(diags),
        )
        val many = List(14) { i ->
            DeftLintFormat.Diag("FL100", "error", "a.js", i + 1, 1, "x")
        }
        val text = DeftLintFormat.lines(many)
        assertTrue(text.endsWith("2 more (run apps.verify for all)"))
    }
}
