package app.deft.nfc

import app.deft.catalog.BridgeCatalog
import app.deft.catalog.Capability
import app.deft.catalog.Risk
import app.deft.roles.RoleComponent
import app.deft.roles.RoleSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Routing state and the payment service's static rules. */
class HceRoutingTest {
    @Test
    fun onlyTheAppThatGotTheCommandAnswersItOnce() = runBlocking {
        val pending = HcePending()
        val (id, deferred) = pending.open("holder")
        assertFalse(pending.respond("other", id, byteArrayOf(0x90.toByte(), 0x00)))
        assertFalse(pending.respond("holder", "unknown", byteArrayOf(0x90.toByte(), 0x00)))
        assertTrue(pending.respond("holder", id, byteArrayOf(0x90.toByte(), 0x00)))
        assertFalse("second answer", pending.respond("holder", id, byteArrayOf(0x6A, 0x82.toByte())))
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x00), deferred.await())
        pending.close(id)
        assertEquals(0, pending.size)
        assertFalse("expired", pending.respond("holder", id, byteArrayOf(0x90.toByte(), 0x00)))
    }

    @Test
    fun requestIdsAreUnique() {
        val pending = HcePending()
        val ids = List(50) { pending.open("holder").first }.toSet()
        assertEquals(50, ids.size)
    }

    @Test
    fun placeholdersAreValidDistinctAndAddedOnce() {
        assertEquals(HceRouting.PLACEHOLDER_AID, HceRouting.normalizeAid(HceRouting.PLACEHOLDER_AID))
        assertEquals(HceRouting.PAYMENT_PLACEHOLDER_AID, HceRouting.normalizeAid(HceRouting.PAYMENT_PLACEHOLDER_AID))
        assertNotEquals(HceRouting.PLACEHOLDER_AID, HceRouting.PAYMENT_PLACEHOLDER_AID)
        val ppse = "325041592E5359532E4444463031"
        assertEquals(listOf(ppse), HceRouting.validAids(listOf("325041592e5359532e4444463031")).getOrThrow())
        assertEquals(
            listOf(ppse, HceRouting.PAYMENT_PLACEHOLDER_AID),
            HceRouting.withPlaceholder(listOf(ppse, HceRouting.PAYMENT_PLACEHOLDER_AID), HceRouting.PAYMENT_PLACEHOLDER_AID),
        )
        assertEquals("linkLoss", HceRouting.deactivationReason(true))
        assertEquals("deselected", HceRouting.deactivationReason(false))
    }

    @Test
    fun paymentServiceXmlIsPaymentCategoryAndNeedsUnlock() {
        val xml = File("src/main/res/xml/apduservice_payment.xml").readText()
        assertTrue("android:category=\"payment\"" in xml)
        assertTrue("android:requireDeviceUnlock=\"true\"" in xml)
        assertTrue("android:name=\"${HceRouting.PAYMENT_PLACEHOLDER_AID}\"" in xml)
        assertTrue("android:apduServiceBanner=" in xml)
        val other = File("src/main/res/xml/apduservice.xml").readText()
        assertTrue("android:name=\"${HceRouting.PLACEHOLDER_AID}\"" in other)
        assertFalse("payment" in other.substringAfter("<aid-group"))
    }

    @Test
    fun paymentServiceIsDisabledUntilAHolderExists() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = manifest.substringAfter("android:name=\".nfc.DeftPaymentApduService\"").substringBefore("</service>")
        assertTrue("android:enabled=\"false\"" in service)
        assertTrue("android.permission.BIND_NFC_SERVICE" in service)
        assertTrue("@xml/apduservice_payment" in service)
        assertEquals("app.deft.nfc.DeftPaymentApduService", RoleComponent.PAYMENT_HCE_SERVICE.className)
        assertEquals(setOf(RoleComponent.PAYMENT_HCE_SERVICE), RoleSpec.enabledComponents(setOf("hce-payment")))
        assertTrue(RoleSpec.enabledComponents(setOf("nfc.hce")).isEmpty())
    }

    @Test
    fun paymentMethodsNeedTheSpecialGrant() {
        val cap = Capability.NFC_HCE_PAYMENT
        assertEquals(Risk.SPECIAL, cap.risk)
        assertEquals("hce-payment", cap.exclusiveGroup)
        assertTrue(cap.available)
        for (m in listOf("nfc.paymentRegister", "nfc.paymentUnregister", "nfc.paymentRespond")) {
            assertEquals(m, cap, BridgeCatalog.capabilityFor(m))
        }
    }
}
