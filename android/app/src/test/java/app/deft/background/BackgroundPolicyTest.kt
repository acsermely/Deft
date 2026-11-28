package app.deft.background

import app.deft.catalog.BridgeCatalog
import app.deft.catalog.Capability
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPolicyTest {
    private val none = JsonObject(emptyMap())

    private fun needs(method: String, args: JsonObject = none): Boolean {
        val cap = BridgeCatalog.capabilityFor(method)
        assertNotNull("unmapped $method", cap)
        return BackgroundPolicy.needsForeground(method, args, cap!!)
    }

    @Test
    fun promptsNeedTheForeground() {
        // confirm-each capabilities
        assertTrue(needs("sms.send"))
        assertTrue(needs("telephony.call"))
        assertTrue(needs("telephony.ussd"))
        // always confirmed
        assertTrue(needs("nfc.makeReadOnly"))
        assertTrue(needs("keystore.delete"))
        assertTrue(needs("wifi.suggest"))
    }

    @Test
    fun screensAndPickersNeedTheForeground() {
        for (m in BackgroundPolicy.UI_METHODS) assertTrue(m, needs(m))
        assertTrue(needs("files.pickTree"))
        assertTrue(needs("intents.startForResult"))
        assertTrue(needs("auth.start"))
        assertTrue(needs("dnd.requestAccess"))
        assertTrue(needs("fgs.start"))
    }

    @Test
    fun quietMethodsAreAllowed() {
        assertFalse(needs("contacts.list"))
        assertFalse(needs("notify.post"))
        assertFalse(needs("alarms.schedule"))
        assertFalse(needs("background.done"))
        assertFalse(needs("device.id"))
        assertFalse(needs("auth.prepare"))
        // A run may change or end the app's service, never start one.
        assertFalse(needs("fgs.update"))
        assertFalse(needs("fgs.stop"))
    }

    @Test
    fun largeExportsNeedTheForeground() {
        assertFalse(needs("sms.listMessages"))
        assertFalse(needs("sms.listMessages", JsonObject(mapOf("limit" to JsonPrimitive(500)))))
        assertTrue(needs("sms.listMessages", JsonObject(mapOf("limit" to JsonPrimitive(501)))))
        assertTrue(needs("telephony.listCalls", JsonObject(mapOf("limit" to JsonPrimitive("5000")))))
        assertFalse(needs("sms.listThreads"))
    }

    @Test
    fun everyUiMethodIsARealMethod() {
        for (m in BackgroundPolicy.UI_METHODS) assertNotNull(m, BridgeCatalog.capabilityFor(m))
    }

    @Test
    fun declaredTriggers() {
        val triggers = listOf("sms", "periodic:30")
        assertTrue(BackgroundPolicy.declares(triggers, "sms"))
        assertTrue(BackgroundPolicy.declares(triggers, RunnerQueue.PERIODIC))
        assertFalse(BackgroundPolicy.declares(triggers, "boot"))
        assertFalse(BackgroundPolicy.declares(listOf("sms"), RunnerQueue.PERIODIC))
        assertFalse(BackgroundPolicy.declares(triggers, "periodic:30"))
        assertEquals(30, BackgroundPolicy.periodicMinutes(triggers))
        assertNull(BackgroundPolicy.periodicMinutes(listOf("sms")))
    }

    @Test
    fun backgroundCapabilityIsMedium() {
        assertEquals("background", Capability.BACKGROUND.id)
        assertEquals(app.deft.catalog.Risk.MEDIUM, Capability.BACKGROUND.risk)
        assertEquals(Capability.BACKGROUND, BridgeCatalog.capabilityFor("background.done"))
    }
}
