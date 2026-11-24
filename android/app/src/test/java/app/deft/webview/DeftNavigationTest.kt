package app.deft.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PARITY G14: frames of approved embeds are left to the CSP; top-level rules are unchanged. */
class DeftNavigationTest {
    @Test
    fun subframeHttpsPassesOnlyWithApprovedEmbeds() {
        assertTrue(DeftNavigation.allowSubframe("https://js.stripe.com/v3/three-ds", isForMainFrame = false, embedsActive = true))
        assertTrue(DeftNavigation.allowSubframe("https://acs.bank.example/challenge", isForMainFrame = false, embedsActive = true))
        assertFalse(DeftNavigation.allowSubframe("https://js.stripe.com/v3/", isForMainFrame = false, embedsActive = false))
    }

    @Test
    fun mainFrameNeverPasses() {
        assertFalse(DeftNavigation.allowSubframe("https://js.stripe.com/", isForMainFrame = true, embedsActive = true))
    }

    @Test
    fun otherSchemesInFramesStillGoThroughDecide() {
        for (url in listOf("http://js.stripe.com/", "intent://x#Intent;end", "javascript:alert(1)", "tel:123", "file:///etc/hosts")) {
            assertFalse(url, DeftNavigation.allowSubframe(url, isForMainFrame = false, embedsActive = true))
        }
    }
}
