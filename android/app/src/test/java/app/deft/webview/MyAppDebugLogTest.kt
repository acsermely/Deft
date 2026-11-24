package app.deft.webview

import app.deft.agent.AgentContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MyAppDebugLogTest {
    @Before
    fun reset() {
        MyAppDebugLog.resetForTests()
    }

    @After
    fun cleanup() {
        MyAppDebugLog.resetForTests()
    }

    @Test
    fun isolatesSlugs() {
        MyAppDebugLog.append("notes", error("boom"))
        MyAppDebugLog.append("timer", warn("slow"))
        val notes = MyAppDebugLog.dump("notes")
        val timer = MyAppDebugLog.dump("timer")
        assertTrue(notes.contains("ERROR console boom"))
        assertTrue(notes.contains("errors=1"))
        assertFalse(notes.contains("slow"))
        assertTrue(timer.contains("WARN console slow"))
        assertTrue(timer.contains("warnings=1"))
    }

    @Test
    fun dedupesAndRotates() {
        repeat(3) { MyAppDebugLog.append("notes", error("same")) }
        assertEquals(1, MyAppDebugLog.snapshot("notes").size)
        for (i in 0 until WebViewFindings.MAX + 5) {
            MyAppDebugLog.append("notes", error("n$i"))
        }
        val items = MyAppDebugLog.snapshot("notes")
        assertEquals(WebViewFindings.MAX, items.size)
        val texts = items.map { it.text }.toSet()
        assertFalse(texts.contains("same"))
        assertFalse(texts.contains("n0"))
        assertTrue(texts.contains("n${WebViewFindings.MAX + 4}"))
        assertTrue(MyAppDebugLog.dump("notes").contains("…truncated"))
        MyAppDebugLog.clear("notes")
        for (i in 0 until WebViewFindings.MAX) {
            MyAppDebugLog.append("notes", error("f$i"))
        }
        assertFalse(MyAppDebugLog.dump("notes").contains("…truncated"))
        MyAppDebugLog.append("notes", error("same"))
        assertTrue(MyAppDebugLog.snapshot("notes").any { it.text == "same" })
    }

    @Test
    fun fingerprintMatchesEqualFindings() {
        assertEquals(error("boom", "main.js", 3).fingerprint(), error("boom", "main.js", 3).fingerprint())
        assertTrue(error("a").fingerprint() != error("b").fingerprint())
    }

    @Test
    fun evictsOldestSlug() {
        for (i in 0..MyAppDebugLog.MAX_SLUGS) {
            MyAppDebugLog.append("app$i", error("e$i"))
        }
        assertTrue(MyAppDebugLog.snapshot("app0").isEmpty())
        assertEquals(1, MyAppDebugLog.snapshot("app${MyAppDebugLog.MAX_SLUGS}").size)
    }

    @Test
    fun dumpFormatAndClear() {
        MyAppDebugLog.append("notes", error("missing", "https://notes.app.deft.internal/main.js", 12))
        val dump = MyAppDebugLog.dump("notes")
        assertTrue(dump.startsWith("issues\n"))
        assertTrue(dump.contains("slug=notes"))
        assertTrue(dump.contains("errors=1"))
        assertTrue(dump.contains("warnings=0"))
        assertTrue(dump.contains("ERROR console missing @ https://notes.app.deft.internal/main.js:12"))
        MyAppDebugLog.clear("notes")
        val empty = MyAppDebugLog.dump("notes")
        assertTrue(empty.startsWith("ok\n"))
        assertTrue(empty.contains("errors=0"))
        assertTrue(empty.contains("no Preview errors yet"))
        assertTrue(empty.contains("in-memory until process death"))
        assertNull(MyAppDebugLog.hint("notes"))
    }

    @Test
    fun clearStartsFreshWithoutTouchingOtherSlugs() {
        MyAppDebugLog.append("notes", error("old"))
        MyAppDebugLog.append("timer", warn("slow"))
        MyAppDebugLog.clear("notes")
        assertTrue(MyAppDebugLog.snapshot("notes").isEmpty())
        assertEquals(1, MyAppDebugLog.snapshot("timer").size)
        MyAppDebugLog.append("notes", error("new"))
        val dump = MyAppDebugLog.dump("notes")
        assertTrue(dump.contains("new"))
        assertFalse(dump.contains("old"))
    }

    @Test
    fun hintWhenIssues() {
        assertNull(MyAppDebugLog.hint(null))
        assertNull(MyAppDebugLog.hint("notes"))
        MyAppDebugLog.append("notes", warn("css"))
        assertEquals(
            "Preview debug: 0 errors, 1 warnings (apps.debug).",
            MyAppDebugLog.hint("notes"),
        )
        assertEquals(
            "Current my-app: notes. File tools stay in this folder. You cannot open other workspaces. Preview debug: 0 errors, 1 warnings (apps.debug).",
            AgentContext.slugLine("notes"),
        )
    }

    @Test
    fun clipsHttpAndNewlines() {
        val url = "https://notes.app.deft.internal/" + "a".repeat(WebViewFindings.TEXT_MAX)
        MyAppDebugLog.append("notes", WebViewFindings.http(404, url))
        val item = MyAppDebugLog.snapshot("notes").single()
        assertEquals(WebViewFindings.TEXT_MAX, item.text.length)
        assertEquals(WebViewFindings.TEXT_MAX, item.source.length)
        MyAppDebugLog.clear("notes")
        MyAppDebugLog.append(
            "notes",
            WebViewFindings.load("fail\nline", "https://notes.app.deft.internal/x"),
        )
        val load = MyAppDebugLog.snapshot("notes").single()
        assertFalse(load.text.contains('\n'))
        assertTrue(load.text.startsWith("fail line"))
    }

    @Test
    fun recordsInterceptedHttpAndSkipsNoise() {
        val js = "https://notes.app.deft.internal/store.js"
        val ico = "https://notes.app.deft.internal/favicon.ico"
        val png = "https://notes.app.deft.internal/icon.png"
        assertTrue(WebViewFindings.shouldRecordHttp(true, ico))
        assertFalse(WebViewFindings.shouldRecordHttp(false, ico))
        assertFalse(WebViewFindings.shouldRecordHttp(false, png))
        assertTrue(WebViewFindings.shouldRecordHttp(false, js))
        assertTrue(WebViewFindings.shouldRecordHttp(false, "https://notes.app.deft.internal/styles.css"))
        assertNull(WebViewFindings.fromIntercept(false, js, 200))
        assertNull(WebViewFindings.fromIntercept(false, js, null))
        assertNull(WebViewFindings.fromIntercept(false, ico, 404))
        val missing = WebViewFindings.fromIntercept(false, js, 404)
        assertEquals("ERROR", missing?.level)
        assertEquals("http", missing?.kind)
        assertTrue(missing!!.text.startsWith("404 "))
        assertTrue(missing.text.contains(js))
    }

    @Test
    fun skipsCancelAndSubframeLoads() {
        assertFalse(WebViewFindings.shouldRecordLoad(false, "net::ERR_CONNECTION_REFUSED"))
        assertFalse(WebViewFindings.shouldRecordLoad(true, "net::ERR_ABORTED"))
        assertFalse(WebViewFindings.shouldRecordLoad(true, "net::ERR_BLOCKED_BY_CLIENT"))
        assertTrue(WebViewFindings.shouldRecordLoad(true, "net::ERR_FAILED"))
        assertTrue(WebViewFindings.shouldRecordLoad(true, "net::ERR_CONNECTION_REFUSED"))
    }

    @Test
    fun hookClipsBeforeConsole() {
        assertTrue(WebViewFindings.HOOK_JS.contains("slice(0, ${WebViewFindings.TEXT_MAX})"))
    }

    @Test
    fun formatOmitsEmptyHintWhenThereAreFindings() {
        val text = WebViewFindings.format(
            slug = "x",
            findings = listOf(error("e")),
            emptyHint = "should not show",
        )
        assertFalse(text.contains("should not show"))
        assertFalse(text.contains("url="))
    }

    @Test
    fun ringKeepsNewestAndMarksTruncated() {
        val ring = WebViewFindingRing(max = 3)
        ring.offer(error("a"))
        ring.offer(error("a"))
        ring.offer(error("b"))
        ring.offer(error("c"))
        var (items, overflow) = ring.snapshot()
        assertEquals(listOf("a", "b", "c"), items.map { it.text })
        assertFalse(overflow)
        ring.offer(error("d"))
        ring.offer(error("e"))
        val snap = ring.snapshot()
        items = snap.first
        overflow = snap.second
        assertEquals(3, items.size)
        assertEquals(listOf("c", "d", "e"), items.map { it.text })
        assertTrue(overflow)
        assertTrue(
            WebViewFindings.format(slug = "x", findings = items, overflow = overflow).contains("…truncated"),
        )
    }

    @Test
    fun skipsNativeUncaughtKeepsHook() {
        assertTrue(WebViewFindings.isNativeUncaughtDuplicate("Uncaught TypeError: x is not a function"))
        assertTrue(WebViewFindings.isNativeUncaughtDuplicate("  Uncaught (in promise) boom"))
        assertFalse(WebViewFindings.isNativeUncaughtDuplicate("[uncaught] boom @ main.js:3"))
        assertFalse(WebViewFindings.isNativeUncaughtDuplicate("missing store.js"))
        assertNull(
            WebViewFindings.consoleFinding("ERROR", "Uncaught ReferenceError: foo is not defined", "main.js", 4),
        )
        val hooked = WebViewFindings.consoleFinding("ERROR", "[uncaught] foo @ main.js:4", "main.js", 4)
        assertEquals("[uncaught] foo @ main.js:4", hooked?.text)
        val warn = WebViewFindings.consoleFinding("WARN", "slow css")
        assertEquals("WARN", warn?.level)
        assertEquals("slow css", warn?.text)
    }

    private fun error(text: String, source: String = "", line: Int = 0) =
        WebViewFinding(level = "ERROR", kind = "console", text = text, source = source, line = line)

    private fun warn(text: String) =
        WebViewFinding(level = "WARN", kind = "console", text = text)
}
