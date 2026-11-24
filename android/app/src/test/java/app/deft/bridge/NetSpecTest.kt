package app.deft.bridge

import java.net.InetAddress
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NetSpecTest {
    private fun addr(ip: String): InetAddress = InetAddress.getByName(ip)

    @Test
    fun serviceTypeNeedsUnderscoreNameAndProtocol() {
        assertEquals("_http._tcp.", NetSpec.serviceType("_http._tcp"))
        assertEquals("_http._tcp.", NetSpec.serviceType("_HTTP._tcp."))
        assertEquals("_ipp._udp.", NetSpec.serviceType(" _ipp._udp "))
        assertNull(NetSpec.serviceType("http._tcp"))
        assertNull(NetSpec.serviceType("_http._sctp"))
        assertNull(NetSpec.serviceType("_http"))
        assertNull(NetSpec.serviceType("_" + "a".repeat(16) + "._tcp"))
        assertNull(NetSpec.serviceType(null))
    }

    @Test
    fun hostAcceptsNamesAndLiteralsOnly() {
        assertEquals("printer.local", NetSpec.host("printer.local"))
        assertEquals("192.168.1.20", NetSpec.host("192.168.1.20"))
        assertEquals("fe80::1%wlan0", NetSpec.host("fe80::1%wlan0"))
        assertNull(NetSpec.host(""))
        assertNull(NetSpec.host("http://host"))
        assertNull(NetSpec.host("host/path"))
        assertNull(NetSpec.host("a b"))
        assertNull(NetSpec.host("x".repeat(254)))
        assertNull(NetSpec.host(null))
    }

    @Test
    fun portRange() {
        assertEquals(1, NetSpec.port("1"))
        assertEquals(65535, NetSpec.port("65535"))
        assertNull(NetSpec.port("0"))
        assertNull(NetSpec.port("65536"))
        assertNull(NetSpec.port("80a"))
        assertNull(NetSpec.port(null))
    }

    @Test
    fun udpListenNeedsUnprivilegedPort() {
        assertNull(NetSpec.udpListenPort("1023"))
        assertEquals(1024, NetSpec.udpListenPort("1024"))
        assertEquals(65535, NetSpec.udpListenPort("65535"))
        assertNull(NetSpec.udpListenPort("65536"))
    }

    @Test
    fun timeoutDefaultsAndRange() {
        assertEquals(NetSpec.DEFAULT_TIMEOUT_MS, NetSpec.timeoutMs(null))
        assertEquals(1, NetSpec.timeoutMs("1"))
        assertEquals(NetSpec.MAX_TIMEOUT_MS, NetSpec.timeoutMs("30000"))
        assertNull(NetSpec.timeoutMs("0"))
        assertNull(NetSpec.timeoutMs("30001"))
    }

    @Test
    fun sendPayloadLimitIs64KiB() {
        val exact = ByteArray(NetSpec.MAX_SEND_BYTES)
        val encoded = NetSpec.encode(exact)
        assertEquals(exact.size, NetSpec.decodeSend(encoded)?.size)
        assertNull(NetSpec.decodeSend(NetSpec.encode(ByteArray(NetSpec.MAX_SEND_BYTES + 1))))
        assertNull(NetSpec.decodeSend("not base64!!"))
        assertNull(NetSpec.decodeSend(null))
    }

    @Test
    fun sendPayloadAcceptsWrappedBase64() {
        val bytes = "hello".toByteArray()
        assertEquals("hello", NetSpec.decodeSend("aGVs\nbG8=")?.toString(Charsets.UTF_8))
        assertEquals("hello", NetSpec.decodeSend(NetSpec.encode(bytes))?.toString(Charsets.UTF_8))
    }

    @Test
    fun admitAllLanIsOk() {
        assertEquals(
            NetSpec.Verdict.OK,
            NetSpec.admit(listOf(addr("10.0.0.5"), addr("192.168.1.9")), allowInternet = false),
        )
    }

    @Test
    fun admitRejectsEmptyList() {
        assertEquals(NetSpec.Verdict.EMPTY, NetSpec.admit(emptyList(), allowInternet = true))
    }

    @Test
    fun admitRefusesIfAnyAddressIsRefused() {
        // One good LAN address does not save a name that also resolves to loopback.
        assertEquals(
            NetSpec.Verdict.REFUSED,
            NetSpec.admit(listOf(addr("10.0.0.5"), addr("127.0.0.1")), allowInternet = true),
        )
        assertEquals(
            NetSpec.Verdict.REFUSED,
            NetSpec.admit(listOf(addr("8.8.8.8"), addr("224.0.0.1")), allowInternet = true),
        )
    }

    @Test
    fun admitRefusesThePhonesOwnAddresses() {
        val own = listOf(addr("192.168.1.20"), addr("fe80::1"), addr("2001:db8::7"))
        assertEquals(NetSpec.Verdict.REFUSED, NetSpec.admit(listOf(addr("192.168.1.20")), allowInternet = false, own = own))
        assertEquals(NetSpec.Verdict.REFUSED, NetSpec.admit(listOf(addr("::ffff:192.168.1.20")), allowInternet = false, own = own))
        assertEquals(NetSpec.Verdict.REFUSED, NetSpec.admit(listOf(addr("fe80::1")), allowInternet = false, own = own))
        assertEquals(NetSpec.Verdict.REFUSED, NetSpec.admit(listOf(addr("2001:db8::7")), allowInternet = true, own = own))
        assertEquals(
            NetSpec.Verdict.REFUSED,
            NetSpec.admit(listOf(addr("192.168.1.9"), addr("192.168.1.20")), allowInternet = false, own = own),
        )
        assertEquals(NetSpec.Verdict.OK, NetSpec.admit(listOf(addr("192.168.1.9")), allowInternet = false, own = own))
    }

    @Test
    fun admitNeedsInternetGrantForAnyNonLanAddress() {
        assertEquals(
            NetSpec.Verdict.NEEDS_INTERNET,
            NetSpec.admit(listOf(addr("10.0.0.5"), addr("8.8.8.8")), allowInternet = false),
        )
        assertEquals(
            NetSpec.Verdict.OK,
            NetSpec.admit(listOf(addr("10.0.0.5"), addr("8.8.8.8")), allowInternet = true),
        )
        assertEquals(
            NetSpec.Verdict.NEEDS_INTERNET,
            NetSpec.admit(listOf(addr("100.64.0.1")), allowInternet = false),
        )
    }

    @Test
    fun argSchemaRejectsMissingSocketAndOversizeBase64() {
        assertNotNull(BridgeArgs.validate("net.tcpSend", buildJsonObject { put("base64", JsonPrimitive("AA==")) }))
        assertNotNull(
            BridgeArgs.validate(
                "net.tcpSend",
                buildJsonObject {
                    put("socketId", JsonPrimitive("s1"))
                    put("base64", JsonPrimitive("A".repeat(88_001)))
                },
            ),
        )
        assertNull(
            BridgeArgs.validate(
                "net.tcpSend",
                buildJsonObject {
                    put("socketId", JsonPrimitive("s1"))
                    put("base64", JsonPrimitive("AA=="))
                },
            ),
        )
        assertNotNull(BridgeArgs.validate("net.tcpConnect", buildJsonObject { put("host", JsonPrimitive("a")) }))
        assertNull(
            BridgeArgs.validate(
                "net.tcpConnect",
                buildJsonObject {
                    put("host", JsonPrimitive("10.0.0.5"))
                    put("port", JsonPrimitive("80"))
                },
            ),
        )
    }
}
