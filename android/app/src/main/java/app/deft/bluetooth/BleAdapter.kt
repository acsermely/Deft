package app.deft.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

@SuppressLint("MissingPermission")
class BleAdapterHost(
    private val context: Context,
    private val emit: (event: String, payload: String) -> Unit,
) {
    private var adapterReceiver: BroadcastReceiver? = null
    private var bondReceiver: BroadcastReceiver? = null

    fun adapter(): BluetoothAdapter? {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return mgr.adapter
    }

    fun scanner(): BluetoothLeScanner? = adapter()?.bluetoothLeScanner

    fun advertiser(): BluetoothLeAdvertiser? = adapter()?.bluetoothLeAdvertiser

    fun device(address: String): BluetoothDevice? = runCatching { adapter()?.getRemoteDevice(bleMac(address)) }.getOrNull()

    fun isEmulator(): Boolean {
        val fp = Build.FINGERPRINT
        val hw = Build.HARDWARE
        val product = Build.PRODUCT
        return fp.startsWith("generic") ||
            fp.contains("emulator") ||
            hw.contains("goldfish") ||
            hw.contains("ranchu") ||
            product.contains("sdk") ||
            product.contains("emulator")
    }

    fun stateJson() = buildJsonObject {
        val a = adapter()
        put("available", JsonPrimitive(a != null))
        put("enabled", JsonPrimitive(a?.isEnabled == true))
        put("emulator", JsonPrimitive(isEmulator()))
        put("leSupported", JsonPrimitive(a?.isEnabled == true || a != null))
        put("advertisingSupported", JsonPrimitive(a?.isMultipleAdvertisementSupported == true || a?.bluetoothLeAdvertiser != null))
        put("offloadedFiltering", JsonPrimitive(a?.isOffloadedFilteringSupported == true))
        put("offloadedScanBatching", JsonPrimitive(a?.isOffloadedScanBatchingSupported == true))
        put("multipleAdvertisement", JsonPrimitive(a?.isMultipleAdvertisementSupported == true))
    }

    fun bondedJson() = buildJsonArray {
        val a = adapter() ?: return@buildJsonArray
        for (d in a.bondedDevices.orEmpty()) {
            add(deviceJson(d))
        }
    }

    fun deviceJson(d: BluetoothDevice) = buildJsonObject {
        put("deviceId", JsonPrimitive(bleMac(d.address.orEmpty())))
        put("address", JsonPrimitive(bleMac(d.address.orEmpty())))
        put("name", JsonPrimitive(d.name.orEmpty()))
        if (Build.VERSION.SDK_INT >= 30) {
            put("alias", JsonPrimitive(d.alias.orEmpty()))
        }
        put("bondState", JsonPrimitive(bondName(d.bondState)))
        put("type", JsonPrimitive(typeName(d.type)))
    }

    fun listenAdapter(onDisabled: (() -> Unit)? = null) {
        if (adapterReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    onDisabled?.invoke()
                }
                if (state != BluetoothAdapter.STATE_ON && state != BluetoothAdapter.STATE_OFF) return
                emit(
                    "bluetooth.bleAdapter",
                    buildJsonObject {
                        put("enabled", JsonPrimitive(state == BluetoothAdapter.STATE_ON))
                    }.toString(),
                )
            }
        }
        context.registerReceiver(r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        adapterReceiver = r
    }

    fun listenBonds() {
        if (bondReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                emit(
                    "bluetooth.bleBond",
                    buildJsonObject {
                        put("deviceId", JsonPrimitive(bleMac(device.address.orEmpty())))
                        put("bondState", JsonPrimitive(bondName(state)))
                    }.toString(),
                )
            }
        }
        context.registerReceiver(r, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        bondReceiver = r
    }

    fun createBond(address: String): Boolean = device(address)?.createBond() == true

    fun removeBond(address: String): Boolean {
        val d = device(address) ?: return false
        return runCatching {
            BluetoothDevice::class.java.getMethod("removeBond").invoke(d) as Boolean
        }.getOrDefault(false)
    }

    fun close() {
        adapterReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        bondReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        adapterReceiver = null
        bondReceiver = null
    }

    companion object {
        fun bondName(state: Int) = when (state) {
            BluetoothDevice.BOND_BONDED -> "bonded"
            BluetoothDevice.BOND_BONDING -> "bonding"
            else -> "none"
        }

        fun typeName(type: Int) = when (type) {
            BluetoothDevice.DEVICE_TYPE_LE -> "le"
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
            else -> "unknown"
        }
    }
}
