package app.deft.bridge

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PARITY G7: `device.id`. HMAC-SHA256 of the web origin, keyed with the launcher's secret, as hex.
 * The same origin gets the same id on this phone; other origins get different ids, and the
 * secret never leaves the launcher.
 */
object DeviceIdSpec {
    const val LENGTH = 32

    fun derive(secret: ByteArray, origin: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val digest = mac.doFinal(origin.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) hex.append(((b.toInt() shr 4) and 0xF).toString(16)).append((b.toInt() and 0xF).toString(16))
        return hex.substring(0, LENGTH)
    }
}
