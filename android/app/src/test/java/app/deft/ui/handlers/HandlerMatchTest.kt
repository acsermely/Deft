package app.deft.ui.handlers

import app.deft.registry.DeftHandles
import app.deft.registry.DeftManifestParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HandlerMatchTest {
    @Test
    fun mimeMatchingUsesWildcardsOnEitherSide() {
        assertTrue(HandlerMatch.mimeMatches("image/*", "image/png"))
        assertTrue(HandlerMatch.mimeMatches("image/png", "image/png"))
        assertTrue(HandlerMatch.mimeMatches("image/png", "image/*"))
        assertTrue(HandlerMatch.mimeMatches("text/plain", "Text/Plain; charset=utf-8"))
        assertTrue(HandlerMatch.mimeMatches("text/vcard", "text/x-vcard"))
        assertFalse(HandlerMatch.mimeMatches("image/png", "image/jpeg"))
        assertFalse(HandlerMatch.mimeMatches("image/*", "video/mp4"))
        assertFalse(HandlerMatch.mimeMatches("image/*", "*/*"))
        assertFalse(HandlerMatch.mimeMatches("image/*", ""))
        assertFalse(HandlerMatch.mimeMatches("image/*", "garbage"))
        assertNull(HandlerMatch.normalizeMime(null))
    }

    @Test
    fun schemesMatchExactly() {
        val h = DeftHandles(schemes = listOf("web+deft", "mailto"))
        assertTrue(HandlerMatch.handlesScheme(h, "web+deft"))
        assertTrue(HandlerMatch.handlesScheme(h, "MAILTO"))
        assertFalse(HandlerMatch.handlesScheme(h, "web+foo"))
        assertFalse(HandlerMatch.handlesScheme(DeftHandles(), "mailto"))
    }

    @Test
    fun aliasesFollowWhatAppsHandle() {
        assertEquals(emptySet<HandlerAlias>(), HandlerMatch.enabledAliases(emptyList()))
        assertEquals(
            setOf(HandlerAlias.VIEW_IMAGE),
            HandlerMatch.enabledAliases(listOf(DeftHandles(mimeTypes = listOf("image/png")))),
        )
        assertEquals(
            setOf(HandlerAlias.VIEW_PDF, HandlerAlias.SENDTO_MAILTO, HandlerAlias.VIEW_WEB_DEFT),
            HandlerMatch.enabledAliases(
                listOf(
                    DeftHandles(mimeTypes = listOf("application/pdf")),
                    DeftHandles(schemes = listOf("mailto", "web+deft", "web+other")),
                    DeftHandles(schemes = listOf("web+fl")), // the old scheme has no alias any more
                ),
            ),
        )
        // Links never enable anything: no https alias exists.
        assertEquals(emptySet<HandlerAlias>(), HandlerMatch.enabledAliases(listOf(DeftHandles(links = listOf("https://a.example")))))
    }

    @Test
    fun calendarAndVcardStepAsideForText() {
        assertEquals(
            setOf(HandlerAlias.VIEW_CALENDAR),
            HandlerMatch.enabledAliases(listOf(DeftHandles(mimeTypes = listOf("text/calendar")))),
        )
        // text/* covers text/calendar; the calendar alias would list the launcher twice.
        assertEquals(
            setOf(HandlerAlias.VIEW_TEXT),
            HandlerMatch.enabledAliases(
                listOf(DeftHandles(mimeTypes = listOf("text/calendar")), DeftHandles(mimeTypes = listOf("text/plain"))),
            ),
        )
        assertEquals(
            setOf(HandlerAlias.VIEW_TEXT),
            HandlerMatch.enabledAliases(listOf(DeftHandles(mimeTypes = listOf("text/*")))),
        )
        assertEquals(
            setOf(HandlerAlias.VIEW_CALENDAR, HandlerAlias.VIEW_VCARD),
            HandlerMatch.enabledAliases(listOf(DeftHandles(mimeTypes = listOf("text/calendar", "text/x-vcard")))),
        )
    }

    @Test
    fun aliasLookupAndLinkUrls() {
        assertEquals(HandlerAlias.VIEW_PDF, HandlerAlias.forClassName("app.deft.ui.handlers.ViewPdf"))
        assertNull(HandlerAlias.forClassName("app.deft.ui.handlers.HandlerPickerActivity"))
        assertEquals(HandlerAlias.SENDTO_MAILTO, HandlerAlias.forKey("sendto-mailto"))
        assertTrue(HandlerMatch.validLinkUrl("mailto:a@b.example?subject=Hi", "mailto"))
        assertTrue(HandlerMatch.validLinkUrl("web+deft:open/note/1", "web+deft"))
        assertFalse(HandlerMatch.validLinkUrl("https://a.example", "mailto"))
        assertFalse(HandlerMatch.validLinkUrl("mailto:a\nb", "mailto"))
        assertFalse(HandlerMatch.validLinkUrl("mailto:" + "a".repeat(HandlerMatch.MAX_URL), "mailto"))
        assertFalse(HandlerMatch.validLinkUrl(null, "mailto"))
    }

    @Test
    fun rememberedChoiceOnlyWhileItStillApplies() {
        assertEquals("a", HandlerMatch.rememberedChoice("a", listOf("a", "b")))
        assertNull(HandlerMatch.rememberedChoice("gone", listOf("a", "b")))
        assertNull(HandlerMatch.rememberedChoice(null, listOf("a")))
    }

    private fun manifest(handles: String, profile: String = "myapp") = """
        {"deft":1,"id":"hh","name":"H","version":"1.0.0","profile":"$profile","handles":$handles}
    """.trimIndent()

    @Test
    fun parserAcceptsHandles() {
        val m = DeftManifestParser.parse(
            manifest("""{"mimeTypes":["image/*","application/pdf","image/*"],"schemes":["web+deft","mailto"],"links":["https://notes.example"]}"""),
        )
        assertEquals(listOf("image/*", "application/pdf"), m.handles!!.mimeTypes)
        assertEquals(listOf("web+deft", "mailto"), m.handles!!.schemes)
        assertEquals(listOf("https://notes.example"), m.handles!!.links)
        assertNull(DeftManifestParser.parse(manifest("{}")).handles)
        assertNull(DeftManifestParser.parse(manifest("null")).handles)
    }

    @Test
    fun parserRejectsBadHandles() {
        val bad = listOf(
            "[]",
            """{"mimeTypes":"image/*"}""",
            """{"mimeTypes":["*/*"]}""",
            """{"mimeTypes":["image"]}""",
            """{"mimeTypes":["Image/PNG"]}""",
            """{"mimeTypes":["image/*x"]}""",
            """{"mimeTypes":[3]}""",
            """{"schemes":["foo"]}""",
            """{"schemes":["web+"]}""",
            """{"schemes":["web+Foo"]}""",
            """{"schemes":["https"]}""",
            """{"links":["http://a.example"]}""",
            """{"links":["https://localhost"]}""",
            """{"links":["https://a.example/path"]}""",
            """{"mimeTypes":[${(1..17).joinToString(",") { "\"image/x$it\"" }}]}""",
        )
        for (h in bad) {
            try {
                DeftManifestParser.parse(manifest(h))
                fail("accepted $h")
            } catch (_: IllegalArgumentException) {
            }
        }
        val sixteen = (1..16).joinToString(",") { "\"image/x$it\"" }
        assertEquals(16, DeftManifestParser.parse(manifest("""{"mimeTypes":[$sixteen]}""")).handles!!.mimeTypes.size)
        try {
            DeftManifestParser.parse(manifest("""{"schemes":["web+deft"]}""", profile = "canvas"))
            fail("canvas accepted handles")
        } catch (_: IllegalArgumentException) {
        }
    }
}
