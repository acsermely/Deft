package app.deft.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PasskeyPolicyTest {
    private val appA = "abcdefghijklmnopqrst"
    private val appB = "bcdefghijklmnopqrstu"

    private fun create(rpId: String? = "example.com", extra: String = "") = buildString {
        append("{\"challenge\":\"AAEC\",")
        append(if (rpId == null) "\"rp\":{\"name\":\"Ex\"}," else "\"rp\":{\"id\":\"$rpId\",\"name\":\"Ex\"},")
        append("\"user\":{\"id\":\"AQ\",\"name\":\"a\",\"displayName\":\"A\"},\"pubKeyCredParams\":[{\"type\":\"public-key\",\"alg\":-7}]$extra}")
    }

    private fun get(rpId: String? = "example.com", extra: String = "") =
        if (rpId == null) "{\"challenge\":\"AAEC\"$extra}" else "{\"challenge\":\"AAEC\",\"rpId\":\"$rpId\"$extra}"

    private fun manifest(raw: String) = Json.parseToJsonElement(raw) as JsonObject

    @Test
    fun acceptsPlainDomains() {
        for (ok in listOf("example.com", "login.example.co.uk", "a-b.example", "x1.io", "app.acsermely.hu")) {
            assertTrue(ok, PasskeyPolicy.validRpId(ok))
        }
    }

    @Test
    fun rejectsUppercaseIpLocalAndAppOrigins() {
        val bad = listOf(
            "", "Example.com", "example", "localhost", "a.localhost", "10.0.0.1", "1.2.3.4", "printer.local",
            "foo.test", "abc.app.deft.internal", "deft.internal", "https://example.com", "example.com/", "-x.com",
            "x-.com", "a..com", "exa mple.com", "example.com:443", "a.123", "x".repeat(64) + ".com",
            ("a".repeat(63) + ".").repeat(4) + "com",
        )
        for (value in bad) assertFalse(value, PasskeyPolicy.validRpId(value))
    }

    @Test
    fun manifestRpIdsParse() {
        assertNull(PasskeyPolicy.parseManifest(null))
        assertEquals(listOf("example.com"), PasskeyPolicy.parseManifest(manifest("{\"rpIds\":[\"example.com\",\"example.com\"]}")))
        val bad = listOf(
            "{\"rpIds\":[]}", "{\"rpIds\":\"example.com\"}", "{}", "{\"rpIds\":[3]}", "{\"rpIds\":[\"Example.com\"]}",
            "{\"rpIds\":[\"a.com\",\"b.com\",\"c.com\",\"d.com\",\"e.com\"]}",
        )
        for (raw in bad) {
            try {
                PasskeyPolicy.parseManifest(manifest(raw))
                fail("accepted $raw")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun rpIdComesOnlyFromTheRequest() {
        assertEquals("example.com", (PasskeyPolicy.checkRequest(PasskeyPolicy.Kind.CREATE, create()) as PasskeyPolicy.Checked.Ok).rpId)
        assertEquals("example.com", (PasskeyPolicy.checkRequest(PasskeyPolicy.Kind.GET, get()) as PasskeyPolicy.Checked.Ok).rpId)
        // No default to the page host: a missing rpId is refused.
        assertTrue(PasskeyPolicy.checkRequest(PasskeyPolicy.Kind.CREATE, create(rpId = null)) is PasskeyPolicy.Checked.Bad)
        assertTrue(PasskeyPolicy.checkRequest(PasskeyPolicy.Kind.GET, get(rpId = null)) is PasskeyPolicy.Checked.Bad)
        // A create request's rpId is rp.id, a get request's is rpId; the other shape does not count.
        assertTrue(PasskeyPolicy.checkRequest(PasskeyPolicy.Kind.GET, create()) is PasskeyPolicy.Checked.Bad)
    }

    @Test
    fun refusesBadRequests() {
        val bad = listOf(
            PasskeyPolicy.Kind.CREATE to "not json",
            PasskeyPolicy.Kind.CREATE to "[]",
            PasskeyPolicy.Kind.CREATE to create(rpId = "Example.com"),
            PasskeyPolicy.Kind.CREATE to create(rpId = "abc.app.deft.internal"),
            PasskeyPolicy.Kind.CREATE to create().replace("\"user\":", "\"usr\":"),
            PasskeyPolicy.Kind.CREATE to create().replace("\"challenge\":\"AAEC\",", ""),
            PasskeyPolicy.Kind.GET to get(extra = ",\"mediation\":\"conditional\""),
            PasskeyPolicy.Kind.GET to get(rpId = "10.0.0.1"),
            PasskeyPolicy.Kind.CREATE to create().replace("\"name\":\"a\",", ""),
            PasskeyPolicy.Kind.CREATE to create().replace("\"name\":\"a\"", "\"name\":\"\""),
            PasskeyPolicy.Kind.GET to get(extra = ",\"extensions\":{\"appid\":\"https://evil.com\"}"),
            PasskeyPolicy.Kind.CREATE to create(extra = ",\"extensions\":{\"appidExclude\":\"https://evil.com\"}"),
            PasskeyPolicy.Kind.GET to get(extra = ",\"extensions\":[]"),
            PasskeyPolicy.Kind.GET to get(extra = ",\"pad\":\"${"x".repeat(PasskeyPolicy.MAX_REQUEST_BYTES)}\""),
        )
        for ((kind, raw) in bad) {
            assertTrue(raw.take(80), PasskeyPolicy.checkRequest(kind, raw) is PasskeyPolicy.Checked.Bad)
        }
    }

    @Test
    fun theProviderGetsWhatTheHostChecked() {
        // A key named twice: the host keeps one value, and the text handed on names only that one.
        val twice = "{\"challenge\":\"AAEC\",\"rpId\":\"evil.com\",\"rpId\":\"example.com\"}"
        val ok = PasskeyPolicy.checkRequest(PasskeyPolicy.Kind.GET, twice) as PasskeyPolicy.Checked.Ok
        val handedOn = Json.parseToJsonElement(ok.json) as JsonObject
        assertEquals(ok.rpId, (handedOn["rpId"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertFalse(ok.json, ok.json.contains("evil.com"))
        // Nested objects too.
        val nested = create().replace("\"rp\":{\"id\":\"example.com\"", "\"rp\":{\"id\":\"evil.com\",\"id\":\"example.com\"")
        val okNested = PasskeyPolicy.checkRequest(PasskeyPolicy.Kind.CREATE, nested) as PasskeyPolicy.Checked.Ok
        assertFalse(okNested.json, okNested.json.contains("evil.com"))
    }

    @Test
    fun originIdOnlyFromAppOrigins() {
        assertEquals(appA, PasskeyPolicy.originIdOf("https://$appA.app.deft.internal"))
        assertEquals("0123456789abcdef", PasskeyPolicy.originIdOf("https://0123456789abcdef.app.deft.internal"))
        assertNull(PasskeyPolicy.originIdOf("http://$appA.app.deft.internal"))
        assertNull(PasskeyPolicy.originIdOf("https://example.com"))
        assertNull(PasskeyPolicy.originIdOf("https://$appA.app.deft.internal.evil.com"))
        assertNull(PasskeyPolicy.originIdOf("https://a.b.app.deft.internal"))
    }

    @Test
    fun optInParse() {
        assertEquals(setOf(appA), PasskeyPolicy.parseOptIn("{\"passkeys\":{\"origins\":[\"$appA\",\"Not An Id\",3]}}"))
        assertNull(PasskeyPolicy.parseOptIn("{\"passkeys\":{}}"))
        assertNull(PasskeyPolicy.parseOptIn("{\"origins\":[\"$appA\"]}"))
        assertNull(PasskeyPolicy.parseOptIn("<html>"))
        val many = (1..65).joinToString(",") { "\"${appA.dropLast(2)}${"%02d".format(it)}\"" }
        assertNull(PasskeyPolicy.parseOptIn("{\"passkeys\":{\"origins\":[$many]}}"))
    }

    @Test
    fun appNotListedIsRefusedEvenWithTheRpIdInItsManifest() {
        val manifestRpIds = listOf("example.com")
        assertTrue(PasskeyPolicy.allowed("example.com", manifestRpIds, appA, setOf(appA)))
        // App A's opt-in does not cover app B.
        assertFalse(PasskeyPolicy.allowed("example.com", manifestRpIds, appB, setOf(appA)))
        // Listed by the website but the rpId is not in the app's manifest.
        assertFalse(PasskeyPolicy.allowed("other.com", manifestRpIds, appA, setOf(appA)))
        // No list could be read, or no origin id.
        assertFalse(PasskeyPolicy.allowed("example.com", manifestRpIds, appA, null))
        assertFalse(PasskeyPolicy.allowed("example.com", manifestRpIds, null, setOf(appA)))
    }

    @Test
    fun optInResponseMustBeADirectJson200() {
        assertNull(PasskeyPolicy.responseProblem(200, "application/json; charset=utf-8", 100))
        assertNull(PasskeyPolicy.responseProblem(200, "application/json", -1))
        assertNotNull(PasskeyPolicy.responseProblem(301, "application/json", 0))
        assertNotNull(PasskeyPolicy.responseProblem(302, null, 0))
        assertNotNull(PasskeyPolicy.responseProblem(404, "application/json", 0))
        assertNotNull(PasskeyPolicy.responseProblem(200, "text/html", 100))
        assertNotNull(PasskeyPolicy.responseProblem(200, null, 100))
        assertNotNull(PasskeyPolicy.responseProblem(200, "application/json", PasskeyPolicy.MAX_OPT_IN_BYTES + 1L))
        assertEquals("https://example.com/.well-known/deft.json", PasskeyPolicy.optInUrl("example.com"))
    }

    @Test
    fun facetOriginAndAudit() {
        val sha = ByteArray(32) { 0xfb.toByte() }
        assertEquals("android:apk-key-hash:" + "-_v7".repeat(10) + "-_s", PasskeyPolicy.facetOrigin(sha))
        assertEquals("{\"rpId\":\"example.com\"}", PasskeyPolicy.auditMetadata("example.com"))
    }
}
