package app.deft.bridge.modules

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.BridgeService
import app.deft.bridge.HostSurfaces
import app.deft.bridge.NetSpec
import app.deft.bridge.jsonArg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * `net.*` (PARITY F8 and G4): service discovery, TCP and UDP sockets. Only while the app is on
 * screen (`net.discoverStop` and `net.close` also work to clean up).
 *
 * Every destination is resolved first, every resolved address is checked with [NetSpec.admit],
 * and the socket connects to the checked [InetAddress] itself, never to the name, so a DNS answer
 * cannot change after the check. Sockets are closed when the app stops being live, when its
 * `net.*` grants are revoked, and when the app is uninstalled.
 */
class NetModule(private val service: BridgeService) : BridgeModule {
    private class Handle(
        val webAppId: String,
        val id: String,
        val tcp: Socket?,
        val udp: DatagramSocket?,
    ) {
        fun close() {
            runCatching { tcp?.close() }
            runCatching { udp?.close() }
        }
    }

    private class Discovery(val listener: NsdManager.DiscoveryListener, val lock: WifiManager.MulticastLock?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handles = ConcurrentHashMap<String, Handle>()
    private val discoveries = ConcurrentHashMap<String, Discovery>()
    private val registerLock = Any()
    private val resolveLock = Mutex()
    private val ids = AtomicLong(0)

    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        val webAppId = request.webAppId
        if (request.method != "net.close" && request.method != "net.discoverStop") {
            HostSurfaces.forRequest(request)
                ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        }
        return when (request.method) {
            "net.discover" -> discover(request)
            "net.discoverStop" -> {
                stopDiscovery(webAppId)
                BridgeOutcome.Ok()
            }
            "net.resolve" -> resolve(request)
            "net.tcpConnect" -> tcpConnect(request)
            "net.tcpSend" -> tcpSend(request)
            "net.udpSend" -> udpSend(request)
            "net.udpListen" -> udpListen(request)
            "net.close" -> close(request)
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    // ---- discovery (mDNS through NsdManager) ----

    private suspend fun discover(request: BridgeRequest): BridgeOutcome {
        val type = NetSpec.serviceType(jsonArg(request.args, "type"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "type must look like _http._tcp")
        val manager = nsdManager()
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Network discovery is not available")
        val webAppId = request.webAppId
        stopDiscovery(webAppId)
        val typeName = type.trimEnd('.')
        // Completes with "" once NsdManager reports the start, or with the reason it failed.
        val started = CompletableDeferred<String>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                started.complete("")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveries.remove(webAppId)?.let { releaseLock(it.lock) }
                started.complete("Discovery could not start (code $errorCode)")
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                emitIfLive(webAppId, "net.found", namePayload(info.serviceName, typeName))
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                emitIfLive(webAppId, "net.lost", namePayload(info.serviceName, typeName))
            }
        }
        val lock = multicastLock()
        runCatching { lock?.setReferenceCounted(false); lock?.acquire() }
        discoveries[webAppId] = Discovery(listener, lock)
        runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                discoveries.remove(webAppId)?.let { releaseLock(it.lock) }
                return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Discovery could not start")
            }
        val error = withTimeoutOrNull(DISCOVERY_START_TIMEOUT_MS) { started.await() } ?: "Discovery did not start in time"
        if (error.isNotEmpty()) {
            stopDiscovery(webAppId)
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, error)
        }
        return BridgeOutcome.Ok()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(request: BridgeRequest): BridgeOutcome {
        val name = jsonArg(request.args, "name")?.takeIf { it.isNotBlank() && it.length <= 63 }
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "name required")
        val type = NetSpec.serviceType(jsonArg(request.args, "type"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "type must look like _http._tcp")
        val manager = nsdManager()
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Network discovery is not available")
        val info = resolveService(manager, name, type)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Could not find $name")
        val host = info.host
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Could not find $name")
        // A service found on the Wi-Fi resolves to a LAN address; anything else is refused.
        if (NetSpec.admit(listOf(host), allowInternet = false) != NetSpec.Verdict.OK) {
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "$name is not on your network")
        }
        val txt = buildJsonObject {
            for ((key, value) in info.attributes) {
                put(key, JsonPrimitive(value?.toString(Charsets.UTF_8).orEmpty()))
            }
        }
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("host", JsonPrimitive(host.hostAddress))
                put("port", JsonPrimitive(info.port))
                put("txt", txt)
            },
        )
    }

    /** NsdManager resolves one service at a time on older Android versions. */
    @Suppress("DEPRECATION")
    private suspend fun resolveService(manager: NsdManager, name: String, type: String): NsdServiceInfo? =
        resolveLock.withLock {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                    val probe = NsdServiceInfo().apply {
                        serviceName = name
                        serviceType = type
                    }
                    runCatching {
                        manager.resolveService(probe, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                if (cont.isActive) cont.resume(serviceInfo)
                            }
                        })
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
        }

    private fun stopDiscovery(webAppId: String) {
        val found = discoveries.remove(webAppId) ?: return
        runCatching { nsdManager()?.stopServiceDiscovery(found.listener) }
        releaseLock(found.lock)
    }

    private fun releaseLock(lock: WifiManager.MulticastLock?) {
        runCatching { if (lock != null && lock.isHeld) lock.release() }
    }

    private fun multicastLock(): WifiManager.MulticastLock? = runCatching {
        service.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("freedom-nsd")
    }.getOrNull()

    private fun nsdManager(): NsdManager? = service.getSystemService(NsdManager::class.java)

    private fun namePayload(name: String, type: String): String = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("type", JsonPrimitive(type))
    }.toString()

    // ---- TCP ----

    private suspend fun tcpConnect(request: BridgeRequest): BridgeOutcome {
        val webAppId = request.webAppId
        val host = NetSpec.host(jsonArg(request.args, "host"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "host is not valid")
        val port = NetSpec.port(jsonArg(request.args, "port"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "port must be 1 to 65535")
        val timeout = NetSpec.timeoutMs(jsonArg(request.args, "timeoutMs"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "timeoutMs must be 1 to ${NetSpec.MAX_TIMEOUT_MS}")
        if (countFor(webAppId) >= NetSpec.MAX_SOCKETS_PER_APP) return tooManySockets()
        val addresses = lookup(host)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Could not find $host")
        checkAddresses(webAppId, addresses)?.let { return it }
        // Connect to the checked addresses themselves; the name is never looked up again.
        val socket = withContext(Dispatchers.IO) { connectFirst(addresses, port, timeout) }
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Could not connect to $host:$port")
        val id = newId()
        val handle = Handle(webAppId, id, socket, null)
        val accepted = synchronized(registerLock) {
            if (countFor(webAppId) >= NetSpec.MAX_SOCKETS_PER_APP) {
                false
            } else {
                handles[id] = handle
                true
            }
        }
        if (!accepted) {
            runCatching { socket.close() }
            return tooManySockets()
        }
        startTcpReader(handle, socket)
        return BridgeOutcome.Ok(socketIdJson(id))
    }

    private suspend fun tcpSend(request: BridgeRequest): BridgeOutcome {
        val handle = ownHandle(request) ?: return unknownSocket()
        val socket = handle.tcp ?: return unknownSocket()
        val bytes = NetSpec.decodeSend(jsonArg(request.args, "base64"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "base64 must be at most 64 KiB")
        return withContext(Dispatchers.IO) {
            runCatching {
                synchronized(handle) {
                    val out = socket.getOutputStream()
                    out.write(bytes)
                    out.flush()
                }
                BridgeOutcome.Ok()
            }.getOrElse { BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Send failed") }
        }
    }

    private fun startTcpReader(handle: Handle, socket: Socket) {
        scope.launch {
            val buf = ByteArray(8192)
            try {
                val input = socket.getInputStream()
                while (service.hasSink(handle.webAppId)) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    service.emit(handle.webAppId, "net.data", dataPayload(handle.id, buf.copyOf(n), null, null))
                }
            } catch (_: Exception) {
            } finally {
                dropAndNotify(handle)
            }
        }
    }

    // ---- UDP ----

    private suspend fun udpSend(request: BridgeRequest): BridgeOutcome {
        val webAppId = request.webAppId
        val host = NetSpec.host(jsonArg(request.args, "host"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "host is not valid")
        val port = NetSpec.port(jsonArg(request.args, "port"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "port must be 1 to 65535")
        val bytes = NetSpec.decodeSend(jsonArg(request.args, "base64"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "base64 must be at most 64 KiB")
        val addresses = lookup(host)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Could not find $host")
        checkAddresses(webAppId, addresses)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val socket = DatagramSocket()
                try {
                    socket.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(addresses.first(), port)))
                } finally {
                    socket.close()
                }
                BridgeOutcome.Ok()
            }.getOrElse { BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Send failed") }
        }
    }

    private suspend fun udpListen(request: BridgeRequest): BridgeOutcome {
        val webAppId = request.webAppId
        val port = NetSpec.udpListenPort(jsonArg(request.args, "port"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "port must be ${NetSpec.MIN_UDP_LISTEN_PORT} to 65535")
        if (countFor(webAppId) >= NetSpec.MAX_SOCKETS_PER_APP) return tooManySockets()
        val allowInternet = service.repository.internetSocketsAllowed(webAppId)
        val socket = runCatching { DatagramSocket(port) }.getOrNull()
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Port $port is in use")
        val id = newId()
        val handle = Handle(webAppId, id, null, socket)
        val accepted = synchronized(registerLock) {
            if (countFor(webAppId) >= NetSpec.MAX_SOCKETS_PER_APP) {
                false
            } else {
                handles[id] = handle
                true
            }
        }
        if (!accepted) {
            runCatching { socket.close() }
            return tooManySockets()
        }
        startUdpReader(handle, socket, allowInternet)
        return BridgeOutcome.Ok(socketIdJson(id))
    }

    /**
     * Datagrams from a sender that the same rule refuses (loopback, multicast, the phone's own
     * addresses, or internet without the grant) are dropped. Each event also carries the sender `host` and `port`,
     * which a UDP reply needs.
     */
    private fun startUdpReader(handle: Handle, socket: DatagramSocket, allowInternet: Boolean) {
        scope.launch {
            val buf = ByteArray(NetSpec.MAX_SEND_BYTES)
            val own = OwnAddresses()
            try {
                while (service.hasSink(handle.webAppId)) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val sender = packet.address ?: continue
                    if (NetSpec.admit(listOf(sender), allowInternet, own.get()) != NetSpec.Verdict.OK) continue
                    service.emit(
                        handle.webAppId,
                        "net.data",
                        dataPayload(handle.id, buf.copyOf(packet.length), sender.hostAddress, packet.port),
                    )
                }
            } catch (_: Exception) {
            } finally {
                dropAndNotify(handle)
            }
        }
    }

    // ---- shared ----

    private suspend fun close(request: BridgeRequest): BridgeOutcome {
        val handle = ownHandle(request) ?: return unknownSocket()
        handles.remove(handle.id)
        handle.close()
        return BridgeOutcome.Ok()
    }

    /** Resolves a host name or IP literal. Blocking DNS runs off the main thread. */
    private suspend fun lookup(host: String): List<InetAddress>? = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Null when every address is allowed, otherwise the error to return. */
    private suspend fun checkAddresses(webAppId: String, addresses: List<InetAddress>): BridgeOutcome.Err? {
        val allowInternet = service.repository.internetSocketsAllowed(webAppId)
        val own = withContext(Dispatchers.IO) { ownAddresses() }
        return when (NetSpec.admit(addresses, allowInternet, own)) {
            NetSpec.Verdict.OK -> null
            NetSpec.Verdict.EMPTY -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "No address found")
            NetSpec.Verdict.REFUSED -> BridgeOutcome.Err(
                BridgeErrors.INVALID_ARGS,
                "That address is not allowed (this phone, loopback, multicast or unspecified)",
            )
            NetSpec.Verdict.NEEDS_INTERNET -> BridgeOutcome.Err(
                BridgeErrors.NOT_GRANTED,
                "Addresses outside your network need Internet connections and Any website",
            )
        }
    }

    /** Review: a reader's view of the phone's own addresses, looked up again every few seconds. */
    private class OwnAddresses {
        private var at = 0L
        private var cached: List<InetAddress> = emptyList()

        fun get(): List<InetAddress> {
            val now = System.currentTimeMillis()
            if (now - at > OWN_REFRESH_MS) {
                cached = ownAddresses()
                at = now
            }
            return cached
        }
    }

    private fun connectFirst(addresses: List<InetAddress>, port: Int, timeoutMs: Int): Socket? {
        for (address in addresses) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                return socket
            } catch (_: Exception) {
                runCatching { socket.close() }
            }
        }
        return null
    }

    private fun ownHandle(request: BridgeRequest): Handle? {
        val id = jsonArg(request.args, "socketId") ?: return null
        return handles[id]?.takeIf { it.webAppId == request.webAppId }
    }

    private fun countFor(webAppId: String): Int = handles.values.count { it.webAppId == webAppId }

    private fun newId(): String = "s" + ids.incrementAndGet()

    /** Removes the socket if it is still registered; tells the app only if it is still open. */
    private suspend fun dropAndNotify(handle: Handle) {
        val removed = handles.remove(handle.id) ?: return
        removed.close()
        if (service.hasSink(handle.webAppId)) {
            service.emit(
                handle.webAppId,
                "net.closed",
                buildJsonObject { put("socketId", JsonPrimitive(handle.id)) }.toString(),
            )
        }
    }

    private fun emitIfLive(webAppId: String, event: String, payload: String) {
        scope.launch {
            if (service.hasSink(webAppId)) service.emit(webAppId, event, payload)
        }
    }

    private fun dataPayload(id: String, bytes: ByteArray, host: String?, port: Int?): String = buildJsonObject {
        put("socketId", JsonPrimitive(id))
        put("base64", JsonPrimitive(NetSpec.encode(bytes)))
        if (host != null) put("host", JsonPrimitive(host))
        if (port != null) put("port", JsonPrimitive(port))
    }.toString()

    private fun socketIdJson(id: String): JsonObject = buildJsonObject { put("socketId", JsonPrimitive(id)) }

    private fun tooManySockets() = BridgeOutcome.Err(
        BridgeErrors.UNSUPPORTED,
        "At most ${NetSpec.MAX_SOCKETS_PER_APP} open sockets per app",
    )

    private fun unknownSocket() = BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "unknown socket")

    /** Called when the app stops being live, loses a `net.*` grant, or is uninstalled. */
    fun releaseApp(webAppId: String) {
        stopDiscovery(webAppId)
        for (handle in handles.values.filter { it.webAppId == webAppId }) {
            handles.remove(handle.id)
            handle.close()
        }
    }

    fun shutdown() {
        for (webAppId in discoveries.keys.toList()) stopDiscovery(webAppId)
        for (handle in handles.values.toList()) {
            handles.remove(handle.id)
            handle.close()
        }
        scope.cancel()
    }

    private companion object {
        const val DISCOVERY_START_TIMEOUT_MS = 5_000L
        const val RESOLVE_TIMEOUT_MS = 10_000L
    }
}

/** Review: the addresses of every network interface of the phone right now. */
internal fun ownAddresses(): List<InetAddress> = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.inetAddresses.toList() }
}.getOrDefault(emptyList())

private const val OWN_REFRESH_MS = 5_000L
