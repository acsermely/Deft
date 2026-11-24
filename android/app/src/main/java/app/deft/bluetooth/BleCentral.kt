package app.deft.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BleCentral(
    private val context: Context,
    private val host: BleAdapterHost,
    private val emit: (event: String, payload: String) -> Unit,
) {
    private val gatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val connectWait = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val opWait = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()
    private val mtuWait = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    private val rssiWait = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    private val phyWait = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, Int>>>()
    private val discoverWait = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            emit("bluetooth.bleScanResult", scanJson(result).toString())
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) emit("bluetooth.bleScanResult", scanJson(r).toString())
        }

        override fun onScanFailed(errorCode: Int) {
            emit(
                "bluetooth.bleScanResult",
                buildJsonObject { put("error", JsonPrimitive(errorCode)) }.toString(),
            )
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val id = bleMac(gatt.device.address)
            val connected = newState == BluetoothProfile.STATE_CONNECTED
            if (!connected) {
                gatts.remove(id)
                gatt.close()
            }
            connectWait.remove(id)?.complete(connected && status == BluetoothGatt.GATT_SUCCESS)
            emit(
                "bluetooth.bleConnection",
                buildJsonObject {
                    put("deviceId", JsonPrimitive(id))
                    put("status", JsonPrimitive(status))
                    put("connected", JsonPrimitive(connected))
                }.toString(),
            )
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            discoverWait.remove(bleMac(gatt.device.address))?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            opWait.remove(opKey(gatt, "read"))?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            opWait.remove(opKey(gatt, "read"))?.complete(
                if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null,
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            opWait.remove(opKey(gatt, "write"))?.complete(
                if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null,
            )
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            emitChar(gatt, characteristic, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            emitChar(gatt, characteristic, characteristic.value ?: ByteArray(0))
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray,
        ) {
            opWait.remove(opKey(gatt, "dread"))?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Deprecated in Java")
        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            opWait.remove(opKey(gatt, "dread"))?.complete(
                if (status == BluetoothGatt.GATT_SUCCESS) descriptor.value else null,
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            opWait.remove(opKey(gatt, "dwrite"))?.complete(
                if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null,
            )
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtuWait.remove(bleMac(gatt.device.address))?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else -1)
            emit(
                "bluetooth.bleMtu",
                buildJsonObject {
                    put("deviceId", JsonPrimitive(bleMac(gatt.device.address)))
                    put("mtu", JsonPrimitive(mtu))
                }.toString(),
            )
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            rssiWait.remove(bleMac(gatt.device.address))?.complete(if (status == BluetoothGatt.GATT_SUCCESS) rssi else Int.MIN_VALUE)
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            phyWait.remove(bleMac(gatt.device.address))?.complete(txPhy to rxPhy)
            emit(
                "bluetooth.blePhy",
                buildJsonObject {
                    put("deviceId", JsonPrimitive(bleMac(gatt.device.address)))
                    put("txPhy", JsonPrimitive(txPhy))
                    put("rxPhy", JsonPrimitive(rxPhy))
                }.toString(),
            )
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            onPhyUpdate(gatt, txPhy, rxPhy, status)
        }
    }

    private fun emitChar(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
        emit(
            "bluetooth.bleCharacteristic",
            buildJsonObject {
                put("deviceId", JsonPrimitive(bleMac(gatt.device.address)))
                put("service", JsonPrimitive(ch.service?.uuid?.toString().orEmpty()))
                put("characteristic", JsonPrimitive(ch.uuid.toString()))
                put("base64", JsonPrimitive(b64(value)))
            }.toString(),
        )
    }

    fun startScan(args: JsonObject): String? {
        val scanner = host.scanner() ?: return "scanner unavailable"
        val filters = args.objArray("filters").mapNotNull { filterOf(it) }
        val options = args["options"] as? JsonObject
        val settings = scanSettings(options)
        return try {
            if (filters.isEmpty()) scanner.startScan(null, settings, scanCallback)
            else scanner.startScan(filters, settings, scanCallback)
            scanning = true
            null
        } catch (e: Exception) {
            e.message ?: "scan failed"
        }
    }

    fun stopScan() {
        if (!scanning) return
        runCatching { host.scanner()?.stopScan(scanCallback) }
        scanning = false
    }

    suspend fun connect(address: String, autoConnect: Boolean): Boolean {
        val key = bleMac(address)
        val existing = gatts[key]
        if (existing != null) return true
        val device = host.device(key) ?: return false
        val wait = CompletableDeferred<Boolean>()
        connectWait[key] = wait
        val gatt = device.connectGatt(
            context,
            autoConnect,
            gattCallback,
            android.bluetooth.BluetoothDevice.TRANSPORT_LE,
        )
            ?: return false
        gatts[key] = gatt
        val ok = withTimeoutOrNull(20_000) { wait.await() } == true
        if (!ok) {
            gatts.remove(key)?.let {
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            connectWait.remove(key)
        }
        return ok
    }

    fun disconnect(address: String) {
        gatts.remove(bleMac(address))?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
    }

    fun disconnectAll(addresses: Collection<String>) {
        for (address in addresses) disconnect(address)
    }

    suspend fun discover(address: String): Boolean {
        val gatt = gatts[bleMac(address)] ?: return false
        val wait = CompletableDeferred<Boolean>()
        discoverWait[bleMac(address)] = wait
        if (!gatt.discoverServices()) return false
        return withTimeoutOrNull(15_000) { wait.await() } == true
    }

    fun servicesJson(address: String): JsonObject? {
        val gatt = gatts[bleMac(address)] ?: return null
        return buildJsonObject {
            putJsonArray("services") {
                for (svc in gatt.services.orEmpty()) {
                    add(
                        buildJsonObject {
                            put("uuid", JsonPrimitive(svc.uuid.toString()))
                            put("type", JsonPrimitive(if (svc.type == 0) "primary" else "secondary"))
                            putJsonArray("included") {
                                for (inc in svc.includedServices.orEmpty()) {
                                    add(JsonPrimitive(inc.uuid.toString()))
                                }
                            }
                            putJsonArray("characteristics") {
                                for (ch in svc.characteristics.orEmpty()) {
                                    add(
                                        buildJsonObject {
                                            put("uuid", JsonPrimitive(ch.uuid.toString()))
                                            put("instanceId", JsonPrimitive(ch.instanceId))
                                            put("properties", JsonPrimitive(ch.properties))
                                            put("permissions", JsonPrimitive(ch.permissions))
                                            putJsonArray("descriptors") {
                                                for (d in ch.descriptors.orEmpty()) {
                                                    add(JsonPrimitive(d.uuid.toString()))
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    suspend fun read(address: String, service: String, characteristic: String): ByteArray? {
        val gatt = gatts[bleMac(address)] ?: return null
        val ch = charOf(gatt, service, characteristic) ?: return null
        val wait = CompletableDeferred<ByteArray?>()
        opWait[opKey(gatt, "read")] = wait
        val ok = if (Build.VERSION.SDK_INT >= 33) gatt.readCharacteristic(ch) else @Suppress("DEPRECATION") gatt.readCharacteristic(ch)
        if (!ok) return null
        return withTimeoutOrNull(10_000) { wait.await() }
    }

    suspend fun write(
        address: String,
        service: String,
        characteristic: String,
        value: ByteArray,
        writeType: Int,
    ): Boolean {
        val gatt = gatts[bleMac(address)] ?: return false
        val ch = charOf(gatt, service, characteristic) ?: return false
        val wait = CompletableDeferred<ByteArray?>()
        opWait[opKey(gatt, "write")] = wait
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(ch, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = writeType
            ch.value = value
            gatt.writeCharacteristic(ch)
        }
        if (!ok) return false
        return withTimeoutOrNull(10_000) { wait.await() } != null
    }

    suspend fun readDescriptor(
        address: String,
        service: String,
        characteristic: String,
        descriptor: String,
    ): ByteArray? {
        val gatt = gatts[bleMac(address)] ?: return null
        val d = descOf(gatt, service, characteristic, descriptor) ?: return null
        val wait = CompletableDeferred<ByteArray?>()
        opWait[opKey(gatt, "dread")] = wait
        if (!gatt.readDescriptor(d)) return null
        return withTimeoutOrNull(10_000) { wait.await() }
    }

    suspend fun writeDescriptor(
        address: String,
        service: String,
        characteristic: String,
        descriptor: String,
        value: ByteArray,
    ): Boolean {
        val gatt = gatts[bleMac(address)] ?: return false
        val d = descOf(gatt, service, characteristic, descriptor) ?: return false
        val wait = CompletableDeferred<ByteArray?>()
        opWait[opKey(gatt, "dwrite")] = wait
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            gatt.writeDescriptor(d)
        }
        if (!ok) return false
        return withTimeoutOrNull(10_000) { wait.await() } != null
    }

    suspend fun notify(address: String, service: String, characteristic: String, enable: Boolean): Boolean {
        val gatt = gatts[bleMac(address)] ?: return false
        val ch = charOf(gatt, service, characteristic) ?: return false
        if (!gatt.setCharacteristicNotification(ch, enable)) return false
        val cccd = ch.getDescriptor(CCCD) ?: return true
        val value = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        return writeDescriptor(address, service, characteristic, CCCD.toString(), value)
    }

    suspend fun requestMtu(address: String, mtu: Int): Int? {
        val gatt = gatts[bleMac(address)] ?: return null
        val wait = CompletableDeferred<Int>()
        mtuWait[bleMac(address)] = wait
        if (!gatt.requestMtu(mtu)) return null
        val v = withTimeoutOrNull(10_000) { wait.await() } ?: return null
        return v.takeIf { it > 0 }
    }

    suspend fun readRssi(address: String): Int? {
        val gatt = gatts[bleMac(address)] ?: return null
        val wait = CompletableDeferred<Int>()
        rssiWait[bleMac(address)] = wait
        if (!gatt.readRemoteRssi()) return null
        val v = withTimeoutOrNull(10_000) { wait.await() } ?: return null
        return v.takeIf { it != Int.MIN_VALUE }
    }

    fun connectionPriority(address: String, priority: Int): Boolean {
        val gatt = gatts[bleMac(address)] ?: return false
        return gatt.requestConnectionPriority(priority)
    }

    suspend fun setPhy(address: String, tx: Int, rx: Int, options: Int): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT < 26) return null
        val gatt = gatts[bleMac(address)] ?: return null
        val wait = CompletableDeferred<Pair<Int, Int>>()
        phyWait[bleMac(address)] = wait
        gatt.setPreferredPhy(tx, rx, options)
        return withTimeoutOrNull(10_000) { wait.await() }
    }

    fun refreshCache(address: String): Boolean {
        val gatt = gatts[bleMac(address)] ?: return false
        return runCatching {
            BluetoothGatt::class.java.getMethod("refresh").invoke(gatt) as Boolean
        }.getOrDefault(false)
    }

    fun reliableBegin(address: String): Boolean = gatts[bleMac(address)]?.beginReliableWrite() == true

    fun reliableExecute(address: String): Boolean = gatts[bleMac(address)]?.executeReliableWrite() == true

    fun reliableAbort(address: String) {
        gatts[bleMac(address)]?.abortReliableWrite()
    }

    fun closeAll() {
        stopScan()
        for ((_, g) in gatts) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatts.clear()
    }

    private fun charOf(gatt: BluetoothGatt, service: String, characteristic: String): BluetoothGattCharacteristic? {
        val su = parseUuid(service) ?: return null
        val cu = parseUuid(characteristic) ?: return null
        return gatt.getService(su)?.getCharacteristic(cu)
    }

    private fun descOf(
        gatt: BluetoothGatt,
        service: String,
        characteristic: String,
        descriptor: String,
    ): BluetoothGattDescriptor? {
        val du = parseUuid(descriptor) ?: return null
        return charOf(gatt, service, characteristic)?.getDescriptor(du)
    }

    private fun opKey(gatt: BluetoothGatt, kind: String) = "${bleMac(gatt.device.address)}:$kind"

    private fun filterOf(obj: JsonObject): ScanFilter? {
        val b = ScanFilter.Builder()
        obj.str("name")?.let { b.setDeviceName(it) }
        obj.str("address")?.let { b.setDeviceAddress(it.uppercase()) }
        obj.strArray("services").firstOrNull()?.let { parseUuid(it)?.let { u -> b.setServiceUuid(ParcelUuid(u)) } }
        obj.str("namePrefix")?.let { /* ScanFilter has no prefix; ignore, JS can filter */ }
        val manId = obj.int("manufacturerId")
        val manData = fromB64(obj.str("manufacturerDataBase64"))
        if (manId != null && manData != null) b.setManufacturerData(manId, manData)
        return runCatching { b.build() }.getOrNull()
    }

    private fun scanSettings(options: JsonObject?): ScanSettings {
        val b = ScanSettings.Builder()
        val mode = when (options?.str("scanMode")) {
            "lowPower" -> ScanSettings.SCAN_MODE_LOW_POWER
            "lowLatency" -> ScanSettings.SCAN_MODE_LOW_LATENCY
            "opportunistic" -> ScanSettings.SCAN_MODE_OPPORTUNISTIC
            else -> ScanSettings.SCAN_MODE_BALANCED
        }
        b.setScanMode(mode)
        options?.int("reportDelayMs")?.let { b.setReportDelay(it.toLong()) }
        val cb = when (options?.str("callbackType")) {
            "first" -> ScanSettings.CALLBACK_TYPE_FIRST_MATCH
            "matchLost" -> ScanSettings.CALLBACK_TYPE_MATCH_LOST
            else -> ScanSettings.CALLBACK_TYPE_ALL_MATCHES
        }
        b.setCallbackType(cb)
        if (Build.VERSION.SDK_INT >= 26) {
            b.setLegacy(options?.bool("legacy", true) == true)
            when (options?.str("phy")) {
                "1m" -> b.setPhy(android.bluetooth.BluetoothDevice.PHY_LE_1M)
                "coded" -> b.setPhy(android.bluetooth.BluetoothDevice.PHY_LE_CODED)
                "all" -> b.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            }
        }
        return b.build()
    }

    private fun scanJson(result: ScanResult) = buildJsonObject {
        val d = result.device
        put("deviceId", JsonPrimitive(bleMac(d.address.orEmpty())))
        put("address", JsonPrimitive(bleMac(d.address.orEmpty())))
        put("name", JsonPrimitive(d.name ?: result.scanRecord?.deviceName.orEmpty()))
        put("rssi", JsonPrimitive(result.rssi))
        if (Build.VERSION.SDK_INT >= 26) {
            put("txPower", JsonPrimitive(result.txPower))
            put("isConnectable", JsonPrimitive(result.isConnectable))
            put("primaryPhy", JsonPrimitive(result.primaryPhy))
            put("secondaryPhy", JsonPrimitive(result.secondaryPhy))
        }
        val rec = result.scanRecord
        if (rec != null) {
            putJsonArray("serviceUuids") {
                rec.serviceUuids.orEmpty().forEach { add(JsonPrimitive(it.uuid.toString())) }
            }
            putJsonObject("serviceData") {
                rec.serviceData?.forEach { (uuid, bytes) ->
                    put(uuid.uuid.toString(), JsonPrimitive(b64(bytes)))
                }
            }
            putJsonObject("manufacturerData") {
                val sp = rec.manufacturerSpecificData
                if (sp != null) {
                    for (i in 0 until sp.size()) {
                        put(sp.keyAt(i).toString(), JsonPrimitive(b64(sp.valueAt(i))))
                    }
                }
            }
            rec.bytes?.let { put("advertisementBase64", JsonPrimitive(b64(it))) }
        }
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
