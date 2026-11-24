package app.deft.background

import android.content.pm.ServiceInfo
import app.deft.catalog.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PARITY D3: each `fgs.start` type needs its own grant and maps to the right service type. */
class FgsTypeTest {
    @Test
    fun idsParse() {
        assertEquals(FgsType.LOCATION, FgsType.fromId("location"))
        assertEquals(FgsType.MEDIA_PLAYBACK, FgsType.fromId("mediaPlayback"))
        assertEquals(FgsType.MICROPHONE, FgsType.fromId("microphone"))
        assertEquals(FgsType.CONNECTED_DEVICE, FgsType.fromId("connectedDevice"))
        assertEquals(FgsType.DATA_SYNC, FgsType.fromId("dataSync"))
        assertNull(FgsType.fromId("camera"))
        assertNull(FgsType.fromId("specialUse"))
        assertNull(FgsType.fromId("Location"))
        assertNull(FgsType.fromId(""))
        assertNull(FgsType.fromId(null))
    }

    @Test
    fun serviceTypes() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, FgsType.LOCATION.serviceType)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, FgsType.MEDIA_PLAYBACK.serviceType)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, FgsType.MICROPHONE.serviceType)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, FgsType.CONNECTED_DEVICE.serviceType)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, FgsType.DATA_SYNC.serviceType)
        // Distinct bits, so the union can be split again.
        assertEquals(FgsType.entries.size, FgsType.entries.map { it.serviceType }.toSet().size)
        for (t in FgsType.entries) assertEquals(1, Integer.bitCount(t.serviceType))
    }

    @Test
    fun unionCombinesBits() {
        assertEquals(0, FgsType.union(emptyList()))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            FgsType.union(listOf(FgsType.LOCATION, FgsType.DATA_SYNC, FgsType.LOCATION)),
        )
    }

    @Test
    fun locationNeedsLocation() {
        assertFalse(FgsType.LOCATION.allowedWith(emptySet()))
        assertFalse(FgsType.LOCATION.allowedWith(setOf(Capability.MICROPHONE, Capability.LOCATION_BACKGROUND)))
        assertTrue(FgsType.LOCATION.allowedWith(setOf(Capability.LOCATION)))
    }

    @Test
    fun microphoneNeedsMicrophone() {
        assertFalse(FgsType.MICROPHONE.allowedWith(setOf(Capability.SPEECH_RECOGNIZE, Capability.CAMERA)))
        assertTrue(FgsType.MICROPHONE.allowedWith(setOf(Capability.MICROPHONE)))
    }

    @Test
    fun connectedDeviceNeedsADeviceCap() {
        assertFalse(FgsType.CONNECTED_DEVICE.allowedWith(setOf(Capability.LOCATION, Capability.NET_LOCAL)))
        for (cap in listOf(
            Capability.BLUETOOTH_SERIAL, Capability.BLUETOOTH_BLE_CENTRAL, Capability.BLUETOOTH_BLE_PERIPHERAL,
            Capability.USB, Capability.NFC_READ, Capability.NFC_WRITE, Capability.NFC_TECH, Capability.NFC_HCE,
        )) {
            assertTrue(cap.id, FgsType.CONNECTED_DEVICE.allowedWith(setOf(cap)))
        }
    }

    @Test
    fun mediaAndSyncNeedNothingExtra() {
        assertTrue(FgsType.MEDIA_PLAYBACK.allowedWith(emptySet()))
        assertTrue(FgsType.DATA_SYNC.allowedWith(emptySet()))
    }

    @Test
    fun whileInUseTypesNeedAndroidPermissions() {
        assertTrue(FgsType.LOCATION.whileInUse)
        assertTrue(FgsType.MICROPHONE.whileInUse)
        assertFalse(FgsType.MEDIA_PLAYBACK.whileInUse)
        assertFalse(FgsType.CONNECTED_DEVICE.whileInUse)
        assertFalse(FgsType.DATA_SYNC.whileInUse)
    }

    @Test
    fun revokingEndsTheService() {
        val all = setOf(Capability.FOREGROUND, Capability.LOCATION, Capability.USB, Capability.BLUETOOTH_SERIAL)
        assertTrue(FgsType.stillAllowed(FgsType.LOCATION, all))
        assertFalse(FgsType.stillAllowed(FgsType.LOCATION, all - Capability.FOREGROUND))
        assertFalse(FgsType.stillAllowed(FgsType.LOCATION, all - Capability.LOCATION))
        assertFalse(FgsType.stillAllowed(FgsType.DATA_SYNC, all - Capability.FOREGROUND))
        assertTrue(FgsType.stillAllowed(FgsType.DATA_SYNC, setOf(Capability.FOREGROUND)))
        // connectedDevice ends only when the last device grant goes.
        assertTrue(FgsType.stillAllowed(FgsType.CONNECTED_DEVICE, all - Capability.USB))
        assertFalse(FgsType.stillAllowed(FgsType.CONNECTED_DEVICE, all - Capability.USB - Capability.BLUETOOTH_SERIAL))
    }
}
