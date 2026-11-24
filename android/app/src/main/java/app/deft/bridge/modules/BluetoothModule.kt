package app.deft.bridge.modules

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.deft.DeftApp
import app.deft.R
import app.deft.bluetooth.BleAdapterHost
import app.deft.bluetooth.BleCentral
import app.deft.bluetooth.BlePeripheral
import app.deft.bluetooth.BluetoothEventRouter
import app.deft.bluetooth.BluetoothRadioLock
import app.deft.bluetooth.bleMac
import app.deft.bluetooth.bool
import app.deft.bluetooth.fromB64
import app.deft.bluetooth.int
import app.deft.bluetooth.str
import app.deft.bridge.OsPermissions
import app.deft.bridge.BridgeAccessoryPicks
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeJson
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.BridgeService
import app.deft.bridge.jsonArg
import app.deft.ui.bluetooth.BluetoothEnableActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BluetoothModule(
    private val context: Context,
) : BridgeModule {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val macs = ConcurrentHashMap<String, String>()
    private var listenSocket: BluetoothServerSocket? = null
    private var listenJob: Job? = null
    private var listenOwner: String? = null
    private val radioLock = BluetoothRadioLock()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appNames = ConcurrentHashMap<String, String>()

    private val emit: (String, String) -> Unit = { event, payload -> routeEmit(event, payload) }

    private val host = BleAdapterHost(context, emit)
    private val central = BleCentral(context, host, emit)
    private val peripheral = BlePeripheral(context, host, emit)

    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        OsPermissions.ensure(context, request.webAppId, request.background, listOf(connectPermission()), "Bluetooth permission not granted")
            ?.let { return it }
        appNames[request.webAppId] = appName(request.webAppId)
        return when (request.method) {
            "bluetooth.bleState" -> bleState(request)
            "bluetooth.bleEnable" -> enable()
            "bluetooth.bleScanStart" -> withRadio(request, BluetoothRadioLock.Flag.Scanning) { scanStart(request) }
            "bluetooth.bleScanStop" -> stopRadio(request, BluetoothRadioLock.Flag.Scanning) {
                central.stopScan()
            }
            "bluetooth.bleBonded" -> bondedList(request)
            "bluetooth.bleCreateBond" -> bondMac(request, create = true)
            "bluetooth.bleRemoveBond" -> bondMac(request, create = false)
            "bluetooth.bleConnect" -> withGatt(request, claim = true) { connect(request) }
            "bluetooth.bleDisconnect" -> withGatt(request, claim = false) {
                val id = jsonArg(request.args, "deviceId") ?: return@withGatt missing("deviceId")
                central.disconnect(id)
                radioLock.releaseGatt(id)
                BridgeOutcome.Ok()
            }
            "bluetooth.bleDiscover" -> withGatt(request, claim = false) {
                val id = jsonArg(request.args, "deviceId") ?: return@withGatt missing("deviceId")
                if (!central.discover(id)) return@withGatt fail("discover failed")
                BridgeOutcome.Ok()
            }
            "bluetooth.bleServices" -> withGatt(request, claim = false) {
                val id = jsonArg(request.args, "deviceId") ?: return@withGatt missing("deviceId")
                val json = central.servicesJson(id) ?: return@withGatt fail("not connected")
                BridgeOutcome.Ok(json)
            }
            "bluetooth.bleRead" -> withGatt(request, claim = false) { read(request) }
            "bluetooth.bleWrite" -> withGatt(request, claim = false) { write(request) }
            "bluetooth.bleReadDescriptor" -> withGatt(request, claim = false) { readDesc(request) }
            "bluetooth.bleWriteDescriptor" -> withGatt(request, claim = false) { writeDesc(request) }
            "bluetooth.bleNotifyStart" -> withGatt(request, claim = false) { notify(request, true) }
            "bluetooth.bleNotifyStop" -> withGatt(request, claim = false) { notify(request, false) }
            "bluetooth.bleRequestMtu" -> withGatt(request, claim = false) {
                val id = jsonArg(request.args, "deviceId") ?: return@withGatt missing("deviceId")
                val mtu = request.args.int("mtu") ?: 512
                val got = central.requestMtu(id, mtu) ?: return@withGatt fail("mtu failed")
                BridgeOutcome.Ok(buildJsonObject { put("mtu", JsonPrimitive(got)) })
            }
            "bluetooth.bleReadRssi" -> withGatt(request, claim = false) {
                val id = jsonArg(request.args, "deviceId") ?: return@withGatt missing("deviceId")
                val rssi = central.readRssi(id) ?: return@withGatt fail("rssi failed")
                BridgeOutcome.Ok(buildJsonObject { put("rssi", JsonPrimitive(rssi)) })
            }
            "bluetooth.bleConnectionPriority" -> withGatt(request, claim = false) {
                val id = jsonArg(request.args, "deviceId") ?: return@withGatt missing("deviceId")
                val p = when (jsonArg(request.args, "priority")) {
                    "high" -> android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH
                    "lowPower" -> android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
                    else -> android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                }
                if (!central.connectionPriority(id, p)) return@withGatt fail("priority failed")
                BridgeOutcome.Ok()
            }
            "bluetooth.bleSetPhy" -> withGatt(request, claim = false) {
                val id = jsonArg(request.args, "deviceId") ?: return@withGatt missing("deviceId")
                val tx = request.args.int("txPhy") ?: 1
                val rx = request.args.int("rxPhy") ?: 1
                val options = request.args.int("options") ?: 0
                val phy = central.setPhy(id, tx, rx, options) ?: return@withGatt fail("phy failed")
                BridgeOutcome.Ok(
                    buildJsonObject {
                        put("txPhy", JsonPrimitive(phy.first))
                        put("rxPhy", JsonPrimitive(phy.second))
                    },
                )
            }
            "bluetooth.bleRefreshCache" -> withGatt(request, claim = false) {
                val id = jsonArg(request.args, "deviceId") ?: return@withGatt missing("deviceId")
                BridgeOutcome.Ok(buildJsonObject { put("refreshed", JsonPrimitive(central.refreshCache(id))) })
            }
            "bluetooth.bleReliableWriteBegin" -> withGatt(request, claim = false) {
                val id = jsonArg(request.args, "deviceId") ?: return@withGatt missing("deviceId")
                if (!central.reliableBegin(id)) return@withGatt fail("begin failed")
                BridgeOutcome.Ok()
            }
            "bluetooth.bleReliableWriteExecute" -> withGatt(request, claim = false) {
                val id = jsonArg(request.args, "deviceId") ?: return@withGatt missing("deviceId")
                if (!central.reliableExecute(id)) return@withGatt fail("execute failed")
                BridgeOutcome.Ok()
            }
            "bluetooth.bleReliableWriteAbort" -> withGatt(request, claim = false) {
                val id = jsonArg(request.args, "deviceId") ?: return@withGatt missing("deviceId")
                central.reliableAbort(id)
                BridgeOutcome.Ok()
            }
            "bluetooth.bleAdvertiseStart" -> withRadio(request, BluetoothRadioLock.Flag.Advertising) {
                val err = peripheral.startAdvertise(request.args)
                if (err != null) fail(err) else BridgeOutcome.Ok()
            }
            "bluetooth.bleAdvertiseStop" -> stopRadio(request, BluetoothRadioLock.Flag.Advertising) {
                peripheral.stopAdvertise()
            }
            "bluetooth.bleServerAddService" -> withRadio(request, BluetoothRadioLock.Flag.Server) {
                val err = peripheral.addService(request.args)
                if (err != null) return@withRadio fail(err)
                BridgeOutcome.Ok()
            }
            "bluetooth.bleServerRemoveService" -> withRadioOwner(request) {
                val uuid = jsonArg(request.args, "uuid") ?: return@withRadioOwner missing("uuid")
                if (!peripheral.removeService(uuid)) return@withRadioOwner fail("remove failed")
                if (!peripheral.hasServices()) {
                    radioLock.dropFlag(request.webAppId, BluetoothRadioLock.Flag.Server)
                }
                BridgeOutcome.Ok()
            }
            "bluetooth.bleServerClear" -> withRadio(request, BluetoothRadioLock.Flag.Server) {
                peripheral.clearServices()
                BridgeOutcome.Ok()
            }
            "bluetooth.bleServerNotify" -> withRadioOwner(request) { serverNotify(request) }
            "bluetooth.bleServerRespond" -> withRadioOwner(request) {
                val id = jsonArg(request.args, "requestId") ?: return@withRadioOwner missing("requestId")
                val status = request.args.int("status") ?: 0
                val value = fromB64(jsonArg(request.args, "base64"))
                if (!peripheral.respond(id, status, value)) return@withRadioOwner fail("unknown requestId")
                BridgeOutcome.Ok()
            }
            "bluetooth.sppConnect" -> sppConnect(request)
            "bluetooth.sppWrite" -> sppWrite(request)
            "bluetooth.sppClose" -> sppClose(request)
            "bluetooth.sppStatus" -> sppStatus(request)
            "bluetooth.sppListen" -> sppListen(request)
            "bluetooth.sppListenStop" -> sppListenStop(request)
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private fun bleState(request: BridgeRequest): BridgeOutcome {
        val base = host.stateJson()
        return BridgeOutcome.Ok(
            buildJsonObject {
                for ((key, value) in base) put(key, value)
                if (radioLock.ownerId == request.webAppId) {
                    put("radioOwner", JsonPrimitive(true))
                }
            },
        )
    }

    private suspend inline fun withRadio(
        request: BridgeRequest,
        flag: BluetoothRadioLock.Flag,
        block: suspend () -> BridgeOutcome,
    ): BridgeOutcome {
        radioPermission(request)?.let { return it }
        ensureAdapterWatch()
        val name = appName(request.webAppId)
        if (!radioLock.tryAcquire(request.webAppId, flag, name)) {
            return inUse(radioLock.ownerId, radio = true)
        }
        val result = block()
        if (result is BridgeOutcome.Err) {
            if (flag == BluetoothRadioLock.Flag.Server && peripheral.hasServices()) {
                // keep Server if services remain from an earlier add
            } else {
                radioLock.dropFlag(request.webAppId, flag)
            }
        }
        return result
    }

    private suspend inline fun withRadioOwner(
        request: BridgeRequest,
        block: suspend () -> BridgeOutcome,
    ): BridgeOutcome {
        radioPermission(request)?.let { return it }
        ensureAdapterWatch()
        val owner = radioLock.ownerId ?: return fail("radio not held")
        if (owner != request.webAppId) return inUse(owner, radio = true)
        return block()
    }

    private suspend fun stopRadio(
        request: BridgeRequest,
        flag: BluetoothRadioLock.Flag,
        action: () -> Unit,
    ): BridgeOutcome {
        radioPermission(request)?.let { return it }
        ensureAdapterWatch()
        val owner = radioLock.ownerId
        if (owner != null && owner != request.webAppId) return inUse(owner, radio = true)
        action()
        if (owner == request.webAppId) radioLock.dropFlag(request.webAppId, flag)
        return BridgeOutcome.Ok()
    }

    private fun bondedList(request: BridgeRequest): BridgeOutcome {
        ensureAdapterWatch()
        host.listenBonds()
        val all = host.bondedJson()
        if (radioLock.isRadioOwner(request.webAppId)) return BridgeOutcome.Ok(all)
        val allowed = radioLock.gattDevicesOf(request.webAppId)
        return BridgeOutcome.Ok(
            buildJsonArray {
                for (el in all) {
                    val obj = el as? JsonObject ?: continue
                    val id = obj["deviceId"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (bleMac(id) in allowed) add(el)
                }
            },
        )
    }

    private fun bondMac(request: BridgeRequest, create: Boolean): BridgeOutcome {
        val raw = jsonArg(request.args, "deviceId") ?: return missing("deviceId")
        if (!MAC_REGEX.matches(raw)) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "deviceId must match AA:BB:CC:DD:EE:FF")
        }
        val mac = bleMac(raw)
        ensureAdapterWatch()
        host.listenBonds()
        val gattOwner = radioLock.gattOwner(mac)
        if (!BluetoothEventRouter.mayBondMac(request.webAppId, radioLock.ownerId, gattOwner)) {
            return when {
                radioLock.ownerId != null && radioLock.ownerId != request.webAppId ->
                    inUse(radioLock.ownerId, radio = true)
                gattOwner != null -> inUse(gattOwner, radio = false)
                else -> fail("not connected")
            }
        }
        val ok = if (create) host.createBond(mac) else host.removeBond(mac)
        return if (ok) BridgeOutcome.Ok() else fail(if (create) "createBond failed" else "removeBond failed")
    }

    private suspend inline fun withGatt(
        request: BridgeRequest,
        claim: Boolean,
        block: suspend () -> BridgeOutcome,
    ): BridgeOutcome {
        ensureAdapterWatch()
        val id = jsonArg(request.args, "deviceId") ?: return missing("deviceId")
        if (claim) {
            if (!radioLock.claimGatt(id, request.webAppId)) return inUse(radioLock.gattOwner(id), radio = false)
        } else {
            val owner = radioLock.gattOwner(id) ?: return fail("not connected")
            if (owner != request.webAppId) return inUse(owner, radio = false)
        }
        val result = block()
        if (claim && result is BridgeOutcome.Err) radioLock.releaseGatt(id)
        return result
    }

    /** Null when the radio call may run; otherwise the error, after asking for scan or advertise access on screen. */
    private suspend fun radioPermission(request: BridgeRequest): BridgeOutcome? =
        OsPermissions.ensure(
            context,
            request.webAppId, request.background,
            scanAdvertisePermissions(request.method),
            "BLUETOOTH_SCAN/ADVERTISE not granted",
        )

    private fun ensureAdapterWatch() {
        host.listenAdapter {
            central.closeAll()
            peripheral.close()
            radioLock.clearAll()
        }
        host.listenBonds()
    }

    private fun routeEmit(event: String, payload: String) {
        val deviceId = payloadField(payload, "deviceId")?.let { bleMac(it) }
        val scanError = payloadHas(payload, "error")
        val started = payloadBool(payload, "started")
        BluetoothEventRouter.flagToDrop(event, scanError, started)?.let { radioLock.dropOwnerFlag(it) }
        if (event == "bluetooth.bleConnection" && payloadBool(payload, "connected") == false && deviceId != null) {
            val owner = radioLock.gattOwner(deviceId)
            radioLock.releaseGatt(deviceId)
            if (owner != null) dispatch(setOf(owner), event, payload)
            return
        }
        val targets = BluetoothEventRouter.targets(
            event,
            deviceId,
            radioLock.ownerId,
            deviceId?.let { radioLock.gattOwner(it) },
            radioLock.interestedAppIds(),
        )
        if (targets.isNotEmpty()) dispatch(targets, event, payload)
    }

    private fun dispatch(targets: Set<String>, event: String, payload: String) {
        val svc = BridgeService.instanceOrNull() ?: return
        scope.launch {
            for (id in targets) svc.emit(id, event, payload)
        }
    }

    private fun payloadField(payload: String, key: String): String? {
        val obj = runCatching { BridgeJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        return obj[key]?.jsonPrimitive?.contentOrNull
    }

    private fun payloadBool(payload: String, key: String): Boolean? {
        val obj = runCatching { BridgeJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        return obj[key]?.jsonPrimitive?.booleanOrNull
    }

    private fun payloadHas(payload: String, key: String): Boolean {
        val obj = runCatching { BridgeJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return false
        return obj.containsKey(key)
    }

    private suspend fun scanStart(request: BridgeRequest): BridgeOutcome {
        OsPermissions.ensure(
            context,
            request.webAppId, request.background,
            scanAdvertisePermissions("bluetooth.bleScanStart"),
            "BLUETOOTH_SCAN not granted",
        )?.let { return it }
        val err = central.startScan(request.args)
        return if (err != null) fail(err) else BridgeOutcome.Ok()
    }

    private suspend fun connect(request: BridgeRequest): BridgeOutcome {
        val id = jsonArg(request.args, "deviceId") ?: return missing("deviceId")
        val auto = request.args.bool("autoConnect", false)
        return if (central.connect(id, auto)) BridgeOutcome.Ok() else fail("connect failed")
    }

    private suspend fun read(request: BridgeRequest): BridgeOutcome {
        val id = jsonArg(request.args, "deviceId") ?: return missing("deviceId")
        val service = jsonArg(request.args, "service") ?: return missing("service")
        val ch = jsonArg(request.args, "characteristic") ?: return missing("characteristic")
        val bytes = central.read(id, service, ch) ?: return fail("read failed")
        return BridgeOutcome.Ok(buildJsonObject { put("base64", JsonPrimitive(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))) })
    }

    private suspend fun write(request: BridgeRequest): BridgeOutcome {
        val id = jsonArg(request.args, "deviceId") ?: return missing("deviceId")
        val service = jsonArg(request.args, "service") ?: return missing("service")
        val ch = jsonArg(request.args, "characteristic") ?: return missing("characteristic")
        val bytes = fromB64(jsonArg(request.args, "base64")) ?: return missing("base64")
        val type = when (jsonArg(request.args, "writeType")) {
            "noResponse" -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            "signed" -> BluetoothGattCharacteristic.WRITE_TYPE_SIGNED
            else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        return if (central.write(id, service, ch, bytes, type)) BridgeOutcome.Ok() else fail("write failed")
    }

    private suspend fun readDesc(request: BridgeRequest): BridgeOutcome {
        val id = jsonArg(request.args, "deviceId") ?: return missing("deviceId")
        val service = jsonArg(request.args, "service") ?: return missing("service")
        val ch = jsonArg(request.args, "characteristic") ?: return missing("characteristic")
        val d = jsonArg(request.args, "descriptor") ?: return missing("descriptor")
        val bytes = central.readDescriptor(id, service, ch, d) ?: return fail("readDescriptor failed")
        return BridgeOutcome.Ok(buildJsonObject { put("base64", JsonPrimitive(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))) })
    }

    private suspend fun writeDesc(request: BridgeRequest): BridgeOutcome {
        val id = jsonArg(request.args, "deviceId") ?: return missing("deviceId")
        val service = jsonArg(request.args, "service") ?: return missing("service")
        val ch = jsonArg(request.args, "characteristic") ?: return missing("characteristic")
        val d = jsonArg(request.args, "descriptor") ?: return missing("descriptor")
        val bytes = fromB64(jsonArg(request.args, "base64")) ?: return missing("base64")
        return if (central.writeDescriptor(id, service, ch, d, bytes)) BridgeOutcome.Ok() else fail("writeDescriptor failed")
    }

    private suspend fun notify(request: BridgeRequest, enable: Boolean): BridgeOutcome {
        val id = jsonArg(request.args, "deviceId") ?: return missing("deviceId")
        val service = jsonArg(request.args, "service") ?: return missing("service")
        val ch = jsonArg(request.args, "characteristic") ?: return missing("characteristic")
        return if (central.notify(id, service, ch, enable)) BridgeOutcome.Ok() else fail("notify failed")
    }

    private fun serverNotify(request: BridgeRequest): BridgeOutcome {
        val id = jsonArg(request.args, "deviceId") ?: return missing("deviceId")
        val service = jsonArg(request.args, "service") ?: return missing("service")
        val ch = jsonArg(request.args, "characteristic") ?: return missing("characteristic")
        val bytes = fromB64(jsonArg(request.args, "base64")) ?: return missing("base64")
        val indicate = jsonArg(request.args, "confirm") == "indicate"
        return if (peripheral.notify(id, service, ch, bytes, indicate)) BridgeOutcome.Ok() else fail("serverNotify failed")
    }

    private suspend fun enable(): BridgeOutcome {
        val a = host.adapter() ?: return fail("Bluetooth not available")
        if (a.isEnabled) return BridgeOutcome.Ok(buildJsonObject { put("enabled", JsonPrimitive(true)) })
        val id = UUID.randomUUID().toString()
        val deferred = BridgeAccessoryPicks.registerBleEnable(id)
        val intent = Intent(context, BluetoothEnableActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(BluetoothEnableActivity.EXTRA_REQUEST_ID, id)
        }
        context.startActivity(intent)
        val ok = deferred.await()
        return BridgeOutcome.Ok(buildJsonObject { put("enabled", JsonPrimitive(ok)) })
    }

    private suspend fun sppConnect(request: BridgeRequest): BridgeOutcome {
        val mac = jsonArg(request.args, "mac")
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "mac required")
        if (!MAC_REGEX.matches(mac)) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "mac must match AA:BB:CC:DD:EE:FF")
        }
        val uuidStr = jsonArg(request.args, "uuid") ?: SPP_UUID
        val uuid = runCatching { UUID.fromString(uuidStr) }.getOrElse {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "invalid uuid")
        }
        closeQuietly(request.webAppId)
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Bluetooth not available")
        if (!adapter.isEnabled) {
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Bluetooth is disabled")
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val device = adapter.getRemoteDevice(mac)
                val socket = device.createRfcommSocketToServiceRecord(uuid)
                socket.connect()
                sockets[request.webAppId] = socket
                macs[request.webAppId] = mac.uppercase()
                startSppRead(request.webAppId, socket)
                BridgeOutcome.Ok()
            }.getOrElse { e ->
                closeQuietly(request.webAppId)
                BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, e.message ?: "connect failed")
            }
        }
    }

    private fun startSppRead(webAppId: String, socket: BluetoothSocket) {
        scope.launch {
            val buf = ByteArray(1024)
            try {
                val input = socket.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val payload = buildJsonObject {
                        put("base64", JsonPrimitive(Base64.encodeToString(buf.copyOf(n), Base64.NO_WRAP)))
                    }.toString()
                    BridgeService.instanceOrNull()?.emit(webAppId, "bluetooth.sppData", payload)
                }
            } catch (_: Exception) {
            }
            val payload = buildJsonObject {
                put("connected", JsonPrimitive(false))
                put("address", JsonPrimitive(macs[webAppId].orEmpty()))
            }.toString()
            BridgeService.instanceOrNull()?.emit(webAppId, "bluetooth.sppConnection", payload)
        }
    }

    private suspend fun sppWrite(request: BridgeRequest): BridgeOutcome {
        val socket = sockets[request.webAppId]
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "not connected")
        val text = jsonArg(request.args, "text")
        val base64 = jsonArg(request.args, "base64")
        val bytes = when {
            text != null -> text.toByteArray(Charsets.UTF_8)
            base64 != null -> Base64.decode(base64, Base64.DEFAULT)
            else -> return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "text or base64 required")
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                socket.outputStream.write(bytes)
                socket.outputStream.flush()
                BridgeOutcome.Ok()
            }.getOrElse { e ->
                BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, e.message ?: "write failed")
            }
        }
    }

    private fun sppClose(request: BridgeRequest): BridgeOutcome {
        closeQuietly(request.webAppId)
        return BridgeOutcome.Ok()
    }

    private fun sppStatus(request: BridgeRequest): BridgeOutcome {
        val connected = sockets[request.webAppId]?.isConnected == true
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("connected", JsonPrimitive(connected))
                if (connected) {
                    macs[request.webAppId]?.let { put("mac", JsonPrimitive(it)) }
                }
            },
        )
    }

    private fun sppListen(request: BridgeRequest): BridgeOutcome {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return fail("Bluetooth not available")
        val uuidStr = jsonArg(request.args, "uuid") ?: SPP_UUID
        val uuid = parseListenUuid(uuidStr) ?: return fail("invalid uuid")
        val holder = listenOwner
        if (holder != null && holder != request.webAppId) return inUse(holder, radio = false)
        stopListen()
        listenSocket = adapter.listenUsingRfcommWithServiceRecord("DeftSPP", uuid)
        listenOwner = request.webAppId
        listenJob = scope.launch {
            try {
                val socket = listenSocket?.accept() ?: return@launch
                sockets[request.webAppId] = socket
                macs[request.webAppId] = bleMac(socket.remoteDevice?.address.orEmpty())
                val payload = buildJsonObject {
                    put("connected", JsonPrimitive(true))
                    put("address", JsonPrimitive(macs[request.webAppId].orEmpty()))
                }.toString()
                BridgeService.instanceOrNull()?.emit(request.webAppId, "bluetooth.sppConnection", payload)
                startSppRead(request.webAppId, socket)
            } catch (_: Exception) {
            }
        }
        return BridgeOutcome.Ok()
    }

    private fun sppListenStop(request: BridgeRequest): BridgeOutcome {
        val holder = listenOwner
        if (holder != null && holder != request.webAppId) return inUse(holder, radio = false)
        stopListen()
        return BridgeOutcome.Ok()
    }

    private fun parseListenUuid(s: String): UUID? = runCatching { UUID.fromString(s) }.getOrNull()

    private fun stopListen() {
        listenJob?.cancel()
        listenJob = null
        runCatching { listenSocket?.close() }
        listenSocket = null
        listenOwner = null
    }

    private fun closeQuietly(webAppId: String) {
        runCatching { sockets.remove(webAppId)?.close() }
        macs.remove(webAppId)
    }

    fun radioOwner(): Pair<String, String>? {
        val id = radioLock.ownerId ?: return null
        val name = radioLock.ownerName ?: return id to id
        return id to name
    }

    fun releaseIfOwner(webAppId: String) {
        releaseApp(webAppId)
    }

    fun releaseApp(webAppId: String) {
        val released = radioLock.releaseApp(webAppId)
        if (released.heldRadio) {
            central.stopScan()
            peripheral.close()
        }
        central.disconnectAll(released.gattDevices)
        closeQuietly(webAppId)
        if (listenOwner == webAppId) stopListen()
    }

    fun shutdown() {
        stopListen()
        for (id in sockets.keys.toList()) closeQuietly(id)
        central.closeAll()
        peripheral.close()
        host.close()
        radioLock.clearAll()
    }

    private fun inUse(holderId: String?, radio: Boolean): BridgeOutcome {
        val name = when {
            holderId == null -> context.getString(R.string.bt_radio_other_app)
            holderId == radioLock.ownerId && radioLock.ownerName != null -> radioLock.ownerName!!
            else -> appNames[holderId] ?: context.getString(R.string.bt_radio_other_app)
        }
        val toast = if (radio) R.string.bt_radio_in_use else R.string.bt_device_in_use
        val generic = if (radio) R.string.bt_radio_in_use_generic else R.string.bt_device_in_use_generic
        mainHandler.post {
            Toast.makeText(
                context.applicationContext,
                context.getString(toast, name),
                Toast.LENGTH_LONG,
            ).show()
        }
        return BridgeOutcome.Err(BridgeErrors.IN_USE, context.getString(generic))
    }

    private suspend fun appName(webAppId: String): String {
        return DeftApp.instance.repository.getApp(webAppId)?.name
            ?: context.getString(R.string.bt_radio_other_app)
    }

    /** BLUETOOTH_CONNECT from Android 12; before that the plain BLUETOOTH permission (install time). */
    private fun connectPermission(): String =
        if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else @Suppress("DEPRECATION") Manifest.permission.BLUETOOTH

    /** The runtime permissions a radio method needs: scan for scans, advertise for advertising and the server. */
    private fun scanAdvertisePermissions(method: String): List<String> {
        if (Build.VERSION.SDK_INT < 31) return emptyList()
        return buildList {
            if (method.contains("Scan")) add(Manifest.permission.BLUETOOTH_SCAN)
            if (method.contains("Advertise") || method.contains("Server")) add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
    }

    private fun missing(name: String) = BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "$name required")
    private fun fail(msg: String) = BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, msg)

    companion object {
        private val MAC_REGEX = Regex("""^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$""")
        private const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    }
}
