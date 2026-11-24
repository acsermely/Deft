package app.deft.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentChatApiImagesTest {
    private fun content(m: ChatMessage): JsonArray = AgentChatApi.toApiMessages(listOf(m)).single().content!!.jsonArray

    @Test
    fun textOnlyUserMessageIsUnchanged() {
        val parts = content(ChatMessage(role = "user", content = "hello"))
        assertEquals(
            """[{"type":"text","text":"hello"}]""",
            parts.toString(),
        )
    }

    @Test
    fun textPlusTwoImagesIsTextThenImageParts() {
        val a = "data:image/jpeg;base64,AAAA"
        val b = "data:image/jpeg;base64,BBBB"
        val parts = content(ChatMessage(role = "user", content = "compare", images = listOf(a, b)))
        assertEquals(3, parts.size)
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("compare", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        for ((i, url) in listOf(a, b).withIndex()) {
            val part = parts[i + 1].jsonObject
            assertEquals("image_url", part["type"]!!.jsonPrimitive.content)
            assertEquals(url, part["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        }
        assertEquals(
            """[{"type":"text","text":"compare"},{"type":"image_url","image_url":{"url":"$a"}},{"type":"image_url","image_url":{"url":"$b"}}]""",
            parts.toString(),
        )
    }

    @Test
    fun imagesOnlyHasNoEmptyTextPart() {
        val url = "data:image/jpeg;base64,CCCC"
        for (text in listOf("", "   ")) {
            val parts = content(ChatMessage(role = "user", content = text, images = listOf(url)))
            assertEquals(1, parts.size)
            assertEquals("image_url", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun textImagesThenPdfAreEmittedAsFileParts() {
        val img = "data:image/jpeg;base64,AAAA"
        val pdf = "data:application/pdf;base64,QUJD"
        val msg = ChatMessage(role = "user", content = "read", images = listOf(img), files = listOf(FilePart("report.pdf", pdf)))
        val json = AgentChatApi.toApiMessages(listOf(msg)).single().content!!.toString()
        assertEquals(
            """[{"type":"text","text":"read"},{"type":"image_url","image_url":{"url":"$img"}},""" +
                """{"type":"file","file":{"filename":"report.pdf","file_data":"$pdf"}}]""",
            json,
        )
        assertFalse(json.contains("plugins"))
    }

    @Test
    fun pdfOnlyHasNoEmptyTextPart() {
        val parts = content(ChatMessage(role = "user", content = " ", files = listOf(FilePart("a.pdf", "data:application/pdf;base64,QQ=="))))
        assertEquals(1, parts.size)
        assertEquals("file", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun systemMessagesKeepCacheControlAndNeverGainImages() {
        val sys = AgentChatApi.toApiMessages(listOf(ChatMessage(role = "system", content = "rules")))
            .single().content!!.jsonArray
        assertEquals(
            """[{"type":"text","text":"rules","cache_control":{"type":"ephemeral"}}]""",
            sys.toString(),
        )
        val plain = content(ChatMessage(role = "assistant", content = "ok"))
        assertFalse(plain.toString().contains("image_url"))
        assertEquals(JsonPrimitive("text"), plain[0].jsonObject["type"])
    }
}
