package app.deft.bridge

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressTest {
    private fun v4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    /** 16 raw bytes, no DNS: getByAddress only wraps the bytes. */
    private fun v6(vararg parts: Int): InetAddress {
        require(parts.size == 8)
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[2 * i] = (parts[i] shr 8).toByte()
            out[2 * i + 1] = parts[i].toByte()
        }
        return InetAddress.getByAddress(out)
    }

    private fun mapped(a: Int, b: Int, c: Int, d: Int): InetAddress {
        val out = ByteArray(16)
        out[10] = 0xFF.toByte()
        out[11] = 0xFF.toByte()
        out[12] = a.toByte()
        out[13] = b.toByte()
        out[14] = c.toByte()
        out[15] = d.toByte()
        return InetAddress.getByAddress(out)
    }

    private fun assertClass(expected: LanAddress.Class, address: InetAddress) {
        assertEquals("${address.hostAddress}", expected, LanAddress.classify(address))
    }

    @Test
    fun rfc1918TenSlashEightEdges() {
        assertClass(LanAddress.Class.LAN, v4(10, 0, 0, 0))
        assertClass(LanAddress.Class.LAN, v4(10, 255, 255, 255))
        assertClass(LanAddress.Class.INTERNET, v4(9, 255, 255, 255))
        assertClass(LanAddress.Class.INTERNET, v4(11, 0, 0, 0))
    }

    @Test
    fun rfc1918OneSevenTwoSixteenEdges() {
        assertClass(LanAddress.Class.INTERNET, v4(172, 15, 255, 255))
        assertClass(LanAddress.Class.LAN, v4(172, 16, 0, 0))
        assertClass(LanAddress.Class.LAN, v4(172, 31, 255, 255))
        assertClass(LanAddress.Class.INTERNET, v4(172, 32, 0, 0))
    }

    @Test
    fun rfc1918OneNinetyTwoOneSixtyEightEdges() {
        assertClass(LanAddress.Class.INTERNET, v4(192, 167, 255, 255))
        assertClass(LanAddress.Class.LAN, v4(192, 168, 0, 0))
        assertClass(LanAddress.Class.LAN, v4(192, 168, 255, 255))
        assertClass(LanAddress.Class.INTERNET, v4(192, 169, 0, 0))
    }

    @Test
    fun linkLocalIPv4EdgesAreLan() {
        assertClass(LanAddress.Class.INTERNET, v4(169, 253, 255, 255))
        assertClass(LanAddress.Class.LAN, v4(169, 254, 0, 0))
        assertClass(LanAddress.Class.LAN, v4(169, 254, 255, 255))
        assertClass(LanAddress.Class.INTERNET, v4(169, 255, 0, 0))
    }

    @Test
    fun carrierNatIsNotLan() {
        assertClass(LanAddress.Class.INTERNET, v4(100, 63, 255, 255))
        assertClass(LanAddress.Class.INTERNET, v4(100, 64, 0, 0))
        assertClass(LanAddress.Class.INTERNET, v4(100, 127, 255, 255))
        assertClass(LanAddress.Class.INTERNET, v4(100, 128, 0, 0))
    }

    @Test
    fun loopbackMulticastUnspecifiedAndBroadcastAreRefused() {
        assertClass(LanAddress.Class.REFUSED, v4(127, 0, 0, 1))
        assertClass(LanAddress.Class.REFUSED, v4(127, 255, 255, 255))
        assertClass(LanAddress.Class.INTERNET, v4(126, 255, 255, 255))
        assertClass(LanAddress.Class.REFUSED, v4(224, 0, 0, 1))
        assertClass(LanAddress.Class.REFUSED, v4(239, 255, 255, 255))
        assertClass(LanAddress.Class.INTERNET, v4(223, 255, 255, 255))
        assertClass(LanAddress.Class.REFUSED, v4(0, 0, 0, 0))
        assertClass(LanAddress.Class.REFUSED, v4(0, 1, 2, 3))
        assertClass(LanAddress.Class.REFUSED, v4(255, 255, 255, 255))
    }

    @Test
    fun publicIPv4IsInternet() {
        assertClass(LanAddress.Class.INTERNET, v4(8, 8, 8, 8))
        assertClass(LanAddress.Class.INTERNET, v4(1, 1, 1, 1))
    }

    @Test
    fun ipv6UniqueLocalEdgesAreLan() {
        assertClass(LanAddress.Class.LAN, v6(0xFC00, 0, 0, 0, 0, 0, 0, 1))
        assertClass(LanAddress.Class.LAN, v6(0xFDFF, 0xFFFF, 0, 0, 0, 0, 0, 1))
        assertClass(LanAddress.Class.INTERNET, v6(0xFBFF, 0xFFFF, 0, 0, 0, 0, 0, 1))
        assertClass(LanAddress.Class.INTERNET, v6(0xFE00, 0, 0, 0, 0, 0, 0, 1))
    }

    @Test
    fun ipv6LinkLocalEdgesAreLan() {
        assertClass(LanAddress.Class.LAN, v6(0xFE80, 0, 0, 0, 0, 0, 0, 1))
        assertClass(LanAddress.Class.LAN, v6(0xFEBF, 0xFFFF, 0, 0, 0, 0, 0, 1))
        assertClass(LanAddress.Class.INTERNET, v6(0xFEC0, 0, 0, 0, 0, 0, 0, 1))
        assertClass(LanAddress.Class.INTERNET, v6(0xFE7F, 0xFFFF, 0, 0, 0, 0, 0, 1))
    }

    @Test
    fun ipv6LoopbackUnspecifiedAndMulticastAreRefused() {
        assertClass(LanAddress.Class.REFUSED, v6(0, 0, 0, 0, 0, 0, 0, 1))
        assertClass(LanAddress.Class.REFUSED, v6(0, 0, 0, 0, 0, 0, 0, 0))
        assertClass(LanAddress.Class.REFUSED, v6(0xFF02, 0, 0, 0, 0, 0, 0, 1))
        assertClass(LanAddress.Class.REFUSED, v6(0xFF00, 0, 0, 0, 0, 0, 0, 0))
    }

    @Test
    fun publicIPv6IsInternet() {
        assertClass(LanAddress.Class.INTERNET, v6(0x2001, 0xDB8, 0, 0, 0, 0, 0, 1))
        assertClass(LanAddress.Class.INTERNET, v6(0x2001, 0xDB8, 0, 0, 0, 0, 0, 0))
    }

    @Test
    fun ipv4MappedAddressesAreClassifiedByTheirIPv4Value() {
        assertClass(LanAddress.Class.LAN, mapped(10, 0, 0, 1))
        assertClass(LanAddress.Class.LAN, mapped(192, 168, 1, 1))
        assertClass(LanAddress.Class.LAN, mapped(169, 254, 1, 1))
        assertClass(LanAddress.Class.INTERNET, mapped(8, 8, 8, 8))
        assertClass(LanAddress.Class.INTERNET, mapped(100, 64, 0, 1))
        assertClass(LanAddress.Class.INTERNET, mapped(172, 32, 0, 1))
        assertClass(LanAddress.Class.REFUSED, mapped(127, 0, 0, 1))
        assertClass(LanAddress.Class.REFUSED, mapped(224, 0, 0, 1))
        assertClass(LanAddress.Class.REFUSED, mapped(0, 0, 0, 0))
    }

    @Test
    fun ipv4MappedLiteralFromTextIsClassifiedByIPv4Value() {
        // Literals only: getByName on an IP literal never performs a DNS lookup.
        assertTrue(LanAddress.isLan(InetAddress.getByName("::ffff:192.168.0.10")))
        assertFalse(LanAddress.isLan(InetAddress.getByName("::ffff:8.8.8.8")))
        assertEquals(LanAddress.Class.REFUSED, LanAddress.classify(InetAddress.getByName("::ffff:127.0.0.1")))
    }

    private fun nat64(a: Int, b: Int, c: Int, d: Int): InetAddress =
        v6(0x64, 0xFF9B, 0, 0, 0, 0, (a shl 8) or b, (c shl 8) or d)

    private fun compat(a: Int, b: Int, c: Int, d: Int): InetAddress =
        v6(0, 0, 0, 0, 0, 0, (a shl 8) or b, (c shl 8) or d)

    @Test
    fun nat64AddressesAreClassifiedByTheirEmbeddedIPv4Value() {
        assertClass(LanAddress.Class.LAN, nat64(10, 0, 0, 1))
        assertClass(LanAddress.Class.LAN, nat64(192, 168, 1, 1))
        assertClass(LanAddress.Class.LAN, nat64(169, 254, 1, 1))
        assertClass(LanAddress.Class.REFUSED, nat64(127, 0, 0, 1))
        assertClass(LanAddress.Class.REFUSED, nat64(0, 0, 0, 0))
        assertClass(LanAddress.Class.REFUSED, nat64(224, 0, 0, 1))
        assertClass(LanAddress.Class.INTERNET, nat64(8, 8, 8, 8))
    }

    @Test
    fun nat64PrefixIsExactlyTheSlash96() {
        // Bits 32..95 must be zero: 64:ff9b:1:: is a different (local-use) prefix and stays plain IPv6.
        assertClass(LanAddress.Class.INTERNET, v6(0x64, 0xFF9B, 1, 0, 0, 0, 0x0A00, 1))
        assertClass(LanAddress.Class.INTERNET, v6(0x64, 0xFF9B, 0, 0, 0, 1, 0x0A00, 1))
        assertClass(LanAddress.Class.INTERNET, v6(0x65, 0xFF9B, 0, 0, 0, 0, 0x0A00, 1))
    }

    @Test
    fun ipv4CompatibleAddressesAreClassifiedByTheirIPv4Value() {
        assertClass(LanAddress.Class.LAN, compat(10, 1, 2, 3))
        assertClass(LanAddress.Class.LAN, compat(172, 16, 0, 1))
        assertClass(LanAddress.Class.REFUSED, compat(127, 0, 0, 1))
        assertClass(LanAddress.Class.REFUSED, compat(0, 0, 0, 5))
        assertClass(LanAddress.Class.INTERNET, compat(8, 8, 4, 4))
    }

    @Test
    fun ownAddressMatchesThroughNat64AndCompatibleForms() {
        val own = listOf(v4(192, 168, 1, 7))
        assertTrue(LanAddress.isOwn(nat64(192, 168, 1, 7), own))
        assertTrue(LanAddress.isOwn(compat(192, 168, 1, 7), own))
        assertFalse(LanAddress.isOwn(nat64(192, 168, 1, 8), own))
    }

    @Test
    fun isLanIsTrueOnlyForLanClass() {
        assertTrue(LanAddress.isLan(v4(10, 1, 2, 3)))
        assertFalse(LanAddress.isLan(v4(127, 0, 0, 1)))
        assertFalse(LanAddress.isLan(v4(100, 64, 0, 1)))
        assertFalse(LanAddress.isLan(v6(0x2001, 0xDB8, 0, 0, 0, 0, 0, 1)))
    }
}
