package app.deft.data

import app.deft.data.NetworkConsent.LEGACY
import app.deft.data.NetworkConsent.RECORDED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkConsentTest {
    private val a = "https://a.example.com"
    private val b = "https://b.example.com"

    @Test
    fun newAppServesNothingUntilConsent() {
        val r = NetworkConsent.resolve(listOf(a), emptyList())
        assertEquals(emptyList<String>(), r.served)
        assertEquals(listOf(a), r.pending)
        assertNull(r.rewrite)
    }

    @Test
    fun addedOriginIsPendingNotServed() {
        val r = NetworkConsent.resolve(listOf(a, b), listOf(RECORDED, a))
        assertEquals(listOf(a), r.served)
        assertEquals(listOf(b), r.pending)
        assertNull(r.rewrite)
    }

    @Test
    fun shrinkingRevokesSoReaddingAsksAgain() {
        val shrunk = NetworkConsent.resolve(listOf(a), listOf(RECORDED, a, b))
        assertEquals(listOf(RECORDED, a), shrunk.rewrite)
        val readded = NetworkConsent.resolve(listOf(a, b), shrunk.rewrite!!)
        assertEquals(listOf(b), readded.pending)
    }

    @Test
    fun legacyAppAdoptsItsManifestOnce() {
        val r = NetworkConsent.resolve(listOf(a), listOf(LEGACY))
        assertEquals(listOf(a), r.served)
        assertEquals(emptyList<String>(), r.pending)
        assertEquals(listOf(RECORDED, a), r.rewrite)
    }

    @Test
    fun consentAllowsOnlyWhatWasShown() {
        // b was added after the screen was drawn; it must not ride along.
        val rows = NetworkConsent.afterConsent(listOf(a, b), listOf(RECORDED), shown = listOf(a))
        assertEquals(listOf(RECORDED, a), rows)
        assertEquals(listOf(b), NetworkConsent.resolve(listOf(a, b), rows).pending)
    }

    @Test
    fun recordedEmptySetMeansNothingAllowed() {
        val r = NetworkConsent.resolve(emptyList(), listOf(RECORDED))
        assertEquals(emptyList<String>(), r.served)
        assertNull(r.rewrite)
    }

    @Test
    fun userOriginUsesTheManifestRule() {
        assertEquals("https://home.example.net", NetworkConsent.userOrigin("  https://Home.Example.net/ "))
        assertEquals("https://home.example.net:8443", NetworkConsent.userOrigin("https://home.example.net:8443"))
        for (bad in listOf(
            "http://home.example.net",
            "home.example.net",
            "https://localhost",
            "https://192.168.1.10",
            "https://app.deft.internal",
            "https://x.app.deft.internal",
            "https://home.example.net/path",
            "https://*.example.net",
            "https://home.example.net; script-src *",
            "https://nodot",
            "",
        )) {
            assertNull(bad, NetworkConsent.userOrigin(bad))
        }
    }

    @Test
    fun userServersServeOnlyWithTheGrant() {
        val home = "https://home.example.net"
        assertEquals(listOf(a), NetworkConsent.served(listOf(a), listOf(home), userGranted = false))
        assertEquals(listOf(a, home), NetworkConsent.served(listOf(a), listOf(home), userGranted = true))
        assertEquals(listOf(a), NetworkConsent.served(listOf(a), listOf(a), userGranted = true))
    }

    // Embed origins are approved one by one.
    private val s = "https://js.stripe.com"
    private val p = "https://*.paypal.com"

    @Test
    fun embedsArePendingUntilDecided() {
        val r = EmbedConsent.resolve(listOf(s, p), emptyList(), emptyList())
        assertEquals(emptyList<String>(), r.approved)
        assertEquals(listOf(s, p), r.pending)
        assertNull(r.rewrite)
    }

    @Test
    fun untickedEmbedIsRefusedAndNotAskedAgain() {
        val (approved, refused) = EmbedConsent.afterConsent(listOf(s, p), emptyList(), emptyList(), shown = listOf(s, p), ticked = listOf(s))
        assertEquals(listOf(s), approved)
        assertEquals(listOf(p), refused)
        val r = EmbedConsent.resolve(listOf(s, p), approved, refused)
        assertEquals(listOf(s), r.approved)
        assertEquals(emptyList<String>(), r.pending)
        assertNull(r.rewrite)
    }

    @Test
    fun newEmbedIsAskedAndEarlierChoicesKept() {
        val a = "https://a.example.com"
        val r = EmbedConsent.resolve(listOf(s, p, a), listOf(s), listOf(p))
        assertEquals(listOf(a), r.pending)
        val (approved, refused) = EmbedConsent.afterConsent(listOf(s, p, a), listOf(s), listOf(p), shown = listOf(a), ticked = emptyList())
        assertEquals(listOf(s), approved)
        assertEquals(listOf(p, a), refused)
    }

    @Test
    fun droppedEmbedIsForgottenSoReaddingAsksAgain() {
        val r = EmbedConsent.resolve(listOf(p), listOf(s), listOf(p))
        assertEquals(emptyList<String>(), r.approved)
        assertEquals(emptyList<String>() to listOf(p), r.rewrite)
        val readded = EmbedConsent.resolve(listOf(s, p), r.rewrite!!.first, r.rewrite!!.second)
        assertEquals(listOf(s), readded.pending)
    }

    @Test
    fun dashboardToggleOnlyTouchesDeclaredOrigins() {
        assertEquals(listOf(s, p) to emptyList<String>(), EmbedConsent.toggle(listOf(s, p), listOf(s), listOf(p), p, allow = true))
        assertEquals(emptyList<String>() to listOf(s, p), EmbedConsent.toggle(listOf(s, p), listOf(s), listOf(p), s, allow = false))
        // An origin the manifest does not list cannot be approved from outside.
        assertEquals(listOf(s) to listOf(p), EmbedConsent.toggle(listOf(s, p), listOf(s), listOf(p), "https://evil.example.com", allow = true))
    }
}
