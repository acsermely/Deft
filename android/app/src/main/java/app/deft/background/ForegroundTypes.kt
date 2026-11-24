package app.deft.background

import android.Manifest
import android.content.pm.ServiceInfo
import app.deft.catalog.Capability

/**
 * PARITY D3: the kinds of work an app may keep running with `fgs.start`, what each needs and how
 * Android calls it. Pure, unit tested.
 */
enum class FgsType(
    val id: String,
    /** The `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` bit passed to `startForeground`. */
    val serviceType: Int,
    /** The app needs at least one of these grants besides `foreground`; empty means none. */
    val anyOf: Set<Capability>,
    /** Android runtime permissions the launcher must hold before Android 14 lets this type start. */
    val osPermissions: List<String>,
) {
    LOCATION(
        "location",
        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        setOf(Capability.LOCATION),
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    ),
    MEDIA_PLAYBACK("mediaPlayback", ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, emptySet(), emptyList()),
    MICROPHONE(
        "microphone",
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        setOf(Capability.MICROPHONE),
        listOf(Manifest.permission.RECORD_AUDIO),
    ),
    CONNECTED_DEVICE(
        "connectedDevice",
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        setOf(
            Capability.BLUETOOTH_SERIAL, Capability.BLUETOOTH_BLE_CENTRAL, Capability.BLUETOOTH_BLE_PERIPHERAL,
            Capability.USB, Capability.NFC_READ, Capability.NFC_WRITE, Capability.NFC_TECH, Capability.NFC_HCE,
        ),
        emptyList(),
    ),
    DATA_SYNC("dataSync", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, emptySet(), emptyList());

    /** True when [granted] (the app's granted capabilities) satisfy this type. */
    fun allowedWith(granted: Set<Capability>): Boolean = anyOf.isEmpty() || anyOf.any { it in granted }

    /**
     * Location and microphone count as "while in use" types: Android 14 starts them only while the
     * launcher is in front, and fails on a missing runtime permission.
     */
    val whileInUse: Boolean get() = osPermissions.isNotEmpty()

    companion object {
        fun fromId(id: String?): FgsType? = entries.firstOrNull { it.id == id }

        /** The service type bits for every running app's type together. */
        fun union(types: Collection<FgsType>): Int = types.fold(0) { acc, t -> acc or t.serviceType }

        /** Grants whose loss ends a running [type]: `foreground` itself, or the last of [FgsType.anyOf]. */
        fun stillAllowed(type: FgsType, granted: Set<Capability>): Boolean =
            Capability.FOREGROUND in granted && type.allowedWith(granted)

        /** Longest title and text, in characters. */
        const val TITLE_MAX = 80
        const val TEXT_MAX = 200
    }
}
