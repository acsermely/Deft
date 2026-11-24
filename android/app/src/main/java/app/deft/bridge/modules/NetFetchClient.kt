package app.deft.bridge.modules

import android.os.SystemClock
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.NetFetchSpec
import app.deft.bridge.NetSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The checked HTTPS client behind `net.fetch` (the bridge module) and the agent's `web.fetch`,
 * `net.test` and verify sandbox. Policy lives in [NetFetchSpec]; this object does the argument
 * parsing and the I/O, so every caller gets the same pinned-address TLS, redirect re-check,
 * local-address refusal, size and time caps.
 *
 * DNS rebinding: each hop resolves the host once, checks every address ([NetFetchSpec.admit]),
 * and connects a plain socket to the checked [InetAddress]. TLS then runs over that socket with
 * the host name for SNI and certificate checking, so the name is never resolved a second time.
 * No cookies, no credential store, no proxy, no cache. One shared 15 s deadline covers all hops.
 */
object NetFetchClient {
    /** Each hop blocks an IO thread for up to 15 s; a few at once keeps the shared pool free for the launcher. */
    private val slots = Semaphore(4)

    sealed interface Parsed {
        data class Ok(val args: Args) : Parsed
        data class Bad(val message: String) : Parsed
    }

    class Args(
        val target: NetFetchSpec.Target,
        val method: String,
        val mode: String,
        val headers: Map<String, String>,
    )

    /** Validates the `net.fetch` argument object (url, method, as, headers). Same messages for every caller. */
    fun parse(args: JsonObject): Parsed {
        val target = NetFetchSpec.parseUrl((args["url"] as? JsonPrimitive)?.contentOrNull)
            ?: return Parsed.Bad("url must be an https URL without a user name")
        val method = NetFetchSpec.method((args["method"] as? JsonPrimitive)?.contentOrNull)
            ?: return Parsed.Bad("method must be GET or HEAD")
        val mode = NetFetchSpec.asMode((args["as"] as? JsonPrimitive)?.contentOrNull)
            ?: return Parsed.Bad("as must be text, json or base64")
        val raw = LinkedHashMap<String, String>()
        when (val h = args["headers"]) {
            null -> Unit
            is JsonObject -> for ((k, v) in h) {
                raw[k] = (v as? JsonPrimitive)?.contentOrNull ?: return Parsed.Bad("header values must be strings")
            }
            else -> return Parsed.Bad("headers must be an object")
        }
        val headers = when (val h = NetFetchSpec.headers(raw)) {
            is NetFetchSpec.Headers.Ok -> h.values
            is NetFetchSpec.Headers.Refused -> return Parsed.Bad(
                "header ${h.name.take(40)} is not allowed (allowed: ${NetFetchSpec.REQUEST_HEADERS.joinToString()})",
            )
        }
        return Parsed.Ok(Args(target, method, mode, headers))
    }

    /**
     * Fetches [first] under the origin policy: [open] allows any origin (network.open), otherwise
     * the origin must be in [listed]. [local] allows the LAN (net.local); loopback and the phone's own
     * addresses are always refused. Returns `{status, url, headers, body}` on success.
     */
    suspend fun fetch(
        first: NetFetchSpec.Target,
        method: String,
        mode: String,
        headers: Map<String, String>,
        listed: Collection<String>,
        open: Boolean,
        local: Boolean,
    ): BridgeOutcome = slots.withPermit { run(first, method, mode, headers, listed, open, local) }

    private suspend fun run(
        first: NetFetchSpec.Target,
        method: String,
        mode: String,
        headers: Map<String, String>,
        listed: Collection<String>,
        open: Boolean,
        local: Boolean,
    ): BridgeOutcome {
        var target = first
        val deadline = SystemClock.elapsedRealtime() + NetFetchSpec.TIMEOUT_MS
        var hops = 0
        while (true) {
            if (!NetFetchSpec.originAllowed(target, listed, open)) {
                return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "${target.origin} is not an allowed network origin for this app")
            }
            val result = withContext(Dispatchers.IO) { fetchOnce(target, method, headers, local, deadline) }
            val response = when (result) {
                is Hop.Failed -> return result.err
                is Hop.Done -> result.response
            }
            if (NetFetchSpec.isRedirect(response.status)) {
                val next = NetFetchSpec.redirectTarget(target, response.headers["location"])
                    ?: return BridgeOutcome.Err(BridgeErrors.NETWORK_ERROR, "Bad redirect")
                if (++hops > NetFetchSpec.MAX_REDIRECTS) {
                    return BridgeOutcome.Err(BridgeErrors.NETWORK_ERROR, "More than ${NetFetchSpec.MAX_REDIRECTS} redirects")
                }
                target = next
                continue
            }
            return body(response, mode, target)
        }
    }

    private sealed interface Hop {
        data class Done(val response: NetFetchSpec.Response) : Hop
        data class Failed(val err: BridgeOutcome.Err) : Hop
    }

    private fun fail(code: String, message: String) = Hop.Failed(BridgeOutcome.Err(code, message))

    /** Blocking; runs on the IO dispatcher. */
    private fun fetchOnce(
        target: NetFetchSpec.Target,
        method: String,
        headers: Map<String, String>,
        allowLocal: Boolean,
        deadline: Long,
    ): Hop {
        val addresses = runCatching { InetAddress.getAllByName(target.host).toList() }.getOrNull().orEmpty()
        when (NetFetchSpec.admit(addresses, allowLocal, ownAddresses())) {
            NetFetchSpec.Verdict.OK -> Unit
            NetFetchSpec.Verdict.EMPTY -> return fail(BridgeErrors.NETWORK_ERROR, "Could not find ${target.host}")
            NetFetchSpec.Verdict.REFUSED -> return fail(
                BridgeErrors.INVALID_ARGS,
                "That address is not allowed (this phone, loopback, multicast or unspecified)",
            )
            NetFetchSpec.Verdict.NEEDS_LOCAL -> return fail(
                BridgeErrors.NOT_GRANTED,
                "Addresses on your own network need Devices on your network (net.local)",
            )
        }
        var tcp: Socket? = null
        for (address in addresses) {
            val remaining = remaining(deadline)
            if (remaining <= 0) return fail(BridgeErrors.NETWORK_ERROR, "Timed out")
            val s = Socket()
            try {
                s.connect(InetSocketAddress(address, target.port), remaining.coerceAtMost(8_000L).toInt())
                tcp = s
                break
            } catch (_: Exception) {
                runCatching { s.close() }
            }
        }
        val plain = tcp ?: return fail(BridgeErrors.NETWORK_ERROR, "Could not connect to ${target.host}")
        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            // Layered over the already connected, already checked socket: no second DNS lookup.
            val ssl = factory.createSocket(plain, target.host, target.port, true) as SSLSocket
            ssl.soTimeout = remaining(deadline).coerceAtLeast(1L).toInt()
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(target.host, ssl.session)) {
                return fail(BridgeErrors.NETWORK_ERROR, "Certificate does not match ${target.host}")
            }
            ssl.getOutputStream().apply {
                write(NetFetchSpec.requestBytes(target, method, headers))
                flush()
            }
            val input = DeadlineInput(ssl, deadline)
            Hop.Done(NetFetchSpec.readResponse(input, head = method == "HEAD"))
        } catch (e: NetFetchSpec.TooLarge) {
            fail(BridgeErrors.NETWORK_ERROR, "Response is larger than 2 MB")
        } catch (e: NetFetchSpec.Malformed) {
            fail(BridgeErrors.NETWORK_ERROR, "Bad response")
        } catch (e: java.net.SocketTimeoutException) {
            fail(BridgeErrors.NETWORK_ERROR, "Timed out")
        } catch (e: Exception) {
            fail(BridgeErrors.NETWORK_ERROR, "Request failed")
        } finally {
            runCatching { plain.close() }
        }
    }

    private fun remaining(deadline: Long): Long = deadline - SystemClock.elapsedRealtime()

    /** Re-arms the socket timeout before every read so the whole fetch ends by [deadline]. */
    private class DeadlineInput(private val socket: Socket, private val deadline: Long) : java.io.InputStream() {
        private val inner = socket.getInputStream()

        private fun arm() {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) throw java.net.SocketTimeoutException("deadline")
            socket.soTimeout = left.toInt()
        }

        override fun read(): Int {
            arm()
            return inner.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            arm()
            return inner.read(b, off, len)
        }
    }

    private fun body(response: NetFetchSpec.Response, mode: String, target: NetFetchSpec.Target): BridgeOutcome {
        val value = when (mode) {
            "base64" -> JsonPrimitive(NetSpec.encode(response.body))
            "json" -> if (response.body.isEmpty()) {
                kotlinx.serialization.json.JsonNull
            } else {
                val text = decode(response.body, response.headers["content-type"])
                runCatching { Json.parseToJsonElement(text) }.getOrElse {
                    return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The response is not valid JSON")
                }
            }
            else -> JsonPrimitive(decode(response.body, response.headers["content-type"]))
        }
        val out = buildJsonObject {
            put("status", JsonPrimitive(response.status))
            put("url", JsonPrimitive(target.url))
            put("headers", buildJsonObject { for ((k, v) in response.headers) put(k, JsonPrimitive(v)) })
            put("body", value)
        }
        // Audit metadata: host and size only, never the path, query or content.
        return BridgeOutcome.Ok(out, "host=${target.host} status=${response.status} bytes=${response.body.size}")
    }

    private fun decode(bytes: ByteArray, contentType: String?): String {
        val name = contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
        val charset = runCatching { Charset.forName(name ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

}
