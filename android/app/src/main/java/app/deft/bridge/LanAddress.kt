package app.deft.bridge

import java.net.InetAddress

/**
 * Which addresses a socket may connect to. Pure (no Android types) so every range
 * edge can be unit tested.
 *
 * - [Class.LAN]: RFC 1918 (10/8, 172.16/12, 192.168/16), IPv4 link-local 169.254/16, IPv6 ULA
 *   fc00::/7 and link-local fe80::/10. Allowed with `net.local`.
 * - [Class.INTERNET]: any other unicast address, including 100.64/10 (carrier NAT) and IPv6
 *   site-local fec0::/10. Needs `net.sockets` and `network.open`.
 * - [Class.REFUSED]: loopback, multicast, unspecified ("this network" 0/8) and broadcast.
 *   [NetSpec.admit] also refuses the phone's own addresses ([isOwn]).
 *
 * An IPv6 address that embeds an IPv4 one is classified by that IPv4 value: IPv4-mapped (`::ffff:a.b.c.d`),
 * NAT64 (`64:ff9b::/96`, RFC 6052) and the deprecated IPv4-compatible form (`::a.b.c.d`). Otherwise a
 * NAT64 gateway or a stack that still honours `::a.b.c.d` would reach a LAN or loopback address that the
 * IPv6 rules call internet.
 */
object LanAddress {
    enum class Class { LAN, INTERNET, REFUSED }

    fun classify(address: InetAddress): Class {
        val raw = address.address
        val bytes = plain(raw)
        return when (bytes.size) {
            4 -> classifyV4(bytes)
            16 -> classifyV6(bytes)
            else -> Class.REFUSED
        }
    }

    fun isLan(address: InetAddress): Boolean = classify(address) == Class.LAN

    /**
     * Review: true when [address] is one of the phone's own addresses ([own], a snapshot of its
     * network interfaces), compared by value with IPv4-mapped addresses unwrapped. A socket to
     * the phone itself would reach adb over Wi-Fi or another app's local server.
     */
    fun isOwn(address: InetAddress, own: Collection<InetAddress>): Boolean {
        val bytes = plain(address.address)
        return own.any { plain(it.address).contentEquals(bytes) }
    }

    /** The embedded IPv4 bytes of a 16-byte address that carries one (mapped, NAT64, IPv4-compatible); [raw] otherwise. */
    private fun plain(raw: ByteArray): ByteArray =
        if (raw.size == 16 && (isMapped(raw) || isNat64(raw) || isCompatible(raw))) raw.copyOfRange(12, 16) else raw

    private fun classifyV4(b: ByteArray): Class {
        val a = b[0].toInt() and 0xFF
        val c = b[1].toInt() and 0xFF
        return when {
            a == 0 -> Class.REFUSED                                        // 0.0.0.0/8
            a == 127 -> Class.REFUSED                                      // loopback
            a in 224..239 -> Class.REFUSED                                 // multicast
            a == 255 && b.all { it == 0xFF.toByte() } -> Class.REFUSED     // broadcast
            a == 10 -> Class.LAN
            a == 172 && (c and 0xF0) == 16 -> Class.LAN                    // 172.16.0.0/12
            a == 192 && c == 168 -> Class.LAN
            a == 169 && c == 254 -> Class.LAN                              // link-local
            else -> Class.INTERNET
        }
    }

    private fun classifyV6(b: ByteArray): Class {
        if (b.all { it == 0.toByte() }) return Class.REFUSED               // ::
        if (b.take(15).all { it == 0.toByte() } && b[15] == 1.toByte()) return Class.REFUSED // ::1
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        return when {
            first == 0xFF -> Class.REFUSED                                 // ff00::/8 multicast
            (first and 0xFE) == 0xFC -> Class.LAN                          // fc00::/7 ULA
            first == 0xFE && (second and 0xC0) == 0x80 -> Class.LAN        // fe80::/10 link-local
            else -> Class.INTERNET
        }
    }

    /** 64:ff9b::/96 */
    private fun isNat64(b: ByteArray): Boolean {
        if (b[0] != 0.toByte() || b[1] != 0x64.toByte() || b[2] != 0xFF.toByte() || b[3] != 0x9B.toByte()) return false
        for (i in 4 until 12) if (b[i] != 0.toByte()) return false
        return true
    }

    /** ::a.b.c.d (the first 96 bits zero; `::` and `::1` fall here too and classify as refused through 0/8). */
    private fun isCompatible(b: ByteArray): Boolean {
        for (i in 0 until 12) if (b[i] != 0.toByte()) return false
        return true
    }

    /** ::ffff:0:0/96 */
    private fun isMapped(b: ByteArray): Boolean {
        for (i in 0 until 10) if (b[i] != 0.toByte()) return false
        return b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
    }
}
