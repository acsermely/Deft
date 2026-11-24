package app.deft.bluetooth

/** Pure event routing for BLE isolation. No Android types. */
object BluetoothEventRouter {
    fun flagToDrop(event: String, scanError: Boolean, advertiseStarted: Boolean?): BluetoothRadioLock.Flag? {
        if (event == "bluetooth.bleScanResult" && scanError) return BluetoothRadioLock.Flag.Scanning
        if (event == "bluetooth.bleAdvertise" && advertiseStarted == false) {
            return BluetoothRadioLock.Flag.Advertising
        }
        return null
    }

    fun targets(
        event: String,
        deviceId: String?,
        radioOwnerId: String?,
        gattOwner: String?,
        interested: Set<String>,
    ): Set<String> {
        return when (event) {
            "bluetooth.bleScanResult",
            "bluetooth.bleAdvertise",
            "bluetooth.bleServerConnection",
            "bluetooth.bleServerService",
            "bluetooth.bleServerReadRequest",
            "bluetooth.bleServerWriteRequest",
            -> radioOwnerId?.let { setOf(it) } ?: emptySet()
            "bluetooth.bleConnection",
            "bluetooth.bleCharacteristic",
            "bluetooth.bleMtu",
            "bluetooth.blePhy",
            -> gattOwner?.let { setOf(it) } ?: emptySet()
            "bluetooth.bleBond" -> setOfNotNull(radioOwnerId, gattOwner)
            "bluetooth.bleAdapter" -> interested
            else -> radioOwnerId?.let { setOf(it) } ?: emptySet()
        }
    }

    fun bondedDeviceIdsFor(
        requesterId: String,
        radioOwnerId: String?,
        requesterGattMacs: Set<String>,
        allBondedMacs: Set<String>,
    ): Set<String> {
        if (radioOwnerId == requesterId) return allBondedMacs
        return allBondedMacs.intersect(requesterGattMacs)
    }

    fun mayBondMac(
        requesterId: String,
        radioOwnerId: String?,
        gattOwner: String?,
    ): Boolean = radioOwnerId == requesterId || gattOwner == requesterId
}
