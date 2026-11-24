package app.deft.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlTextTest {
    @Test
    fun stripsScriptsStylesHeadAndComments() {
        val html = "<html><head><title>Hi &amp; bye</title><style>p{}</style></head>" +
            "<body><SCRIPT>alert(1)</SCRIPT>ok<!-- hidden --></body></html>"
        assertEquals("Hi & bye\n\nok", HtmlText.toText(html))
    }

    @Test
    fun blockTagsBecomeLinesAndEntitiesDecode() {
        val html = "<p>One &lt;two&gt;</p><div>Three&nbsp;&#39;x&#39; &#x41;</div>"
        assertEquals("One <two>\n\nThree 'x' A", HtmlText.toText(html))
    }

    @Test
    fun keepsHttpLinkTargetsOnly() {
        val html = "<a href=\"https://example.com/a?x=1\">Docs</a> and <a href=\"/relative\">rel</a> " +
            "and <a href=\"javascript:alert(1)\">js</a>"
        assertEquals("Docs [https://example.com/a?x=1] and rel and js", HtmlText.toText(html))
    }

    @Test
    fun linkTargetsAreCapped() {
        val html = (1..45).joinToString(" ") { "<a href=\"https://example.com/$it\">l$it</a>" }
        assertEquals(40, Regex("\\[https://").findAll(HtmlText.toText(html)).count())
    }

    @Test
    fun capCutsOnWholeUtf8Sequences() {
        assertEquals("éé", HtmlText.cap("é".repeat(10), 5))
        assertEquals("abc", HtmlText.cap("abc", 10))
    }

    @Test(timeout = 5_000)
    fun hostileUnclosedMarkupStaysFast() {
        for (unit in listOf("<a href=\"x\">", "<script>", "<", "<title>", "<!--", "<p ")) {
            HtmlText.toText(unit.repeat(150_000))
        }
    }

    @Test
    fun unclosedScriptDropsTheRest() {
        assertEquals("Hi", HtmlText.toText("<p>Hi</p><script>alert(1)"))
    }
}
