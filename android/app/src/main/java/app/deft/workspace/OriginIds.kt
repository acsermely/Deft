package app.deft.workspace

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Origin ids for apps served at `https://<origin-id>.app.deft.internal` (PARITY H2, spec 8.3).
 *
 * The slug is the human name; the origin id is not. It decides the app's origin, storage and
 * WebView profile, so it is fixed once per app row and never chosen by the app:
 * - local apps: [LENGTH] random base32 characters,
 * - packaged apps: the first [LENGTH] base32 characters of `SHA-256(author_key || "/" || id)`.
 *
 * Bundled apps use 16 hex characters ([BundledApps.originId]), so the lengths alone keep the
 * two families apart.
 */
object OriginIds {
    const val LENGTH = 20
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private val random = SecureRandom()

    fun local(): String = String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    fun packaged(authorKey: String, id: String): String =
        base32(MessageDigest.getInstance("SHA-256").digest("${authorKey.trim()}/$id".toByteArray(Charsets.UTF_8)))
            .take(LENGTH)

    fun isValid(value: String): Boolean = value.length == LENGTH && value.all { it in ALPHABET }

    private fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }
}
