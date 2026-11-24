package app.deft.telephony

import app.deft.catalog.Capability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class E164Test {
    @Test
    fun acceptsPlainAndInternationalNumbers() {
        assertTrue(TelephonyBridge.isSafeE164("+15551234567"))
        assertTrue(TelephonyBridge.isSafeE164("0612345678"))
    }

    @Test
    fun rejectsMmiAndShortOrMalformedNumbers() {
        assertFalse(TelephonyBridge.isSafeE164("*100#"))
        assertFalse(TelephonyBridge.isSafeE164("+0123456"))
        assertFalse(TelephonyBridge.isSafeE164("12345"))
        assertFalse(TelephonyBridge.isSafeE164("+1555 123"))
    }

    @Test
    fun blockedNumbersDoNotTakeTheCallsGroup() {
        // Review: granting calls.block must not move calling away from the Phone app.
        assertNull(Capability.CALLS_BLOCK.exclusiveGroup)
    }
}
