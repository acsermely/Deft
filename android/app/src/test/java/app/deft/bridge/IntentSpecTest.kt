package app.deft.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentSpecTest {
    private fun args(action: String = "VIEW", type: String? = null, data: String? = null, extras: JsonObject? = null): JsonObject =
        buildJsonObject {
            put("action", action)
            type?.let { put("type", it) }
            data?.let { put("data", it) }
            extras?.let { put("extras", it) }
        }

    private fun extras(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(pairs.toMap())

    private fun refused(args: JsonObject): String {
        val result = IntentSpec.parse(args)
        assertTrue("expected a refusal", result.isFailure)
        return result.exceptionOrNull()!!.message!!
    }

    @Test
    fun allowedActionsMapToAndroidActions() {
        val spec = IntentSpec.parse(args(action = "IMAGE_CAPTURE")).getOrThrow()
        assertEquals("android.media.action.IMAGE_CAPTURE", spec.androidAction)
        assertEquals("IMAGE_CAPTURE", spec.shortAction)
        assertEquals("android.intent.action.SEND", IntentSpec.parse(args(action = "SEND")).getOrThrow().androidAction)
        assertEquals("android.intent.action.DIAL", IntentSpec.parse(args(action = "DIAL", data = "tel:+15550100")).getOrThrow().androidAction)
    }

    @Test
    fun everyAllowedActionParses() {
        for (action in IntentSpec.ACTIONS.keys) {
            assertTrue(action, IntentSpec.parse(args(action = action)).isSuccess)
        }
    }

    @Test
    fun actionsOutsideTheListAreRefused() {
        assertEquals("action not allowed", refused(args(action = "CALL")))
        assertEquals("action not allowed", refused(args(action = "ACTION_CALL")))
        assertEquals("action not allowed", refused(args(action = "android.intent.action.VIEW")))
        assertEquals("action not allowed", refused(args(action = "")))
        assertEquals("action not allowed", refused(args(action = "send")))
    }

    @Test
    fun actionIsRequiredAndMustBeAString() {
        assertEquals("action required", refused(buildJsonObject {}))
        assertEquals("action must be a string", refused(buildJsonObject { put("action", 5) }))
        assertEquals("action must be a string", refused(buildJsonObject { put("action", JsonNull) }))
    }

    @Test
    fun webAndContactSchemesAreAllowed() {
        for (data in listOf("https://example.com/a", "HTTP://example.com", "mailto:a@example.com", "tel:+15550100", "sms:+15550100", "geo:0,0?q=Paris")) {
            assertTrue(data, IntentSpec.parse(args(action = "VIEW", data = data)).isSuccess)
        }
    }

    @Test
    fun contentUrisAreRefused() {
        assertEquals("files cannot be sent to other apps", refused(args(action = "VIEW", data = "content://app.deft.files/x")))
        assertEquals("files cannot be sent to other apps", refused(args(action = "SEND", data = "Content://anything")))
    }

    @Test
    fun fileUrisAreRefused() {
        assertEquals("files cannot be sent to other apps", refused(args(action = "VIEW", data = "file:///data/data/app/x")))
        assertEquals("files cannot be sent to other apps", refused(args(action = "VIEW", data = "FILE:/sdcard/a.pdf")))
    }

    @Test
    fun otherSchemesAreRefused() {
        assertEquals("data scheme intent is not allowed", refused(args(action = "VIEW", data = "intent://x#Intent;end")))
        assertEquals("data scheme javascript is not allowed", refused(args(action = "VIEW", data = "javascript:alert(1)")))
        assertEquals("data scheme android.resource is not allowed", refused(args(action = "VIEW", data = "android.resource://app/1")))
    }

    @Test
    fun dataWithoutSchemeIsRefused() {
        assertEquals("data must be a URI with a scheme", refused(args(action = "VIEW", data = "/sdcard/a.jpg")))
        assertEquals("data must be a URI with a scheme", refused(args(action = "VIEW", data = "example.com")))
    }

    @Test
    fun sendMultipleTakesNoData() {
        assertEquals("SEND_MULTIPLE takes text only", refused(args(action = "SEND_MULTIPLE", data = "https://example.com")))
        assertEquals(
            "extra android.intent.extra.STREAM is not allowed",
            refused(args(action = "SEND_MULTIPLE", extras = extras("android.intent.extra.STREAM" to JsonPrimitive("x")))),
        )
        assertEquals(
            "SEND_MULTIPLE takes text only",
            refused(args(action = "SEND_MULTIPLE", extras = extras("android.intent.extra.CC" to JsonPrimitive("a@example.com")))),
        )
    }

    @Test
    fun sendMultipleWithTextExtrasIsAllowed() {
        val spec = IntentSpec.parse(
            args(action = "SEND_MULTIPLE", extras = extras("android.intent.extra.TEXT" to JsonPrimitive("hi"))),
        ).getOrThrow()
        assertEquals(mapOf("android.intent.extra.TEXT" to "hi"), spec.extras)
    }

    @Test
    fun streamExtraIsRefusedEvenForSend() {
        assertEquals(
            "extra android.intent.extra.STREAM is not allowed",
            refused(args(action = "SEND", extras = extras("android.intent.extra.STREAM" to JsonPrimitive("content://x")))),
        )
    }

    @Test
    fun typeMustBeAMimeType() {
        assertEquals("type is not a MIME type", refused(args(action = "SEND", type = "text")))
        assertEquals("type is not a MIME type", refused(args(action = "SEND", type = "image/jpeg; charset=utf-8")))
        assertTrue(IntentSpec.parse(args(action = "SEND", type = "image/*")).isSuccess)
        assertEquals("image/jpeg", IntentSpec.parse(args(action = "SEND", type = "image/jpeg")).getOrThrow().type)
    }

    @Test
    fun extrasKeysMustBeAndroidConstants() {
        assertEquals(
            "extra my.key is not allowed",
            refused(args(extras = extras("my.key" to JsonPrimitive("x")))),
        )
        assertEquals(
            "extra android.intent.extra.x y is not allowed",
            refused(args(extras = extras("android.intent.extra.x y" to JsonPrimitive("x")))),
        )
        assertEquals(
            "extra android.os.Bundle is not allowed",
            refused(args(extras = extras("android.os.Bundle" to JsonPrimitive("x")))),
        )
    }

    @Test
    fun providerExtrasAreAllowed() {
        val spec = IntentSpec.parse(
            args(action = "INSERT", extras = extras("android.provider.extra.NAME" to JsonPrimitive("Ada"))),
        ).getOrThrow()
        assertEquals("Ada", spec.extras["android.provider.extra.NAME"])
    }

    @Test
    fun extrasValuesMustBeStrings() {
        assertEquals(
            "extra android.intent.extra.TEXT must be a string",
            refused(args(extras = extras("android.intent.extra.TEXT" to JsonPrimitive(3)))),
        )
        assertEquals(
            "extra android.intent.extra.TEXT must be a string",
            refused(args(extras = extras("android.intent.extra.TEXT" to JsonNull))),
        )
    }

    @Test
    fun extrasAreAtMostSixteenKeys() {
        val sixteen = JsonObject((1..16).associate { "android.intent.extra.K$it" to JsonPrimitive("v") })
        assertEquals(16, IntentSpec.parse(args(extras = sixteen)).getOrThrow().extras.size)
        val seventeen = JsonObject((1..17).associate { "android.intent.extra.K$it" to JsonPrimitive("v") })
        assertEquals("too many extras", refused(args(extras = seventeen)))
    }

    @Test
    fun extraValuesAreAtMost4096Characters() {
        val max = "a".repeat(4096)
        assertEquals(4096, IntentSpec.parse(args(extras = extras("android.intent.extra.TEXT" to JsonPrimitive(max)))).getOrThrow().extras.values.first().length)
        assertEquals(
            "extra android.intent.extra.TEXT is too long",
            refused(args(extras = extras("android.intent.extra.TEXT" to JsonPrimitive("a".repeat(4097))))),
        )
    }

    @Test
    fun extraValuesNamingFilesAreRefused() {
        assertEquals(
            "extra android.intent.extra.TEXT names a file, which cannot be sent",
            refused(args(action = "SEND", extras = extras("android.intent.extra.TEXT" to JsonPrimitive("content://app/x")))),
        )
        assertEquals(
            "extra android.intent.extra.TEXT names a file, which cannot be sent",
            refused(args(action = "SEND", extras = extras("android.intent.extra.TEXT" to JsonPrimitive("  file:///etc/passwd")))),
        )
    }

    @Test
    fun extrasMustBeAnObject() {
        assertEquals("extras must be an object", refused(buildJsonObject { put("action", "SEND"); put("extras", "x") }))
    }

    @Test
    fun plainSendWithTextIsAccepted() {
        val spec = IntentSpec.parse(
            args(action = "SEND", type = "text/plain", extras = extras("android.intent.extra.TEXT" to JsonPrimitive("hello"))),
        ).getOrThrow()
        assertEquals("android.intent.action.SEND", spec.androidAction)
        assertEquals("text/plain", spec.type)
        assertNull(spec.data)
    }
}
