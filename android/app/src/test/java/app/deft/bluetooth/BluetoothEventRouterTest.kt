package app.deft.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothEventRouterTest {
    @Test
    fun dropsScanFlagOnError() {
        assertEquals(
            BluetoothRadioLock.Flag.Scanning,
            BluetoothEventRouter.flagToDrop("bluetooth.bleScanResult", scanError = true, advertiseStarted = null),
        )
        assertNull(
            BluetoothEventRouter.flagToDrop("bluetooth.bleScanResult", scanError = false, advertiseStarted = null),
        )
    }

    @Test
    fun dropsAdvertiseFlagOnStartFailure() {
        assertEquals(
            BluetoothRadioLock.Flag.Advertising,
            BluetoothEventRouter.flagToDrop("bluetooth.bleAdvertise", scanError = false, advertiseStarted = false),
        )
        assertNull(
            BluetoothEventRouter.flagToDrop("bluetooth.bleAdvertise", scanError = false, advertiseStarted = true),
        )
    }

    @Test
    fun bondWhenFreeIsDenied() {
        assertFalse(BluetoothEventRouter.mayBondMac("b", radioOwnerId = null, gattOwner = null))
        assertTrue(BluetoothEventRouter.mayBondMac("a", radioOwnerId = "a", gattOwner = null))
        assertTrue(BluetoothEventRouter.mayBondMac("b", radioOwnerId = "a", gattOwner = "b"))
        assertFalse(BluetoothEventRouter.mayBondMac("c", radioOwnerId = "a", gattOwner = "b"))
    }

    @Test
    fun bondedListFilteredWhenNotRadioOwner() {
        val all = setOf("AA:AA", "BB:BB")
        assertEquals(
            all,
            BluetoothEventRouter.bondedDeviceIdsFor("mesh", "mesh", emptySet(), all),
        )
        assertEquals(
            setOf("BB:BB"),
            BluetoothEventRouter.bondedDeviceIdsFor("hr", "mesh", setOf("BB:BB"), all),
        )
        assertEquals(
            emptySet<String>(),
            BluetoothEventRouter.bondedDeviceIdsFor("other", null, emptySet(), all),
        )
    }

    @Test
    fun scanTargetsRadioOwnerOnly() {
        assertEquals(
            setOf("mesh"),
            BluetoothEventRouter.targets(
                "bluetooth.bleScanResult",
                deviceId = "AA:AA",
                radioOwnerId = "mesh",
                gattOwner = "hr",
                interested = setOf("mesh", "hr"),
            ),
        )
        assertEquals(
            setOf("hr"),
            BluetoothEventRouter.targets(
                "bluetooth.bleCharacteristic",
                deviceId = "AA:AA",
                radioOwnerId = "mesh",
                gattOwner = "hr",
                interested = setOf("mesh", "hr"),
            ),
        )
    }
}
