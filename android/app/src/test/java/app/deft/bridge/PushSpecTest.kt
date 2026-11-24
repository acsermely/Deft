package app.deft.bridge

import app.deft.background.BackgroundTriggers
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PushSpecTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun liveAppGetsTheEventWhateverElseHolds() {
        assertEquals(PushSpec.Route.EVENT, PushSpec.route(live = true, wantsTrigger = true, hasNotification = true))
        assertEquals(PushSpec.Route.EVENT, PushSpec.route(live = true, wantsTrigger = false, hasNotification = false))
    }

    @Test
    fun closedAppWithTriggerRunsInTheBackground() {
        assertEquals(PushSpec.Route.TRIGGER, PushSpec.route(live = false, wantsTrigger = true, hasNotification = true))
        assertEquals(PushSpec.Route.TRIGGER, PushSpec.route(live = false, wantsTrigger = true, hasNotification = false))
    }

    @Test
    fun closedAppWithoutTriggerNotifiesOrQueues() {
        assertEquals(PushSpec.Route.NOTIFY, PushSpec.route(live = false, wantsTrigger = false, hasNotification = true))
        assertEquals(PushSpec.Route.QUEUE, PushSpec.route(live = false, wantsTrigger = false, hasNotification = false))
    }

    @Test
    fun pushTriggerNeedsDeclarationGrantAndManifestEntry() {
        val ok = BackgroundTriggers.Candidate("a", declaresCapability = true, granted = true, triggers = listOf("push"))
        assertTrue(BackgroundTriggers.wants(ok, BackgroundTriggers.PUSH))
        assertFalse(BackgroundTriggers.wants(ok.copy(granted = false), BackgroundTriggers.PUSH))
        assertFalse(BackgroundTriggers.wants(ok.copy(triggers = listOf("alarms")), BackgroundTriggers.PUSH))
        assertFalse(BackgroundTriggers.wants(ok.copy(declaresCapability = false), BackgroundTriggers.PUSH))
    }

    @Test
    fun payloadIsBase64OfTheRawBytes() {
        val raw = byteArrayOf(0, 1, -1, 127, -128)
        val json = BridgeJson.parseToJsonElement(PushSpec.payload(raw)).jsonObject
        assertEquals(setOf("base64"), json.keys)
        assertArrayEquals(raw, Base64.getDecoder().decode(json["base64"]!!.jsonPrimitive.content))
        assertEquals("""{"base64":""}""", PushSpec.payload(ByteArray(0)))
    }

    @Test
    fun sizeCapIs4KiB() {
        assertFalse(PushSpec.tooLarge(ByteArray(4096)))
        assertTrue(PushSpec.tooLarge(ByteArray(4097)))
    }

    @Test
    fun notificationFromJsonTitleAndBody() {
        val n = PushSpec.notification(bytes("""{"title":"  Order shipped ","body":"Arrives Monday","extra":1}"""))
        assertEquals(PushSpec.Notification("Order shipped", "Arrives Monday"), n)
    }

    @Test
    fun notificationNeedsBothAsNonBlankStrings() {
        assertNull(PushSpec.notification(bytes("""{"title":"Hi"}""")))
        assertNull(PushSpec.notification(bytes("""{"body":"Hi"}""")))
        assertNull(PushSpec.notification(bytes("""{"title":" ","body":"Hi"}""")))
        assertNull(PushSpec.notification(bytes("""{"title":1,"body":"Hi"}""")))
        assertNull(PushSpec.notification(bytes("""{"title":{"a":1},"body":"Hi"}""")))
        assertNull(PushSpec.notification(bytes("""{"title":null,"body":"Hi"}""")))
    }

    @Test
    fun nonJsonOrNonObjectPayloadsGiveNoNotification() {
        assertNull(PushSpec.notification(ByteArray(0)))
        assertNull(PushSpec.notification(bytes("hello")))
        assertNull(PushSpec.notification(bytes("""["title","body"]""")))
        assertNull(PushSpec.notification(bytes("\"title\"")))
        assertNull(PushSpec.notification(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d)))
    }

    @Test
    fun oversizedPayloadGivesNoNotification() {
        val big = """{"title":"t","body":"${"x".repeat(5000)}"}"""
        assertNull(PushSpec.notification(bytes(big)))
    }

    @Test
    fun longTextIsCut() {
        val n = PushSpec.notification(bytes("""{"title":"${"t".repeat(300)}","body":"${"b".repeat(2000)}"}"""))!!
        assertEquals(PushSpec.TITLE_MAX, n.title.length)
        assertEquals(PushSpec.BODY_MAX, n.body.length)
    }

    @Test
    fun endpointPayload() {
        assertEquals("""{"endpoint":"https://ntfy.sh/upAbc?up=1"}""", PushSpec.endpointPayload("https://ntfy.sh/upAbc?up=1"))
    }
}
