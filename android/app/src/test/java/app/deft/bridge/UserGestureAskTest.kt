package app.deft.bridge

import app.deft.bridge.UserGesture.AskKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserGestureAskTest {
    @Test
    fun confirmSheetConsumeDoesNotSpendThePermissionAsk() {
        UserGesture.tapped("app-consume-first", 1_000L)
        // BridgeService spends the tap for a confirm-each action before the module runs.
        assertTrue(UserGesture.consume("app-consume-first", 1_100L))
        assertTrue(UserGesture.spendForAsk("app-consume-first", AskKind.SYSTEM, 1_200L))
        assertFalse(UserGesture.spendForAsk("app-consume-first", AskKind.SYSTEM, 1_300L))
    }

    @Test
    fun askExpiresWithTheTapWindow() {
        UserGesture.tapped("app-expire", 0L)
        assertFalse(UserGesture.spendForAsk("app-expire", AskKind.SYSTEM, UserGesture.WINDOW_MS + 1))
    }

    @Test
    fun aNewTapBuysAnotherAsk() {
        UserGesture.tapped("app-new-tap", 0L)
        assertTrue(UserGesture.spendForAsk("app-new-tap", AskKind.SYSTEM, 100L))
        UserGesture.tapped("app-new-tap", 5_000L)
        assertTrue(UserGesture.spendForAsk("app-new-tap", AskKind.SYSTEM, 5_100L))
    }

    @Test
    fun aSystemAskThenASpecialAskInOneTapAreBothAllowed() {
        UserGesture.tapped("app-chain", 0L)
        assertTrue(UserGesture.spendForAsk("app-chain", AskKind.SYSTEM, 100L))
        assertTrue(UserGesture.spendForAsk("app-chain", AskKind.SPECIAL, 200L))
        assertFalse(UserGesture.spendForAsk("app-chain", AskKind.SPECIAL, 300L))
        assertFalse(UserGesture.spendForAsk("app-chain", AskKind.SYSTEM, 300L))
    }

    @Test
    fun twoSystemAsksInOneTapSecondIsRefused() {
        UserGesture.tapped("app-two-system", 0L)
        assertTrue(UserGesture.spendForAsk("app-two-system", AskKind.SYSTEM, 100L))
        assertFalse(UserGesture.spendForAsk("app-two-system", AskKind.SYSTEM, 200L))
    }

    @Test
    fun refreshAfterAllowLetsAFollowUpAskPastTheWindow() {
        UserGesture.tapped("app-refresh", 0L)
        assertTrue(UserGesture.spendForAsk("app-refresh", AskKind.SYSTEM, 100L))
        // The system dialog took 20 s; the user allowed it, so the Allow tap is a new gesture.
        UserGesture.refreshAfterAllow("app-refresh", 20_000L)
        assertTrue(UserGesture.spendForAsk("app-refresh", AskKind.SPECIAL, 21_000L))
    }

    @Test
    fun anAllowDoesNotChangeRecentOrConsume() {
        UserGesture.tapped("app-recent", 0L)
        UserGesture.refreshAfterAllow("app-recent", 20_000L)
        // Paste, speech, auth and the confirm skip still read only real taps.
        assertFalse(UserGesture.recent("app-recent", 20_000L))
        assertFalse(UserGesture.consume("app-recent", 20_000L))
        assertTrue(UserGesture.spendForAsk("app-recent", AskKind.SPECIAL, 20_100L))
    }

    @Test
    fun withoutARefreshTheFollowUpAskIsPastTheWindow() {
        UserGesture.tapped("app-no-refresh", 0L)
        assertTrue(UserGesture.spendForAsk("app-no-refresh", AskKind.SYSTEM, 100L))
        assertFalse(UserGesture.spendForAsk("app-no-refresh", AskKind.SPECIAL, 20_000L))
    }

    @Test
    fun systemSpecialAndConsentAskInOneTapAreAllAllowed() {
        UserGesture.tapped("app-three-kinds", 0L)
        assertTrue(UserGesture.spendForAsk("app-three-kinds", AskKind.SYSTEM, 100L))
        assertTrue(UserGesture.spendForAsk("app-three-kinds", AskKind.SPECIAL, 200L))
        assertTrue(UserGesture.spendForAsk("app-three-kinds", AskKind.CONSENT, 300L))
        assertFalse(UserGesture.spendForAsk("app-three-kinds", AskKind.CONSENT, 400L))
    }

    @Test
    fun aRefreshDoesNotGiveAKindANewTurnInTheSameTap() {
        UserGesture.tapped("app-refresh-spent", 0L)
        assertTrue(UserGesture.spendForAsk("app-refresh-spent", AskKind.SYSTEM, 100L))
        UserGesture.refreshAfterAllow("app-refresh-spent", 4_000L)
        assertFalse(UserGesture.spendForAsk("app-refresh-spent", AskKind.SYSTEM, 4_100L))
    }
}
