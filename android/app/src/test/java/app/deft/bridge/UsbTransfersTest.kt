package app.deft.bridge

import app.deft.bridge.modules.UsbTransfers
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class UsbTransfersTest {
    private val decode: (String) -> ByteArray? = { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    @Test
    fun directionComesFromTheAddressAndRequestType() {
        assertTrue(UsbTransfers.isInEndpoint(0x81))
        assertFalse(UsbTransfers.isInEndpoint(0x02))
        assertTrue(UsbTransfers.isInRequest(0xC0))
        assertFalse(UsbTransfers.isInRequest(0x21))
    }

    @Test
    fun outCarriesBase64AndInAsksForLength() {
        val out = UsbTransfers.plan(buildJsonObject { put("base64", JsonPrimitive("AQID")) }, decode).getOrThrow()
        assertTrue(out is UsbTransfers.Plan.Out && out.bytes.contentEquals(byteArrayOf(1, 2, 3)))
        val inn = UsbTransfers.plan(buildJsonObject { put("length", JsonPrimitive(64)) }, decode).getOrThrow()
        assertEquals(UsbTransfers.Plan.In(64), inn)
    }

    @Test
    fun badTransfersAreRejected() {
        assertTrue(UsbTransfers.plan(buildJsonObject { }, decode).isFailure)
        assertTrue(UsbTransfers.plan(buildJsonObject { put("base64", JsonPrimitive("AQ==")); put("length", JsonPrimitive(1)) }, decode).isFailure)
        assertTrue(UsbTransfers.plan(buildJsonObject { put("length", JsonPrimitive(UsbTransfers.MAX_TRANSFER + 1)) }, decode).isFailure)
        assertTrue(UsbTransfers.plan(buildJsonObject { put("base64", JsonPrimitive("!!not base64!!")) }, decode).isFailure)
    }

    @Test
    fun timeoutIsClamped() {
        assertEquals(UsbTransfers.DEFAULT_TIMEOUT_MS, UsbTransfers.timeout(buildJsonObject { }))
        assertEquals(UsbTransfers.MAX_TIMEOUT_MS, UsbTransfers.timeout(buildJsonObject { put("timeout", JsonPrimitive(999_999)) }))
        assertEquals(0, UsbTransfers.timeout(buildJsonObject { put("timeout", JsonPrimitive(-5)) }))
    }

    @Test
    fun irreversibleMethodsAlwaysConfirm() {
        assertTrue("nfc.makeReadOnly" in BridgeArgs.ALWAYS_CONFIRM)
        assertTrue(BridgeArgs.confirmSummary("nfc.makeReadOnly", buildJsonObject { }).contains("cannot be undone"))
    }
}
