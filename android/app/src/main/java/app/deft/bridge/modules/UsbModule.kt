package app.deft.bridge.modules

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import app.deft.bridge.BridgeAccessoryPicks
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.BridgeService
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import app.deft.bridge.jsonArg
import app.deft.ui.usb.UsbPermissionActivity
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class UsbModule(
    private val context: Context,
) : BridgeModule {
    private val openConnections = ConcurrentHashMap<String, android.hardware.usb.UsbDeviceConnection>()

    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        return when (request.method) {
            "usb.list" -> list()
            "usb.requestPermission" -> requestPermission(request)
            "usb.open" -> open(request)
            "usb.close" -> close(request)
            "usb.claimInterface" -> claim(request, claim = true)
            "usb.releaseInterface" -> claim(request, claim = false)
            "usb.controlTransfer" -> controlTransfer(request)
            "usb.bulkTransfer" -> endpointTransfer(request, android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK)
            "usb.interruptTransfer" -> endpointTransfer(request, android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_INT)
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private fun list(): BridgeOutcome {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val arr = buildJsonArray {
            for (device in usbManager.deviceList.values) {
                add(deviceJson(device))
            }
        }
        return BridgeOutcome.Ok(arr)
    }

    private suspend fun requestPermission(request: BridgeRequest): BridgeOutcome {
        val deviceName = jsonArg(request.args, "deviceName")
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "deviceName required")
        val granted = awaitPermission(deviceName) ?: return noAnswer()
        return BridgeOutcome.Ok(buildJsonObject { put("granted", JsonPrimitive(granted)) })
    }

    private suspend fun open(request: BridgeRequest): BridgeOutcome {
        val deviceName = jsonArg(request.args, "deviceName")
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "deviceName required")
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "device not attached")
        if (!usbManager.hasPermission(device)) {
            // Review: asking shows a screen; a background run, wallpaper or keyboard page may not.
            if (request.background) {
                return BridgeOutcome.Err(BridgeErrors.NEEDS_FOREGROUND, "Open the app to do this")
            }
            val granted = awaitPermission(deviceName) ?: return noAnswer()
            if (!granted) {
                return BridgeOutcome.Err(BridgeErrors.CANCELLED, "USB permission denied")
            }
        }
        closeOwned(request.webAppId, deviceName)
        if (openConnections.keys.any { it.endsWith("\u0000$deviceName") }) {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "device is open in another web app")
        }
        val connection = usbManager.openDevice(device)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "could not open device")
        openConnections[key(request.webAppId, deviceName)] = connection
        return BridgeOutcome.Ok(buildJsonObject { put("opened", JsonPrimitive(true)) })
    }

    private fun close(request: BridgeRequest): BridgeOutcome {
        val deviceName = jsonArg(request.args, "deviceName")
        if (deviceName != null) {
            closeOwned(request.webAppId, deviceName)
        } else {
            val prefix = request.webAppId + "\u0000"
            for (name in openConnections.keys.filter { it.startsWith(prefix) }) {
                openConnections.remove(name)?.close()
            }
        }
        return BridgeOutcome.Ok()
    }

    // ---- PARITY B1: WebUSB-style transfers on a device this app opened ----

    private val claimed = ConcurrentHashMap<String, MutableSet<Int>>()

    private fun owned(request: BridgeRequest): Pair<UsbDevice, android.hardware.usb.UsbDeviceConnection>? {
        val name = jsonArg(request.args, "deviceName") ?: return null
        val conn = openConnections[key(request.webAppId, name)] ?: return null
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usb.deviceList.values.firstOrNull { it.deviceName == name } ?: return null
        return device to conn
    }

    private fun notOpen() = BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "open the device with usb.open first")

    private fun claim(request: BridgeRequest, claim: Boolean): BridgeOutcome {
        val (device, conn) = owned(request) ?: return notOpen()
        val number = UsbTransfers.int(request.args, "interface", 0..255)
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "interface number required")
        val intf = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.id == number }
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "no interface $number")
        val k = key(request.webAppId, device.deviceName)
        return if (claim) {
            val force = jsonArg(request.args, "force") == "true"
            if (!conn.claimInterface(intf, force)) BridgeOutcome.Err(BridgeErrors.IN_USE, "interface $number is busy")
            else {
                claimed.getOrPut(k) { mutableSetOf() }.add(number)
                BridgeOutcome.Ok()
            }
        } else {
            conn.releaseInterface(intf)
            claimed[k]?.remove(number)
            BridgeOutcome.Ok()
        }
    }

    private suspend fun controlTransfer(request: BridgeRequest): BridgeOutcome {
        val (_, conn) = owned(request) ?: return notOpen()
        val args = request.args
        val requestType = UsbTransfers.int(args, "requestType", 0..255)
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "requestType 0..255 required")
        val req = UsbTransfers.int(args, "request", 0..255)
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "request 0..255 required")
        val value = UsbTransfers.int(args, "value", 0..0xFFFF) ?: 0
        val index = UsbTransfers.int(args, "index", 0..0xFFFF) ?: 0
        val plan = UsbTransfers.plan(args, ::decodeB64).getOrElse { return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, it.message ?: "bad transfer") }
        val inDir = UsbTransfers.isInRequest(requestType)
        if (inDir != (plan is UsbTransfers.Plan.In)) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, if (inDir) "IN request needs length" else "OUT request needs base64")
        }
        val timeout = UsbTransfers.timeout(args)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (plan) {
                is UsbTransfers.Plan.Out -> {
                    val n = conn.controlTransfer(requestType, req, value, index, plan.bytes, plan.bytes.size, timeout)
                    transferResult(n, null)
                }
                is UsbTransfers.Plan.In -> {
                    val buf = ByteArray(plan.length)
                    val n = conn.controlTransfer(requestType, req, value, index, buf, buf.size, timeout)
                    transferResult(n, buf)
                }
            }
        }
    }

    private suspend fun endpointTransfer(request: BridgeRequest, type: Int): BridgeOutcome {
        val (device, conn) = owned(request) ?: return notOpen()
        val address = UsbTransfers.int(request.args, "endpoint", 0..255)
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "endpoint address required")
        val k = key(request.webAppId, device.deviceName)
        val endpoint = (0 until device.interfaceCount).map { device.getInterface(it) }
            .filter { claimed[k]?.contains(it.id) == true }
            .flatMap { intf -> (0 until intf.endpointCount).map { intf.getEndpoint(it) } }
            .firstOrNull { it.address == address }
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "endpoint $address is not on a claimed interface")
        if (endpoint.type != type) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "endpoint $address is not a ${if (type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) "bulk" else "interrupt"} endpoint")
        }
        val plan = UsbTransfers.plan(request.args, ::decodeB64).getOrElse { return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, it.message ?: "bad transfer") }
        val inDir = UsbTransfers.isInEndpoint(address)
        if (inDir != (plan is UsbTransfers.Plan.In)) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, if (inDir) "IN endpoint needs length" else "OUT endpoint needs base64")
        }
        val timeout = UsbTransfers.timeout(request.args)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // bulkTransfer also drives interrupt endpoints on Android.
            when (plan) {
                is UsbTransfers.Plan.Out -> transferResult(conn.bulkTransfer(endpoint, plan.bytes, plan.bytes.size, timeout), null)
                is UsbTransfers.Plan.In -> {
                    val buf = ByteArray(plan.length)
                    transferResult(conn.bulkTransfer(endpoint, buf, buf.size, timeout), buf)
                }
            }
        }
    }

    private fun transferResult(n: Int, inBuf: ByteArray?): BridgeOutcome =
        if (n < 0) {
            BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "transfer failed or timed out")
        } else {
            BridgeOutcome.Ok(
                buildJsonObject {
                    put("transferred", JsonPrimitive(n))
                    if (inBuf != null) {
                        put("base64", JsonPrimitive(android.util.Base64.encodeToString(inBuf, 0, n, android.util.Base64.NO_WRAP)))
                    }
                },
            )
        }

    private fun decodeB64(s: String): ByteArray? = runCatching { android.util.Base64.decode(s, android.util.Base64.DEFAULT) }.getOrNull()

    /** Device unplugged: drop its connections and tell the apps that had it open. */
    fun onDetached(deviceName: String) {
        val suffix = "\u0000$deviceName"
        for (k in openConnections.keys.filter { it.endsWith(suffix) }) {
            openConnections.remove(k)?.let { runCatching { it.close() } }
            claimed.remove(k)
            val webAppId = k.removeSuffix(suffix)
            val payload = buildJsonObject { put("deviceName", JsonPrimitive(deviceName)) }.toString()
            BridgeService.instanceOrNull()?.let { svc ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    svc.emit(webAppId, "usb.disconnected", payload)
                }
            }
        }
    }

    fun closeAll() {
        for ((_, conn) in openConnections) {
            runCatching { conn.close() }
        }
        openConnections.clear()
    }

    private fun closeOwned(webAppId: String, deviceName: String) {
        openConnections.remove(key(webAppId, deviceName))?.close()
        claimed.remove(key(webAppId, deviceName))
    }

    private fun key(webAppId: String, deviceName: String) = "$webAppId\u0000$deviceName"

    private fun noAnswer() = BridgeOutcome.Err(BridgeErrors.CANCELLED, "No answer to the USB permission request")

    /** True or false from the user; null when nobody answered within [PERMISSION_TIMEOUT_MS]. */
    private suspend fun awaitPermission(deviceName: String): Boolean? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
            ?: return false
        if (usbManager.hasPermission(device)) return true
        val id = UUID.randomUUID().toString()
        val deferred = BridgeAccessoryPicks.registerUsbPermission(id)
        val intent = Intent(context, UsbPermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(UsbPermissionActivity.EXTRA_REQUEST_ID, id)
            putExtra(UsbPermissionActivity.EXTRA_DEVICE_NAME, deviceName)
        }
        context.startActivity(intent)
        // Review: a sheet that never shows (or is never answered) must not hold the call forever.
        val answer = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { deferred.await() }
        if (answer == null) BridgeAccessoryPicks.completeUsbPermission(id, false)
        return answer
    }

    private fun deviceJson(device: UsbDevice) = buildJsonObject {
        put("deviceName", JsonPrimitive(device.deviceName))
        put("vendorId", JsonPrimitive(device.vendorId))
        put("productId", JsonPrimitive(device.productId))
        put("deviceClass", JsonPrimitive(device.deviceClass))
        device.productName?.let { put("productName", JsonPrimitive(it)) }
        device.manufacturerName?.let { put("manufacturerName", JsonPrimitive(it)) }
        put(
            "interfaces",
            buildJsonArray {
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    add(
                        buildJsonObject {
                            put("interface", JsonPrimitive(intf.id))
                            put("alternate", JsonPrimitive(intf.alternateSetting))
                            put("class", JsonPrimitive(intf.interfaceClass))
                            put("subclass", JsonPrimitive(intf.interfaceSubclass))
                            put("protocol", JsonPrimitive(intf.interfaceProtocol))
                            put(
                                "endpoints",
                                buildJsonArray {
                                    for (e in 0 until intf.endpointCount) {
                                        val ep = intf.getEndpoint(e)
                                        add(
                                            buildJsonObject {
                                                put("address", JsonPrimitive(ep.address))
                                                put("direction", JsonPrimitive(if (UsbTransfers.isInEndpoint(ep.address)) "in" else "out"))
                                                put(
                                                    "type",
                                                    JsonPrimitive(
                                                        when (ep.type) {
                                                            android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                                                            android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                                                            android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
                                                            else -> "control"
                                                        },
                                                    ),
                                                )
                                                put("packetSize", JsonPrimitive(ep.maxPacketSize))
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }

    private companion object {
        const val PERMISSION_TIMEOUT_MS = 120_000L
    }
}
