package app.deft.special

import app.deft.catalog.Capability
import app.deft.catalog.GrantLevel
import app.deft.catalog.Risk
import app.deft.special.SpecialReview.Action
import app.deft.special.SpecialReview.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialReviewTest {
    private val t0 = 1_700_000_000_000L

    @Test
    fun freshGrantDoesNothing() {
        assertEquals(Action.NONE, SpecialReview.decide(t0, null, null, t0 + 29 * DAY_MS))
    }

    @Test
    fun remindsAfterThirtyIdleDays() {
        assertEquals(Action.REMIND, SpecialReview.decide(t0, null, null, t0 + 30 * DAY_MS))
    }

    @Test
    fun recentUseResetsTheClock() {
        val used = t0 + 70 * DAY_MS
        assertEquals(Action.NONE, SpecialReview.decide(t0, used, null, t0 + 95 * DAY_MS))
        assertEquals(Action.REMIND, SpecialReview.decide(t0, used, null, used + 30 * DAY_MS))
    }

    @Test
    fun remindsOncePerThirtyDays() {
        val reminded = t0 + 30 * DAY_MS
        assertEquals(Action.NONE, SpecialReview.decide(t0, null, reminded, t0 + 31 * DAY_MS))
        assertEquals(Action.NONE, SpecialReview.decide(t0, null, reminded, t0 + 59 * DAY_MS))
        assertEquals(Action.REMIND, SpecialReview.decide(t0, null, reminded, t0 + 60 * DAY_MS))
    }

    @Test
    fun reminderBeforeLastUseDoesNotCount() {
        val reminded = t0 + 30 * DAY_MS
        val used = t0 + 40 * DAY_MS
        assertEquals(Action.REMIND, SpecialReview.decide(t0, used, reminded, used + 30 * DAY_MS))
    }

    @Test
    fun revokesAfterNinetyIdleDays() {
        assertEquals(Action.REVOKE, SpecialReview.decide(t0, null, t0 + 60 * DAY_MS, t0 + 90 * DAY_MS))
        assertEquals(Action.REVOKE, SpecialReview.decide(t0, t0 - DAY_MS, null, t0 + 200 * DAY_MS))
    }

    @Test
    fun keepRestartsTheClock() {
        val kept = t0 + 89 * DAY_MS
        assertEquals(Action.NONE, SpecialReview.decide(kept, null, null, t0 + 100 * DAY_MS))
    }

    @Test
    fun clockGoingBackwardsDoesNothing() {
        assertEquals(Action.NONE, SpecialReview.decide(t0, null, null, t0 - 100 * DAY_MS))
    }

    @Test
    fun everySpecialCapabilityHasAWarning() {
        val special = Capability.entries.filter { it.risk == Risk.SPECIAL }
        assertTrue(special.isNotEmpty())
        for (cap in special) assertTrue("${cap.id} needs a warning", cap.warning.isNotBlank())
        for (cap in Capability.entries.filter { it.risk != Risk.SPECIAL }) {
            assertFalse("${cap.id} is not special", cap.persistentWhileActive)
        }
    }

    @Test
    fun firstSpecialCapabilitiesMatchThePlan() {
        val expected = mapOf(
            "apps.list" to null,
            "usage.read" to "usage",
            "notifications.listen" to "notif-listener",
            "accessibility" to "a11y",
            "clipboard.watch" to null,
            "accounts.read" to null,
            "location.background" to null,
            "nfc.hce.payment" to "hce-payment",
        )
        // PARITY G7: apps.list and usage.read have modules now; PARITY G12: so does clipboard.watch.
        // PARITY G5: location.background too; PARITY G10: accounts.read; PARITY G8: notifications.listen;
        // PARITY G9: accessibility; PARITY B4: nfc.hce.payment. All of them have modules now.
        val live = setOf(
            "apps.list", "usage.read", "clipboard.watch", "location.background", "accounts.read", "notifications.listen",
            "accessibility", "nfc.hce.payment",
        )
        for ((id, group) in expected) {
            val cap = Capability.fromId(id)!!
            assertEquals(Risk.SPECIAL, cap.risk)
            assertEquals(id, group, cap.exclusiveGroup)
            assertEquals("$id module state", id in live, cap.available)
        }
        val persistent = Capability.entries.filter { it.persistentWhileActive }.map { it.id }.toSet()
        assertEquals(setOf("notifications.listen", "accessibility", "location.background"), persistent)
    }

    @Test
    fun imeWarningSaysTheAppSeesWhatYouType() {
        // PARITY G11: plain words: everything typed, the password exception, and the app being typed in.
        val warning = Capability.IME.warning
        assertTrue("sees everything you type" in warning)
        assertTrue("Password fields are the exception" in warning)
        assertTrue("which app you are typing in" in warning)
    }

    @Test
    fun paymentWarningSaysTappingMayPay() {
        // PARITY B4: plain words: a card at payment terminals, tapping may pay, one app, how to undo it.
        val cap = Capability.NFC_HCE_PAYMENT
        assertTrue("act as a contactless card at payment terminals" in cap.warning)
        assertTrue("tapping your phone may pay with it" in cap.warning)
        assertTrue("Only one app at a time" in cap.warning)
        assertTrue("Contactless payments" in cap.warning)
        assertEquals(SystemAccess.PAYMENT_DEFAULT, SystemAccess.forCapability(cap))
        assertTrue(SystemAccess.PAYMENT_DEFAULT.asksInPlace)
    }

    @Test
    fun rolesAreExclusiveSpecialCapabilities() {
        // PARITY G11: one holder per role, refused (not replaced) while another app has it.
        val expected = mapOf(
            "role.browser" to "role-browser",
            "role.assistant" to "role-assistant",
            "device.admin" to "device-admin",
            "wallpaper" to "role-wallpaper",
            "ime" to "role-ime",
            // PARITY F7b
            "calls.screen" to "role-call-screening",
        )
        for ((id, group) in expected) {
            val cap = Capability.fromId(id)!!
            assertEquals(id, Risk.SPECIAL, cap.risk)
            assertEquals(id, group, cap.exclusiveGroup)
            assertTrue(id, cap.available)
            assertFalse(id, cap.persistentWhileActive)
            assertTrue(
                "$id says how to undo it in Android",
                "Default apps" in cap.warning || "Device admin apps" in cap.warning || "under Wallpaper" in cap.warning ||
                    "under Keyboard" in cap.warning,
            )
        }
    }

    @Test
    fun callScreeningWarningSaysWhatTheAppCanDo() {
        // PARITY F7b: plain words: who calls, silent rejection, the number rule, the 4 seconds, emergency calls.
        val warning = Capability.CALLS_SCREEN.warning
        assertTrue("sees who calls you" in warning)
        assertTrue("silently reject calls" in warning)
        assertTrue("contacts or call log" in warning)
        assertTrue("4 seconds" in warning)
        assertTrue("Emergency calls are never sent to it" in warning)
    }

    @Test
    fun dashboardRemoveOfSpecialAccessIsAudited() {
        // Review: "Remove" on the dashboard leaves a `special` / `revoked` row; other changes add none here.
        assertEquals("revoked", SpecialReview.dashboardAudit(Capability.CLIPBOARD_WATCH, GrantLevel.DENIED))
        assertNull(SpecialReview.dashboardAudit(Capability.CLIPBOARD_WATCH, GrantLevel.WHILE_USING))
        assertNull(SpecialReview.dashboardAudit(Capability.TORCH, GrantLevel.DENIED))
    }
}
