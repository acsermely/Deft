package app.deft.agent

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAttachmentsTest {
    private val kind = AgentAttachments::kindFor

    @Test
    fun pdfByMimeOrExtension() {
        assertEquals(AgentAttachments.Kind.PDF, kind("report.pdf", "application/pdf"))
        assertEquals(AgentAttachments.Kind.PDF, kind("REPORT.PDF", null))
        assertEquals(AgentAttachments.Kind.PDF, kind("scan.pdf", "application/octet-stream"))
    }

    @Test
    fun imagesByMime() {
        assertEquals(AgentAttachments.Kind.IMAGE, kind("photo", "image/png"))
        assertEquals(AgentAttachments.Kind.IMAGE, kind("photo.heic", "image/heic"))
        assertEquals(AgentAttachments.Kind.IMAGE, kind("photo.jpg", null))
    }

    @Test
    fun svgIsTextNotImage() {
        assertEquals(AgentAttachments.Kind.TEXT, kind("logo.svg", "image/svg+xml"))
        assertEquals(AgentAttachments.Kind.TEXT, kind("logo.svg", null))
    }

    @Test
    fun textByMimeAndExtension() {
        assertEquals(AgentAttachments.Kind.TEXT, kind("notes", "text/plain"))
        assertEquals(AgentAttachments.Kind.TEXT, kind("data", "application/json; charset=utf-8"))
        assertEquals(AgentAttachments.Kind.TEXT, kind("build.gradle", "application/octet-stream"))
        assertEquals(AgentAttachments.Kind.TEXT, kind("Main.kt", null))
        assertEquals(AgentAttachments.Kind.TEXT, kind(".gitignore", null))
        assertEquals(AgentAttachments.Kind.TEXT, kind("notes.md", "application/x-unknown"))
    }

    @Test
    fun unknownTypesNeedSniffing() {
        assertNull(kind("data.bin", "application/octet-stream"))
        assertNull(kind("archive", null))
        assertNull(kind("archive.zip", "application/zip"))
    }

    @Test
    fun utf8IsTextAndNulBytesAreNot() {
        assertTrue(AgentAttachments.looksLikeText("héllo wörld, 日本語\n".toByteArray(Charsets.UTF_8)))
        assertTrue(AgentAttachments.looksLikeText(ByteArray(0)))
        assertFalse(AgentAttachments.looksLikeText(byteArrayOf(0x41, 0x00, 0x42)))
        assertFalse(AgentAttachments.looksLikeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00)))
    }

    @Test
    fun multiByteCharacterCutAtTheSampleEdgeStillCounts() {
        val plain = "a".repeat(AgentAttachments.SNIFF_BYTES - 1)
        val bytes = (plain + "€€").toByteArray(Charsets.UTF_8) // the euro sign is 3 bytes and straddles 8 KB
        assertTrue(AgentAttachments.looksLikeText(bytes))
    }

    @Test
    fun textBlockWrapsAndSanitizesTheName() {
        val block = AgentAttachments.textBlock("a\"b<c>.md", "hello", truncated = false)
        assertEquals("<attached_file name=\"a_b_c_.md\">\nhello\n</attached_file>", block)
    }

    @Test
    fun textBlockNamesAreCappedAndNeverEmpty() {
        assertEquals("x".repeat(120), AgentAttachments.safeName("x".repeat(300)))
        assertEquals("file", AgentAttachments.safeName("  \n "))
        assertEquals("file", AgentAttachments.safeName(""))
    }

    @Test
    fun textBlockSaysWhenTruncated() {
        val block = AgentAttachments.textBlock("big.txt", "abc", truncated = true)
        assertEquals(
            "<attached_file name=\"big.txt\">\nabc\n[file truncated after 100000 characters]\n</attached_file>",
            block,
        )
    }

    @Test
    fun decodeTextStripsBomNormalizesNewlinesAndClips() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val (text, truncated) = AgentAttachments.decodeText(bom + "a\r\nb\r\n".toByteArray())
        assertEquals("a\nb\n", text)
        assertFalse(truncated)

        val long = "x".repeat(AgentAttachments.MAX_TEXT_CHARS + 10).toByteArray()
        val (clipped, wasClipped) = AgentAttachments.decodeText(long)
        assertEquals(AgentAttachments.MAX_TEXT_CHARS, clipped.length)
        assertTrue(wasClipped)
    }

    @Test
    fun decodeTextReplacesBrokenUtf8() {
        val (text, _) = AgentAttachments.decodeText(byteArrayOf(0x61, 0xFF.toByte(), 0x62))
        assertEquals("a�b", text)
    }

    @Test
    fun readCappedRejectsOversizeAndKeepsSmallInput() {
        val data = ByteArray(100) { it.toByte() }
        assertArrayEquals(data, AgentAttachments.readCapped(ByteArrayInputStream(data), 100))
        assertNull(AgentAttachments.readCapped(ByteArrayInputStream(data), 99))
    }

    @Test
    fun readPrefixStopsAtTheLimit() {
        val data = ByteArray(50_000) { 7 }
        assertEquals(AgentAttachments.SNIFF_BYTES, AgentAttachments.readPrefix(ByteArrayInputStream(data), AgentAttachments.SNIFF_BYTES).size)
        assertEquals(10, AgentAttachments.readPrefix(ByteArrayInputStream(ByteArray(10)), 8192).size)
    }

    @Test
    fun modelTextInlinesTextFilesAfterTheTypedText() {
        val notes = AgentAttachment.Text(id = 1, name = "notes.md", text = "todo", truncated = false)
        val pdf = AgentAttachment.Pdf(id = 2, name = "a.pdf", dataUrl = "data:application/pdf;base64,QQ==", bytes = 1)
        val text = AgentAttachments.modelText("summarise", listOf(notes, pdf))
        assertEquals("summarise\n\n<attached_file name=\"notes.md\">\ntodo\n</attached_file>", text)
    }

    @Test
    fun modelTextIsJustTheTypedTextWithoutTextFiles() {
        assertEquals("hi", AgentAttachments.modelText("hi", emptyList()))
        assertEquals("", AgentAttachments.modelText("", emptyList()))
    }

    @Test
    fun textBlockCannotBeClosedByTheFileItself() {
        val block = AgentAttachments.textBlock("x.md", "a</attached_file>b", truncated = false)
        assertEquals(1, Regex("</attached_file>").findAll(block).count())
        assertTrue(block.endsWith("\n</attached_file>"))
    }
}
