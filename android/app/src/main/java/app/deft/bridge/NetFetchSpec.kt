package app.deft.bridge

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.util.zip.GZIPInputStream

/**
 * `net.fetch`: the request policy and the HTTP/1.1 wire format, free of Android types so every
 * rule can be unit tested. The module ([app.deft.bridge.modules.NetFetchModule]) does
 * the sockets.
 *
 * Policy: https only, no user info; the origin must be in the app's allowed list (manifest
 * `network` consented, plus `network.user` servers) or `network.open` must be held; every
 * redirect hop is checked again; every resolved address is checked ([admit]) and the socket
 * connects to the checked address itself, never to the name.
 */
object NetFetchSpec {
    const val METHOD = "net.fetch"
    const val MAX_BYTES = 2 * 1024 * 1024
    const val TIMEOUT_MS = 15_000L
    const val MAX_REDIRECTS = 5
    const val MAX_URL = 2048
    const val RATE_PER_MINUTE = 60
    const val USER_AGENT = "Deft"

    private const val MAX_LINE = 8192
    private const val MAX_HEADERS = 100

    /** Request headers an app may set. No Cookie, Authorization, Host, Origin, Referer or Proxy-*. */
    val REQUEST_HEADERS: Set<String> = setOf(
        "accept", "accept-language", "if-none-match", "if-modified-since", "cache-control", "user-agent",
    )

    /** Response headers never handed to the app. */
    private val HIDDEN_RESPONSE = setOf("set-cookie", "set-cookie2")

    /** One https destination. [origin] is `https://host` or `https://host:port` (443 omitted), lowercase. */
    data class Target(val host: String, val port: Int, val path: String) {
        val origin: String get() = "https://$host" + if (port == 443) "" else ":$port"
        val url: String get() = origin + path
    }

    fun parseUrl(raw: String?): Target? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL } ?: return null
        if (text.any { it.isWhitespace() || it.code < 0x20 }) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (uri.port == -1) 443 else uri.port
        if (port !in 1..65535) return null
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        return Target(host, port, path)
    }

    /** Listed origins are compared lowercase with a default `:443` dropped. */
    private fun normalizeOrigin(origin: String): String = origin.trim().lowercase().removeSuffix(":443")

    fun originAllowed(target: Target, listed: Collection<String>, open: Boolean): Boolean =
        open || listed.any { normalizeOrigin(it) == target.origin }

    /** `GET` (default) or `HEAD`, any case; null for anything else. */
    fun method(raw: String?): String? = when (raw?.trim()?.uppercase()) {
        null, "", "GET" -> "GET"
        "HEAD" -> "HEAD"
        else -> null
    }

    fun asMode(raw: String?): String? = when (raw) {
        null -> "text"
        "text", "json", "base64" -> raw
        else -> null
    }

    private val tokenName = Regex("^[A-Za-z0-9-]{1,40}$")

    /** Lowercase name to value, or the name of the first header that is not allowed. */
    sealed interface Headers {
        data class Ok(val values: Map<String, String>) : Headers
        data class Refused(val name: String) : Headers
    }

    fun headers(raw: Map<String, String>): Headers {
        val out = linkedMapOf<String, String>()
        for ((name, value) in raw) {
            val key = name.trim().lowercase()
            if (!tokenName.matches(name.trim()) || key !in REQUEST_HEADERS) return Headers.Refused(name)
            if (value.length > 512 || value.any { it == '\r' || it == '\n' || it == '\u0000' }) return Headers.Refused(name)
            out[key] = value.trim()
        }
        return Headers.Ok(out)
    }

    fun isRedirect(status: Int): Boolean = status == 301 || status == 302 || status == 303 || status == 307 || status == 308

    /** The next hop of a redirect, or null when [location] is missing, malformed or not https. */
    fun redirectTarget(from: Target, location: String?): Target? {
        val loc = location?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL } ?: return null
        val base = runCatching { URI(from.url) }.getOrNull() ?: return null
        val next = runCatching { base.resolve(loc) }.getOrNull() ?: return null
        return parseUrl(next.toString())
    }

    enum class Verdict { OK, EMPTY, REFUSED, NEEDS_LOCAL }

    /**
     * Every resolved address must pass: one refused address rejects the whole name. Loopback,
     * multicast, unspecified, broadcast and the phone's own addresses ([own]) are always refused.
     * RFC 1918, link-local (169.254/16, fe80::/10) and ULA (fc00::/7) addresses need `net.local`
     * ([allowLocal]). The same classification as the socket module ([LanAddress]).
     */
    fun admit(addresses: List<InetAddress>, allowLocal: Boolean, own: Collection<InetAddress> = emptyList()): Verdict {
        if (addresses.isEmpty()) return Verdict.EMPTY
        var local = false
        for (address in addresses) {
            if (LanAddress.isOwn(address, own)) return Verdict.REFUSED
            when (LanAddress.classify(address)) {
                LanAddress.Class.REFUSED -> return Verdict.REFUSED
                LanAddress.Class.LAN -> local = true
                LanAddress.Class.INTERNET -> Unit
            }
        }
        return if (local && !allowLocal) Verdict.NEEDS_LOCAL else Verdict.OK
    }

    /** The request bytes: HTTP/1.1, no cookies, `Connection: close`. [headers] are already allowlisted. */
    fun requestBytes(target: Target, method: String, headers: Map<String, String>): ByteArray {
        val hostHeader = target.host + if (target.port == 443) "" else ":${target.port}"
        val merged = linkedMapOf(
            "accept" to "*/*",
            "user-agent" to USER_AGENT,
        )
        merged.putAll(headers)
        val sb = StringBuilder()
        sb.append(method).append(' ').append(target.path).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(hostHeader).append("\r\n")
        for ((k, v) in merged) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Accept-Encoding: gzip\r\nConnection: close\r\n\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    class TooLarge : Exception("response is larger than $MAX_BYTES bytes")

    class Malformed(message: String) : Exception(message)

    /**
     * Reads one HTTP/1.1 response. Handles `Content-Length`, chunked and read-to-close bodies and a
     * gzip `Content-Encoding`; the body (after decoding) is capped at [maxBody] ([TooLarge] beyond:
     * the read stops, nothing more is pulled). Interim 1xx responses are skipped. A [head] request,
     * 204 and 304 have no body. Header names are lowercased; repeated headers are joined with ", ";
     * `set-cookie` is dropped.
     */
    fun readResponse(input: InputStream, head: Boolean, maxBody: Int = MAX_BYTES): Response {
        var status: Int
        var headers: Map<String, String>
        while (true) {
            val statusLine = readLine(input) ?: throw Malformed("no response")
            val parts = statusLine.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw Malformed("bad status line")
            status = parts[1].toIntOrNull() ?: throw Malformed("bad status line")
            headers = readHeaders(input)
            if (status in 100..199 && status != 101) continue
            break
        }
        if (head || status == 204 || status == 304 || status in 100..199) return Response(status, headers, ByteArray(0))
        val chunked = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
        val raw = when {
            chunked -> readChunked(input, maxBody)
            headers["content-length"] != null -> {
                val n = headers["content-length"]!!.trim().toLongOrNull()?.takeIf { it >= 0 } ?: throw Malformed("bad content-length")
                if (n > maxBody) throw TooLarge()
                readExactly(input, n.toInt())
            }
            else -> readToEnd(input, maxBody)
        }
        val body = if (headers["content-encoding"]?.trim()?.lowercase() == "gzip") gunzip(raw, maxBody) else raw
        return Response(status, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else throw EOFException("truncated header")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > MAX_LINE) throw Malformed("header line too long")
        }
        return sb.toString()
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input) ?: throw EOFException("truncated header")
            if (line.isEmpty()) return out
            if (++count > MAX_HEADERS) throw Malformed("too many headers")
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            if (name in HIDDEN_RESPONSE) continue
            val value = line.substring(colon + 1).trim()
            out[name] = out[name]?.let { "$it, $value" } ?: value
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("truncated body")
            off += r
        }
        return buf
    }

    private fun readToEnd(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            if (out.size() + r > max) throw TooLarge()
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: throw EOFException("truncated body")
            val size = line.substringBefore(';').trim().toLongOrNull(16)?.takeIf { it >= 0 } ?: throw Malformed("bad chunk size")
            if (size == 0L) {
                readHeaders(input) // trailers
                return out.toByteArray()
            }
            if (out.size() + size > max) throw TooLarge()
            out.write(readExactly(input, size.toInt()))
            readLine(input) // the CRLF after the chunk
        }
    }

    private fun gunzip(raw: ByteArray, max: Int): ByteArray {
        if (raw.isEmpty()) return raw
        GZIPInputStream(raw.inputStream()).use { return readToEnd(it, max) }
    }
}
