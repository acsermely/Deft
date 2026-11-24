package app.deft.bridge

import java.net.InetAddress
import java.util.Base64

/** PARITY F8/G4: argument rules and address admission for `net.*`, free of Android types. */
object NetSpec {
    const val MAX_SOCKETS_PER_APP = 8
    const val MAX_SEND_BYTES = 64 * 1024
    const val MIN_UDP_LISTEN_PORT = 1024
    const val DEFAULT_TIMEOUT_MS = 10_000
    const val MAX_TIMEOUT_MS = 30_000

    enum class Verdict { OK, EMPTY, REFUSED, NEEDS_INTERNET }

    private val hostPattern = Regex("^[A-Za-z0-9._:%-]{1,253}$")
    private val serviceTypePattern = Regex("^_[a-z0-9-]{1,15}\\._(tcp|udp)\\.?$", RegexOption.IGNORE_CASE)

    /** A host name or IP literal. No scheme, path or spaces. */
    fun host(raw: String?): String? = raw?.trim()?.takeIf { hostPattern.matches(it) }

    fun port(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    fun udpListenPort(raw: String?): Int? = port(raw)?.takeIf { it >= MIN_UDP_LISTEN_PORT }

    fun timeoutMs(raw: String?): Int? {
        if (raw == null) return DEFAULT_TIMEOUT_MS
        return raw.trim().toIntOrNull()?.takeIf { it in 1..MAX_TIMEOUT_MS }
    }

    /** `_http._tcp` or `_http._tcp.`; returned with the trailing dot NsdManager expects. */
    fun serviceType(raw: String?): String? {
        val text = raw?.trim() ?: return null
        if (!serviceTypePattern.matches(text)) return null
        return if (text.endsWith('.')) text.lowercase() else text.lowercase() + "."
    }

    /** Decodes a send payload; null when it is not base64 or is larger than [MAX_SEND_BYTES]. */
    fun decodeSend(base64: String?): ByteArray? {
        val text = base64?.takeIf { it.isNotEmpty() } ?: return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(text) }.getOrNull() ?: return null
        return bytes.takeIf { it.isNotEmpty() && it.size <= MAX_SEND_BYTES }
    }

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /**
     * Every resolved address must pass: one refused address rejects the whole name, so the
     * launcher never connects to a good address of a name that also points somewhere forbidden.
     * [own] holds the phone's own addresses, which are refused too.
     */
    fun admit(addresses: List<InetAddress>, allowInternet: Boolean, own: Collection<InetAddress> = emptyList()): Verdict {
        if (addresses.isEmpty()) return Verdict.EMPTY
        var needsInternet = false
        for (address in addresses) {
            if (LanAddress.isOwn(address, own)) return Verdict.REFUSED
            when (LanAddress.classify(address)) {
                LanAddress.Class.REFUSED -> return Verdict.REFUSED
                LanAddress.Class.INTERNET -> needsInternet = true
                LanAddress.Class.LAN -> Unit
            }
        }
        return if (needsInternet && !allowInternet) Verdict.NEEDS_INTERNET else Verdict.OK
    }
}
