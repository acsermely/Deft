package app.deft.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NetFetchSpecTest {
    private fun ip(s: String) = InetAddress.getByName(s)
    private fun target(s: String) = NetFetchSpec.parseUrl(s)!!

    @Test
    fun parseUrlAcceptsHttpsOnlyWithoutUserInfo() {
        val t = target("HTTPS://Example.COM/feed.xml?x=1#frag")
        assertEquals("example.com", t.host)
        assertEquals(443, t.port)
        assertEquals("/feed.xml?x=1", t.path)
        assertEquals("https://example.com", t.origin)
        assertEquals("https://example.com:8443", target("https://example.com:8443").origin)
        assertEquals("/", target("https://example.com").path)
        assertNull(NetFetchSpec.parseUrl("http://example.com/"))
        assertNull(NetFetchSpec.parseUrl("https://user:pw@example.com/"))
        assertNull(NetFetchSpec.parseUrl("ftp://example.com/"))
        assertNull(NetFetchSpec.parseUrl("https://example.com:0/"))
        assertNull(NetFetchSpec.parseUrl("https://example.com/a b"))
        assertNull(NetFetchSpec.parseUrl("//example.com/"))
        assertNull(NetFetchSpec.parseUrl(null))
        assertNull(NetFetchSpec.parseUrl("https://example.com/" + "a".repeat(NetFetchSpec.MAX_URL)))
    }

    @Test
    fun originPolicy() {
        val t = target("https://api.example.com/x")
        assertTrue(NetFetchSpec.originAllowed(t, listOf("https://api.example.com"), open = false))
        assertTrue(NetFetchSpec.originAllowed(t, listOf("HTTPS://API.example.com:443"), open = false))
        assertFalse(NetFetchSpec.originAllowed(t, listOf("https://example.com"), open = false))
        assertFalse(NetFetchSpec.originAllowed(target("https://api.example.com:8443/"), listOf("https://api.example.com"), open = false))
        assertFalse(NetFetchSpec.originAllowed(t, emptyList(), open = false))
        assertTrue(NetFetchSpec.originAllowed(t, emptyList(), open = true))
    }

    @Test
    fun methodAndMode() {
        assertEquals("GET", NetFetchSpec.method(null))
        assertEquals("GET", NetFetchSpec.method("get"))
        assertEquals("HEAD", NetFetchSpec.method("HEAD"))
        assertNull(NetFetchSpec.method("POST"))
        assertEquals("text", NetFetchSpec.asMode(null))
        assertEquals("json", NetFetchSpec.asMode("json"))
        assertNull(NetFetchSpec.asMode("xml"))
    }

    @Test
    fun headerAllowlist() {
        val ok = NetFetchSpec.headers(mapOf("Accept" to "application/json", "If-None-Match" to "\"abc\""))
        assertEquals(mapOf("accept" to "application/json", "if-none-match" to "\"abc\""), (ok as NetFetchSpec.Headers.Ok).values)
        for (bad in listOf("Cookie", "Host", "Authorization", "Origin", "Referer", "Proxy-Authorization", "X-Custom", "Accept\r\nX")) {
            assertTrue(bad, NetFetchSpec.headers(mapOf(bad to "v")) is NetFetchSpec.Headers.Refused)
        }
        assertTrue(NetFetchSpec.headers(mapOf("accept" to "a\r\nCookie: x")) is NetFetchSpec.Headers.Refused)
    }

    @Test
    fun redirectsResolveAgainstTheCurrentUrlAndStayHttps() {
        val from = target("https://example.com/a/b?q=1")
        assertEquals("https://example.com/c", NetFetchSpec.redirectTarget(from, "/c")!!.url)
        assertEquals("https://example.com/a/c", NetFetchSpec.redirectTarget(from, "c")!!.url)
        assertEquals("https://other.org/x", NetFetchSpec.redirectTarget(from, "https://other.org/x")!!.url)
        assertNull(NetFetchSpec.redirectTarget(from, "http://example.com/x"))
        assertNull(NetFetchSpec.redirectTarget(from, null))
        assertNull(NetFetchSpec.redirectTarget(from, ""))
        assertNull(NetFetchSpec.redirectTarget(from, "https://u:p@example.com/"))
        // A redirect to another origin is re-checked: not allowed unless listed.
        val hop = NetFetchSpec.redirectTarget(from, "https://other.org/x")!!
        assertFalse(NetFetchSpec.originAllowed(hop, listOf("https://example.com"), open = false))
        assertTrue(NetFetchSpec.isRedirect(307))
        assertFalse(NetFetchSpec.isRedirect(304))
    }

    @Test
    fun addressAdmission() {
        val a = NetFetchSpec::admit
        assertEquals(NetFetchSpec.Verdict.OK, a(listOf(ip("93.184.216.34")), false, emptyList()))
        assertEquals(NetFetchSpec.Verdict.OK, a(listOf(ip("2606:2800:220:1::1")), false, emptyList()))
        assertEquals(NetFetchSpec.Verdict.EMPTY, a(emptyList(), false, emptyList()))
        for (bad in listOf("127.0.0.1", "0.0.0.0", "::1", "::", "224.0.0.1", "ff02::1", "255.255.255.255", "::ffff:127.0.0.1")) {
            // Refused even with net.local.
            assertEquals(bad, NetFetchSpec.Verdict.REFUSED, a(listOf(ip(bad)), true, emptyList()))
        }
        for (lan in listOf("10.0.0.5", "172.16.1.1", "172.31.255.255", "192.168.1.1", "169.254.169.254", "fd00::1", "fe80::1", "::ffff:192.168.0.1")) {
            assertEquals(lan, NetFetchSpec.Verdict.NEEDS_LOCAL, a(listOf(ip(lan)), false, emptyList()))
            assertEquals(lan, NetFetchSpec.Verdict.OK, a(listOf(ip(lan)), true, emptyList()))
        }
        assertEquals(NetFetchSpec.Verdict.OK, a(listOf(ip("172.32.0.1")), false, emptyList()))
        // One bad address among good ones rejects the name (DNS rebinding with a mixed answer).
        assertEquals(NetFetchSpec.Verdict.REFUSED, a(listOf(ip("93.184.216.34"), ip("127.0.0.1")), true, emptyList()))
        assertEquals(NetFetchSpec.Verdict.NEEDS_LOCAL, a(listOf(ip("93.184.216.34"), ip("10.0.0.1")), false, emptyList()))
        // The phone's own address.
        assertEquals(NetFetchSpec.Verdict.REFUSED, a(listOf(ip("192.168.1.7")), true, listOf(ip("192.168.1.7"))))
    }

    @Test
    fun requestBytesCarryNoCookieOrCredentials() {
        val text = String(NetFetchSpec.requestBytes(target("https://example.com:8443/p?q=1"), "GET", mapOf("accept" to "text/html")), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("GET /p?q=1 HTTP/1.1\r\nHost: example.com:8443\r\n"))
        assertTrue("accept: text/html" in text)
        assertFalse("*/*" in text)
        assertFalse("cookie" in text.lowercase())
        assertTrue(text.endsWith("Connection: close\r\n\r\n"))
    }

    private fun read(raw: String, head: Boolean = false, max: Int = NetFetchSpec.MAX_BYTES) =
        NetFetchSpec.readResponse(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)), head, max)

    @Test
    fun readsContentLengthBodyAndDropsSetCookie() {
        val r = read("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nSet-Cookie: a=b\r\nX-A: 1\r\nX-A: 2\r\nContent-Length: 5\r\n\r\nhelloEXTRA")
        assertEquals(200, r.status)
        assertEquals("hello", String(r.body))
        assertEquals("text/plain", r.headers["content-type"])
        assertEquals("1, 2", r.headers["x-a"])
        assertFalse("set-cookie" in r.headers)
    }

    @Test
    fun readsChunkedAndReadToClose() {
        assertEquals("Wikipedia", String(read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n5;ext=1\r\npedia\r\n0\r\nX-T: 1\r\n\r\n").body))
        assertEquals("tail", String(read("HTTP/1.1 200 OK\r\n\r\ntail").body))
    }

    @Test
    fun headAndNoBodyStatusesReadNoBody() {
        assertEquals(0, read("HTTP/1.1 200 OK\r\nContent-Length: 99\r\n\r\n", head = true).body.size)
        assertEquals(0, read("HTTP/1.1 304 Not Modified\r\nContent-Length: 99\r\n\r\n").body.size)
        assertEquals("ok", String(read("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok").body))
    }

    @Test
    fun responseCapAbortsEveryWay() {
        fun tooLarge(raw: String) = try {
            read(raw, max = 10)
            fail("expected TooLarge")
        } catch (_: NetFetchSpec.TooLarge) {
        }
        tooLarge("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n01234567890")
        tooLarge("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n6\r\n012345\r\n6\r\n012345\r\n0\r\n\r\n")
        tooLarge("HTTP/1.1 200 OK\r\n\r\n01234567890")
        assertEquals(10, read("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n0123456789", max = 10).body.size)
    }

    @Test
    fun gzipBodyIsDecodedAndCappedAfterDecoding() {
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write("a".repeat(100).toByteArray()) } }.toByteArray()
        val head = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${gz.size}\r\n\r\n".toByteArray()
        val raw = head + gz
        assertArrayEquals("a".repeat(100).toByteArray(), NetFetchSpec.readResponse(ByteArrayInputStream(raw), false, 1000).body)
        try {
            NetFetchSpec.readResponse(ByteArrayInputStream(raw), false, 50)
            fail("gzip bomb not capped")
        } catch (_: NetFetchSpec.TooLarge) {
        }
    }

    @Test
    fun malformedResponsesAreRejected() {
        for (raw in listOf("garbage\r\n\r\n", "HTTP/1.1 abc OK\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n")) {
            try {
                read(raw)
                fail(raw)
            } catch (_: NetFetchSpec.Malformed) {
            }
        }
    }
}
