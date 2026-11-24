package app.deft.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentImagesTest {
    @Test
    fun smallImagesAreNeverUpscaled() {
        assertEquals(800 to 600, AgentImages.targetSize(800, 600, AgentImages.MAX_SIDE))
        assertEquals(1568 to 10, AgentImages.targetSize(1568, 10, AgentImages.MAX_SIDE))
    }

    @Test
    fun longSideIsCappedAndAspectKept() {
        assertEquals(1568 to 784, AgentImages.targetSize(4000, 2000, AgentImages.MAX_SIDE))
        assertEquals(784 to 1568, AgentImages.targetSize(2000, 4000, AgentImages.MAX_SIDE))
        assertEquals(160 to 90, AgentImages.targetSize(1600, 900, AgentImages.THUMB_SIDE))
    }

    @Test
    fun exactlyMaxSideIsKept() {
        assertEquals(1568 to 1568, AgentImages.targetSize(1568, 1568, AgentImages.MAX_SIDE))
    }

    @Test
    fun degenerateSizesStayAtLeastOnePixel() {
        assertEquals(1 to 1, AgentImages.targetSize(0, 0, AgentImages.MAX_SIDE))
        val (w, h) = AgentImages.targetSize(100_000, 1, AgentImages.MAX_SIDE)
        assertEquals(1568, w)
        assertTrue(h >= 1)
    }

    @Test
    fun imageCostIsFixedNotBase64Length() {
        val huge = "data:image/jpeg;base64," + "A".repeat(500_000)
        val withImage = ChatMessage(role = "user", content = "hi", images = listOf(huge))
        val textOnly = ChatMessage(role = "user", content = "hi")
        val diff = AgentContext.estimateMessage(withImage) - AgentContext.estimateMessage(textOnly)
        assertEquals(AgentContext.IMAGE_TOKENS, diff)
        val requestDiff = AgentContext.estimateRequest(listOf(withImage), emptyList()) -
            AgentContext.estimateRequest(listOf(textOnly), emptyList())
        assertEquals(AgentContext.IMAGE_TOKENS, requestDiff)
    }

    @Test
    fun imagesBecomeMarkersForSummaries() {
        val msgs = listOf(
            ChatMessage(role = "user", content = "look", images = listOf("data:image/jpeg;base64,AAA", "data:image/jpeg;base64,BBB")),
            ChatMessage(role = "user", content = "", images = listOf("data:image/jpeg;base64,CCC")),
            ChatMessage(role = "user", content = "plain"),
        )
        val out = AgentContext.attachmentsAsText(msgs)
        assertEquals("look\n\n[image] [image]", out[0].content)
        assertEquals(null, out[0].images)
        assertEquals("[image]", out[1].content)
        assertEquals(msgs[2], out[2])
        val summary = AgentContext.serializeForSummary(msgs)
        assertTrue(!summary.contains("base64"))
        assertTrue(summary.contains("[image]"))
    }

    private val pdfPayload = "data:application/pdf;base64," + "A".repeat(409_600) // 307_200 raw bytes = 300 KiB

    @Test
    fun pdfCostsByRawSizeWithAFloor() {
        assertEquals(7_500, AgentContext.pdfTokens(FilePart("big.pdf", pdfPayload)))
        assertEquals(1_500, AgentContext.pdfTokens(FilePart("tiny.pdf", "data:application/pdf;base64,AAAA")))
    }

    @Test
    fun pdfCostIsNotItsBase64Length() {
        val withPdf = ChatMessage(role = "user", content = "hi", files = listOf(FilePart("big.pdf", pdfPayload)))
        val textOnly = ChatMessage(role = "user", content = "hi")
        assertEquals(7_500, AgentContext.estimateMessage(withPdf) - AgentContext.estimateMessage(textOnly))
        val requestDiff = AgentContext.estimateRequest(listOf(withPdf), emptyList()) -
            AgentContext.estimateRequest(listOf(textOnly), emptyList())
        assertEquals(7_500, requestDiff)
    }

    @Test
    fun pdfsBecomeNamedMarksForSummaries() {
        val msgs = listOf(
            ChatMessage(
                role = "user",
                content = "read these",
                images = listOf("data:image/jpeg;base64,AAA"),
                files = listOf(FilePart("report.pdf", pdfPayload)),
            ),
            ChatMessage(role = "user", content = "", files = listOf(FilePart("b.pdf", pdfPayload))),
        )
        val out = AgentContext.attachmentsAsText(msgs)
        assertEquals("read these\n\n[image] [pdf: report.pdf]", out[0].content)
        assertEquals(null, out[0].files)
        assertEquals("[pdf: b.pdf]", out[1].content)
        val summary = AgentContext.serializeForSummary(msgs)
        assertFalse(summary.contains("base64"))
        assertTrue(summary.contains("[pdf: report.pdf]"))
    }
}
