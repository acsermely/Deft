package app.deft.nfc

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Rules for NFC host card emulation, free of Android types for testing. */
object HceRouting {
    /** No app answered in time, or none is holding card emulation. ISO 7816 "no precise diagnosis". */
    val SW_NO_ANSWER = byteArrayOf(0x6F, 0x00)

    /** How long the holder app has to answer one command over the bridge. */
    const val RESPONSE_TIMEOUT_MS = 1_500L

    const val MAX_AIDS = 16

    /** Placeholder AID in the static service XML ("FL" in the proprietary F0 range). */
    const val PLACEHOLDER_AID = "F0464C00000001"

    /** Placeholder AID of the payment service's static XML; never the same as [PLACEHOLDER_AID]. */
    const val PAYMENT_PLACEHOLDER_AID = "F0464C00000002"

    /** Smallest response: just the 2-byte status word. */
    const val MIN_RESPONSE_BYTES = 2

    /** An AID is 5–16 bytes of hex (ISO 7816-4). Returns the upper-case form, or null. */
    fun normalizeAid(raw: String): String? {
        val hex = raw.trim().replace(" ", "").replace(":", "").uppercase()
        if (hex.length % 2 != 0 || hex.length !in 10..32) return null
        if (!hex.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return hex
    }

    fun validAids(raw: List<String>): Result<List<String>> {
        if (raw.isEmpty()) return Result.failure(IllegalArgumentException("aids must not be empty"))
        if (raw.size > MAX_AIDS) return Result.failure(IllegalArgumentException("at most $MAX_AIDS AIDs"))
        val out = raw.map { normalizeAid(it) ?: return Result.failure(IllegalArgumentException("invalid AID '$it' (5–16 bytes of hex)")) }
        return Result.success(out.distinct())
    }

    /**
     * The AIDs Android gets for one service: the app's [aids] plus the service's own [placeholder]
     * (registering replaces the static group, and the placeholder keeps it from being empty).
     */
    fun withPlaceholder(aids: List<String>, placeholder: String): List<String> = (aids + placeholder).distinct()

    /** `nfc.hceDeactivated` / `nfc.paymentDeactivated` reason for Android's deactivation code. */
    fun deactivationReason(linkLoss: Boolean): String = if (linkLoss) "linkLoss" else "deselected"
}

/**
 * Reader commands waiting for the holder's answer, one registry per card emulation
 * service. Only the app a command was sent to may answer it, and only once.
 */
class HcePending {
    private class Entry(val webAppId: String, val deferred: CompletableDeferred<ByteArray>)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** A new command for [webAppId]: its request id and the answer to wait for. Close it when done. */
    fun open(webAppId: String): Pair<String, CompletableDeferred<ByteArray>> {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ByteArray>()
        entries[id] = Entry(webAppId, deferred)
        return id to deferred
    }

    fun close(requestId: String) {
        entries.remove(requestId)
    }

    /** The holder's answer: false for an unknown, expired or already answered id, or another app. */
    fun respond(webAppId: String, requestId: String, response: ByteArray): Boolean {
        val e = entries[requestId]?.takeIf { it.webAppId == webAppId } ?: return false
        return e.deferred.complete(response)
    }

    val size: Int get() = entries.size
}
