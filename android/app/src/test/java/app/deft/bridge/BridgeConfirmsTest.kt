package app.deft.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeConfirmsTest {
    @Test
    fun pendingForCountsOnlyTheOwnersConfirms() {
        BridgeConfirms.register("t-a", owner = "app-a")
        BridgeConfirms.register("t-none")
        assertTrue(BridgeConfirms.pendingFor("app-a"))
        assertFalse(BridgeConfirms.pendingFor("app-b"))
        BridgeConfirms.complete("t-a", true)
        assertFalse(BridgeConfirms.pendingFor("app-a"))
        BridgeConfirms.complete("t-none", false)
    }
}
