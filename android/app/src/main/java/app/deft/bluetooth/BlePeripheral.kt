package app.deft.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.Build
import android.os.DeadObjectException
import android.os.ParcelUuid
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission")
class BlePeripheral(
    private val context: Context,
    private val host: BleAdapterHost,
    private val emit: (event: String, payload: String) -> Unit,
) {
    private var server: BluetoothGattServer? = null
    private val pending = ConcurrentHashMap<String, Pending>()
    private val seq = AtomicInteger(1)
    private var advertising = false
    private var advertiseSet: AdvertisingSet? = null
    @Volatile private var addWait: CompletableDeferred<Int>? = null

    data class Pending(
        val device: BluetoothDevice,
        val androidRequestId: Int,
        val wait: CompletableDeferred<Pair<Int, ByteArray?>>,
    )

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            emit(
                "bluetooth.bleServerConnection",
                buildJsonObject {
                    put("deviceId", JsonPrimitive(bleMac(device.address.orEmpty())))
                    put("connected", JsonPrimitive(newState == BluetoothProfile.STATE_CONNECTED))
                    put("status", JsonPrimitive(status))
                }.toString(),
            )
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            addWait?.complete(status)
            emit(
                "bluetooth.bleServerService",
                buildJsonObject {
                    put("uuid", JsonPrimitive(service.uuid.toString()))
                    put("status", JsonPrimitive(status))
                    put("ok", JsonPrimitive(status == BluetoothGatt.GATT_SUCCESS))
                }.toString(),
            )
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // Answer immediately. Mesh (and most samples) never call bleServerRespond for
            // reads; leaving the request open makes CoreBluetooth drop during startNotify.
            val body = sliceGatt(@Suppress("DEPRECATION") characteristic.value, offset)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, body)
            emit(
                "bluetooth.bleServerReadRequest",
                buildJsonObject {
                    put("deviceId", JsonPrimitive(bleMac(device.address.orEmpty())))
                    put("service", JsonPrimitive(characteristic.service?.uuid?.toString().orEmpty()))
                    put("characteristic", JsonPrimitive(characteristic.uuid.toString()))
                    put("offset", JsonPrimitive(offset))
                    put("auto", JsonPrimitive(true))
                }.toString(),
            )
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val id = nextId(device, requestId)
            if (value != null) {
                @Suppress("DEPRECATION")
                characteristic.value = value
            }
            if (responseNeeded) {
                val wait = CompletableDeferred<Pair<Int, ByteArray?>>()
                pending[id] = Pending(device, requestId, wait)
            }
            emit(
                "bluetooth.bleServerWriteRequest",
                buildJsonObject {
                    put("requestId", JsonPrimitive(id))
                    put("deviceId", JsonPrimitive(bleMac(device.address.orEmpty())))
                    put("service", JsonPrimitive(characteristic.service?.uuid?.toString().orEmpty()))
                    put("characteristic", JsonPrimitive(characteristic.uuid.toString()))
                    put("offset", JsonPrimitive(offset))
                    put("base64", JsonPrimitive(b64(value)))
                    put("prepared", JsonPrimitive(preparedWrite))
                    put("responseNeeded", JsonPrimitive(responseNeeded))
                }.toString(),
            )
            if (!responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val body = sliceGatt(@Suppress("DEPRECATION") descriptor.value, offset)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, body)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (value != null) {
                @Suppress("DEPRECATION")
                descriptor.value = value
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            emit("bluetooth.bleAdvertise", buildJsonObject { put("started", JsonPrimitive(true)) }.toString())
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            emit(
                "bluetooth.bleAdvertise",
                buildJsonObject {
                    put("started", JsonPrimitive(false))
                    put("error", JsonPrimitive(errorCode))
                }.toString(),
            )
        }
    }

    private val setCallback = if (Build.VERSION.SDK_INT >= 26) {
        object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                advertiseSet = advertisingSet
                advertising = status == ADVERTISE_SUCCESS
                emit(
                    "bluetooth.bleAdvertise",
                    buildJsonObject {
                        put("started", JsonPrimitive(advertising))
                        if (!advertising) put("error", JsonPrimitive(status))
                    }.toString(),
                )
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                advertising = false
                advertiseSet = null
            }
        }
    } else {
        null
    }

    fun ensureServer(): BluetoothGattServer? {
        if (server != null) return server
        return reopenServer()
    }

    private fun reopenServer(): BluetoothGattServer? {
        runCatching { server?.close() }
        server = null
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        server = runCatching { mgr.openGattServer(context, serverCallback) }.getOrNull()
        return server
    }

    /**
     * [BluetoothGattServer.addService] returns false until the server has finished
     * registering after [openGattServer]. Retry, then wait for [onServiceAdded].
     * The emulator's `IBluetoothGatt` binder is typically dead — fail fast there.
     * @return error message, or null on success
     */
    suspend fun addService(args: JsonObject): String? {
        val adapter = host.adapter()
        if (adapter == null || !adapter.isEnabled) return "Bluetooth is off"
        if (host.isEmulator()) {
            return "BLE GATT server is not available on the Android emulator " +
                "(IBluetoothGatt is dead). Mesh needs two physical phones."
        }
        var s = ensureServer() ?: return "GATT server unavailable"
        val uuid = parseUuid(args.str("uuid") ?: return "uuid required") ?: return "invalid uuid"
        if (s.getService(uuid) != null) return null
        var reopened = false
        repeat(20) {
            if (s.getService(uuid) != null) return null
            val svc = buildService(args, uuid) ?: return "invalid characteristic list"
            val wait = CompletableDeferred<Int>()
            addWait = wait
            val queued = try {
                s.addService(svc)
            } catch (e: RemoteException) {
                addWait = null
                if (!reopened && e is DeadObjectException) {
                    reopened = true
                    s = reopenServer() ?: return "GATT server died (IBluetoothGatt). Use a physical phone."
                    delay(200)
                    return@repeat
                }
                return "GATT server died: ${e.message ?: e.javaClass.simpleName}"
            }
            if (queued) {
                val status = withTimeoutOrNull(5_000) { wait.await() }
                addWait = null
                if (status == null) return "addService timed out"
                if (status != BluetoothGatt.GATT_SUCCESS) return "addService status $status"
                return null
            }
            addWait = null
            delay(100)
        }
        return "addService failed (GATT server not ready)"
    }

    private fun buildService(args: JsonObject, uuid: UUID): BluetoothGattService? {
        val type = if (args.str("type") == "secondary") {
            BluetoothGattService.SERVICE_TYPE_SECONDARY
        } else {
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        }
        val svc = BluetoothGattService(uuid, type)
        val chars = args["characteristics"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
        var added = 0
        for (el in chars) {
            val o = el as? JsonObject ?: continue
            val cu = parseUuid(o.str("uuid") ?: continue) ?: continue
            val props = propsOf(o.strArray("properties"))
            val perms = permsOf(o.strArray("permissions"))
            val ch = BluetoothGattCharacteristic(cu, props, perms)
            o.str("valueBase64")?.let { ch.value = fromB64(it) }
            for (du in o.strArray("descriptors")) {
                parseUuid(du)?.let { ch.addDescriptor(BluetoothGattDescriptor(it, perms)) }
            }
            if (props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                if (ch.getDescriptor(BleCentral.CCCD) == null) {
                    ch.addDescriptor(
                        BluetoothGattDescriptor(
                            BleCentral.CCCD,
                            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                        ),
                    )
                }
            }
            if (!svc.addCharacteristic(ch)) return null
            added++
        }
        if (added == 0) return null
        return svc
    }

    fun removeService(uuid: String): Boolean {
        val s = server ?: return false
        val u = parseUuid(uuid) ?: return false
        val svc = s.getService(u) ?: return false
        return s.removeService(svc)
    }

    fun clearServices() {
        server?.clearServices()
    }

    fun hasServices(): Boolean = server?.services?.isNotEmpty() == true

    fun isAdvertising(): Boolean = advertising

    fun notify(deviceId: String, service: String, characteristic: String, value: ByteArray, indicate: Boolean): Boolean {
        val s = server ?: return false
        val su = parseUuid(service) ?: return false
        val cu = parseUuid(characteristic) ?: return false
        val ch = s.getService(su)?.getCharacteristic(cu) ?: return false
        val device = host.device(bleMac(deviceId)) ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            s.notifyCharacteristicChanged(device, ch, indicate, value) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = value
            s.notifyCharacteristicChanged(device, ch, indicate)
        }
    }

    fun respond(jsRequestId: String, status: Int, value: ByteArray?): Boolean {
        val p = pending.remove(jsRequestId) ?: return false
        val s = server ?: return false
        s.sendResponse(p.device, p.androidRequestId, status, 0, value)
        p.wait.complete(status to value)
        return true
    }

    fun startAdvertise(args: JsonObject): String? {
        val advertiser = host.advertiser() ?: return "advertiser unavailable"
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                when (args.str("mode")) {
                    "lowPower" -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                    "lowLatency" -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                    else -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
                },
            )
            .setTxPowerLevel(
                when (args.str("txPower")) {
                    "ultraLow" -> AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW
                    "low" -> AdvertiseSettings.ADVERTISE_TX_POWER_LOW
                    "high" -> AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                    else -> AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
                },
            )
            .setConnectable(args.bool("connectable", true))
            .setTimeout(args.int("timeoutMs") ?: 0)
            .build()
        val data = advertiseData(args)
        val legacy = args.bool("legacy", true)
        return try {
            if (!legacy && Build.VERSION.SDK_INT >= 26 && setCallback != null) {
                val params = AdvertisingSetParameters.Builder()
                    .setLegacyMode(false)
                    .setConnectable(args.bool("connectable", true))
                    .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                    .apply {
                        when (args.str("primaryPhy")) {
                            "coded" -> setPrimaryPhy(android.bluetooth.BluetoothDevice.PHY_LE_CODED)
                            else -> setPrimaryPhy(android.bluetooth.BluetoothDevice.PHY_LE_1M)
                        }
                        when (args.str("secondaryPhy")) {
                            "2m" -> setSecondaryPhy(android.bluetooth.BluetoothDevice.PHY_LE_2M)
                            "coded" -> setSecondaryPhy(android.bluetooth.BluetoothDevice.PHY_LE_CODED)
                            else -> setSecondaryPhy(android.bluetooth.BluetoothDevice.PHY_LE_1M)
                        }
                    }
                    .build()
                advertiser.startAdvertisingSet(params, data, null, null, null, setCallback)
            } else {
                advertiser.startAdvertising(settings, data, advertiseCallback)
            }
            null
        } catch (e: Exception) {
            e.message ?: "advertise failed"
        }
    }

    fun stopAdvertise() {
        val advertiser = host.advertiser() ?: return
        runCatching { advertiser.stopAdvertising(advertiseCallback) }
        if (Build.VERSION.SDK_INT >= 26) {
            advertiseSet?.let { runCatching { advertiser.stopAdvertisingSet(setCallback) } }
        }
        advertising = false
        advertiseSet = null
    }

    fun close() {
        stopAdvertise()
        runCatching { server?.close() }
        server = null
        pending.clear()
    }

    private fun advertiseData(args: JsonObject): AdvertiseData {
        val b = AdvertiseData.Builder()
            .setIncludeDeviceName(args.bool("includeDeviceName", false))
            .setIncludeTxPowerLevel(args.bool("includeTxPower", false))
        for (u in args.strArray("serviceUuids")) {
            parseUuid(u)?.let { b.addServiceUuid(ParcelUuid(it)) }
        }
        val sd = args["serviceData"] as? JsonObject
        sd?.keys?.forEach { key ->
            val bytes = fromB64(sd.str(key)) ?: return@forEach
            parseUuid(key)?.let { b.addServiceData(ParcelUuid(it), bytes) }
        }
        val manId = args.int("manufacturerId")
        val man = fromB64(args.str("manufacturerDataBase64"))
        if (manId != null && man != null) b.addManufacturerData(manId, man)
        return b.build()
    }

    private fun nextId(device: BluetoothDevice, androidRequestId: Int): String =
        "${bleMac(device.address.orEmpty())}:$androidRequestId:${seq.getAndIncrement()}"

    private fun sliceGatt(value: ByteArray?, offset: Int): ByteArray {
        val v = value ?: ByteArray(0)
        if (offset <= 0) return v
        if (offset >= v.size) return ByteArray(0)
        return v.copyOfRange(offset, v.size)
    }

    private fun propsOf(names: List<String>): Int {
        var p = 0
        for (n in names) {
            p = p or when (n) {
                "broadcast" -> BluetoothGattCharacteristic.PROPERTY_BROADCAST
                "read" -> BluetoothGattCharacteristic.PROPERTY_READ
                "write" -> BluetoothGattCharacteristic.PROPERTY_WRITE
                "writeNoResponse" -> BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                "notify" -> BluetoothGattCharacteristic.PROPERTY_NOTIFY
                "indicate" -> BluetoothGattCharacteristic.PROPERTY_INDICATE
                "signed" -> BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE
                "extended" -> BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS
                else -> 0
            }
        }
        if (p == 0) p = BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY
        return p
    }

    private fun permsOf(names: List<String>): Int {
        var p = 0
        for (n in names) {
            p = p or when (n) {
                "read" -> BluetoothGattCharacteristic.PERMISSION_READ
                "write" -> BluetoothGattCharacteristic.PERMISSION_WRITE
                "readEncrypted" -> BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
                "writeEncrypted" -> BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
                else -> 0
            }
        }
        if (p == 0) p = BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        return p
    }
}
