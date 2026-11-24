package app.deft.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One origin may scan, advertise, or run a GATT server at a time.
 * GATT client connections are per MAC and may belong to different origins.
 */
class BluetoothRadioLock {
    enum class Flag { Scanning, Advertising, Server }

    @Volatile
    var ownerId: String? = null
        private set

    @Volatile
    var ownerName: String? = null
        private set

    private val flags = mutableSetOf<Flag>()
    private val gattOwners = LinkedHashMap<String, String>()
    private val ownerState = MutableStateFlow<Pair<String, String>?>(null)

    val owner: StateFlow<Pair<String, String>?> = ownerState.asStateFlow()

    @Synchronized
    fun tryAcquire(webAppId: String, flag: Flag, name: String): Boolean {
        if (ownerId == null) {
            ownerId = webAppId
            ownerName = name
            publish()
        }
        if (ownerId != webAppId) return false
        flags.add(flag)
        return true
    }

    @Synchronized
    fun canUseRadio(webAppId: String): Boolean = ownerId == null || ownerId == webAppId

    fun isRadioOwner(webAppId: String): Boolean = ownerId == webAppId

    @Synchronized
    fun dropFlag(webAppId: String, flag: Flag) {
        if (ownerId != webAppId) return
        flags.remove(flag)
        if (flags.isEmpty()) {
            ownerId = null
            ownerName = null
            publish()
        }
    }

    @Synchronized
    fun dropOwnerFlag(flag: Flag) {
        val id = ownerId ?: return
        dropFlag(id, flag)
    }

    @Synchronized
    fun claimGatt(deviceId: String, webAppId: String): Boolean {
        val mac = bleMac(deviceId)
        val existing = gattOwners[mac]
        if (existing == null) {
            gattOwners[mac] = webAppId
            return true
        }
        return existing == webAppId
    }

    @Synchronized
    fun gattOwner(deviceId: String): String? = gattOwners[bleMac(deviceId)]

    @Synchronized
    fun gattDevicesOf(webAppId: String): Set<String> =
        gattOwners.filter { it.value == webAppId }.keys.toSet()

    @Synchronized
    fun releaseGatt(deviceId: String) {
        gattOwners.remove(bleMac(deviceId))
    }

    @Synchronized
    fun releaseApp(webAppId: String): AppRelease {
        val devices = gattOwners.filter { it.value == webAppId }.keys.toSet()
        devices.forEach { gattOwners.remove(it) }
        val held = ownerId == webAppId
        if (held) {
            flags.clear()
            ownerId = null
            ownerName = null
            publish()
        }
        return AppRelease(heldRadio = held, gattDevices = devices)
    }

    @Synchronized
    fun clearAll() {
        flags.clear()
        ownerId = null
        ownerName = null
        gattOwners.clear()
        publish()
    }

    @Synchronized
    fun interestedAppIds(): Set<String> {
        val out = mutableSetOf<String>()
        ownerId?.let { out.add(it) }
        out.addAll(gattOwners.values)
        return out
    }

    private fun publish() {
        val id = ownerId
        val snap = if (id == null) null else id to (ownerName ?: id)
        ownerState.value = snap
        publishedOwner.value = snap
    }

    data class AppRelease(val heldRadio: Boolean, val gattDevices: Set<String>)

    companion object {
        private val publishedOwner = MutableStateFlow<Pair<String, String>?>(null)
        val ownerFlow: StateFlow<Pair<String, String>?> = publishedOwner.asStateFlow()
    }
}
