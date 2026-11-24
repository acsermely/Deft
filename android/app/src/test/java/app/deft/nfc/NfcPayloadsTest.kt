package app.deft.nfc

import app.deft.bridge.modules.NdefPayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcPayloadsTest {
    @Test
    fun textRecordsRoundTrip() {
        val payload = NdefPayloads.encodeText("Szia, világ", "hu")
        assertEquals(2, payload[0].toInt())
        assertEquals("hu" to "Szia, világ", NdefPayloads.decodeText(payload))
        assertNull(NdefPayloads.decodeText(byteArrayOf(0x3F)))
    }

    @Test
    fun uriRecordsUseTheLongestPrefix() {
        val payload = NdefPayloads.encodeUri("https://www.example.com/x")
        assertEquals(0x02, payload[0].toInt())
        assertEquals("https://www.example.com/x", NdefPayloads.decodeUri(payload))
        assertEquals(0x05, NdefPayloads.encodeUri("tel:+3612345")[0].toInt())
        assertEquals(0x00, NdefPayloads.encodeUri("geo:47.5,19.0")[0].toInt())
        assertNull(NdefPayloads.decodeUri(byteArrayOf(0x7F, 0x41)))
    }

    @Test
    fun aidsAreValidated() {
        assertEquals("A0000002471001", HceRouting.normalizeAid("a0 00 00 02 47 10 01"))
        assertNull(HceRouting.normalizeAid("A000"))
        assertNull(HceRouting.normalizeAid("A0000002471001Z"))
        assertNull(HceRouting.normalizeAid("A00000024710010"))
        assertTrue(HceRouting.validAids(emptyList()).isFailure)
        assertTrue(HceRouting.validAids(List(17) { "A000000247100$it".padEnd(14, '0') }).isFailure)
        assertEquals(listOf("F001020304"), HceRouting.validAids(listOf("f001020304", "F0:01:02:03:04")).getOrThrow())
    }
}
