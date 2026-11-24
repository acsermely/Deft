package app.deft.bluetooth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothRadioLockTest {
    @After
    fun resetPublishedOwner() {
        BluetoothRadioLock().clearAll()
    }

    @Test
    fun acquireAndReleaseFlags() {
        val lock = BluetoothRadioLock()
        assertTrue(lock.tryAcquire("a", BluetoothRadioLock.Flag.Scanning, "Mesh"))
        assertEquals("a", lock.ownerId)
        assertEquals("Mesh", lock.ownerName)
        assertTrue(lock.tryAcquire("a", BluetoothRadioLock.Flag.Advertising, "Mesh"))
        lock.dropFlag("a", BluetoothRadioLock.Flag.Scanning)
        assertEquals("a", lock.ownerId)
        lock.dropFlag("a", BluetoothRadioLock.Flag.Advertising)
        assertNull(lock.ownerId)
        assertNull(lock.ownerName)
    }

    @Test
    fun secondAppBlockedWhileFlagsHeld() {
        val lock = BluetoothRadioLock()
        assertTrue(lock.tryAcquire("a", BluetoothRadioLock.Flag.Scanning, "Mesh"))
        assertFalse(lock.tryAcquire("b", BluetoothRadioLock.Flag.Advertising, "Other"))
        assertFalse(lock.canUseRadio("b"))
        assertTrue(lock.canUseRadio("a"))
        lock.dropFlag("b", BluetoothRadioLock.Flag.Scanning)
        assertEquals("a", lock.ownerId)
        lock.dropFlag("a", BluetoothRadioLock.Flag.Scanning)
        assertTrue(lock.tryAcquire("b", BluetoothRadioLock.Flag.Server, "Other"))
        assertEquals("b", lock.ownerId)
    }

    @Test
    fun twoAppsTwoMacsOk() {
        val lock = BluetoothRadioLock()
        assertTrue(lock.claimGatt("AA:AA", "a"))
        assertTrue(lock.claimGatt("BB:BB", "b"))
        assertEquals("a", lock.gattOwner("AA:AA"))
        assertEquals("b", lock.gattOwner("BB:BB"))
    }

    @Test
    fun sameMacConflict() {
        val lock = BluetoothRadioLock()
        assertTrue(lock.claimGatt("AA:AA", "a"))
        assertFalse(lock.claimGatt("AA:AA", "b"))
        assertTrue(lock.claimGatt("AA:AA", "a"))
    }

    @Test
    fun macCaseNormalized() {
        val lock = BluetoothRadioLock()
        assertTrue(lock.claimGatt("aa:bb:cc:dd:ee:ff", "a"))
        assertFalse(lock.claimGatt("AA:BB:CC:DD:EE:FF", "b"))
        assertEquals("a", lock.gattOwner("aa:bb:cc:dd:ee:ff"))
        assertEquals(setOf("AA:BB:CC:DD:EE:FF"), lock.gattDevicesOf("a"))
    }

    @Test
    fun releaseOneAppLeavesOtherGatt() {
        val lock = BluetoothRadioLock()
        assertTrue(lock.tryAcquire("a", BluetoothRadioLock.Flag.Scanning, "Mesh"))
        assertTrue(lock.claimGatt("AA:AA", "a"))
        assertTrue(lock.claimGatt("BB:BB", "b"))
        val released = lock.releaseApp("a")
        assertTrue(released.heldRadio)
        assertEquals(setOf("AA:AA"), released.gattDevices)
        assertNull(lock.ownerId)
        assertNull(lock.gattOwner("AA:AA"))
        assertEquals("b", lock.gattOwner("BB:BB"))
    }
}
