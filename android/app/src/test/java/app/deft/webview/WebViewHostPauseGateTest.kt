package app.deft.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewHostPauseGateTest {
    @Test
    fun secondHostResumeDoesNotPauseWhenFirstPauses() {
        val gate = WebViewHostPauseGate()
        assertEquals(WebViewHostPauseGate.Action.RESUME_ALL, gate.resume())
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.resume())
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.pause())
        assertTrue(gate.hasResumedHosts)
        assertEquals(1, gate.resumedHosts)
    }

    @Test
    fun invertedOrderKeepsTimersRunning() {
        val gate = WebViewHostPauseGate()
        gate.resume()
        gate.resume()
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.pause())
        assertTrue(gate.hasResumedHosts)
    }

    @Test
    fun lastPauseStopsAll() {
        val gate = WebViewHostPauseGate()
        gate.resume()
        assertEquals(WebViewHostPauseGate.Action.PAUSE_ALL, gate.pause())
        assertFalse(gate.hasResumedHosts)
        assertEquals(WebViewHostPauseGate.Action.RESUME_ALL, gate.resume())
    }

    @Test
    fun extraPauseIsIdempotent() {
        val gate = WebViewHostPauseGate()
        assertEquals(WebViewHostPauseGate.Action.PAUSE_ALL, gate.pause())
        assertEquals(WebViewHostPauseGate.Action.PAUSE_ALL, gate.pause())
        assertEquals(0, gate.resumedHosts)
    }

    // PARITY D1: background runs need the process-wide timers while no host is in front.
    @Test
    fun backgroundRunResumesTimersOnlyWhenNoHost() {
        val gate = WebViewHostPauseGate()
        assertEquals(WebViewHostPauseGate.Action.RESUME_TIMERS, gate.backgroundStarted())
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.backgroundStarted())
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.backgroundEnded())
        assertEquals(WebViewHostPauseGate.Action.PAUSE_TIMERS, gate.backgroundEnded())
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.backgroundEnded())
        assertEquals(0, gate.backgroundRuns)
    }

    @Test
    fun hostLeavingDuringARunKeepsTimers() {
        val gate = WebViewHostPauseGate()
        gate.resume()
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.backgroundStarted())
        assertEquals(WebViewHostPauseGate.Action.PAUSE_VIEWS, gate.pause())
        assertEquals(WebViewHostPauseGate.Action.PAUSE_TIMERS, gate.backgroundEnded())
    }

    @Test
    fun runEndingWhileHostInFrontLeavesTimers() {
        val gate = WebViewHostPauseGate()
        gate.backgroundStarted()
        assertEquals(WebViewHostPauseGate.Action.RESUME_ALL, gate.resume())
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.backgroundEnded())
        assertEquals(WebViewHostPauseGate.Action.PAUSE_ALL, gate.pause())
    }

    // PARITY D3: an app running with a foreground service keeps every host view and the timers.
    @Test
    fun foregroundServiceKeepsViewsWhenTheHostLeaves() {
        val gate = WebViewHostPauseGate()
        gate.resume()
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.fgsStarted())
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.pause())
        assertTrue(gate.keepsViewsRunning)
        assertFalse(gate.hasResumedHosts)
        assertEquals(WebViewHostPauseGate.Action.PAUSE_ALL, gate.fgsEnded())
        assertFalse(gate.keepsViewsRunning)
    }

    @Test
    fun foregroundServiceEndingWhileHostInFrontPausesNothing() {
        val gate = WebViewHostPauseGate()
        gate.resume()
        gate.fgsStarted()
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.fgsEnded())
        assertEquals(WebViewHostPauseGate.Action.PAUSE_ALL, gate.pause())
    }

    @Test
    fun returningToAnAppThatKeptRunningResumesNothing() {
        val gate = WebViewHostPauseGate()
        gate.resume()
        gate.fgsStarted()
        gate.pause()
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.resume())
    }

    @Test
    fun twoServicesPauseOnlyAfterTheLast() {
        val gate = WebViewHostPauseGate()
        gate.resume()
        gate.fgsStarted()
        gate.fgsStarted()
        gate.pause()
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.fgsEnded())
        assertEquals(WebViewHostPauseGate.Action.PAUSE_ALL, gate.fgsEnded())
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.fgsEnded())
        assertEquals(0, gate.foregroundServices)
    }

    @Test
    fun foregroundServiceStartedWithNoHostResumesAll() {
        val gate = WebViewHostPauseGate()
        assertEquals(WebViewHostPauseGate.Action.RESUME_ALL, gate.fgsStarted())
        assertEquals(WebViewHostPauseGate.Action.PAUSE_ALL, gate.fgsEnded())
    }

    @Test
    fun backgroundRunDuringAForegroundServiceLeavesTimers() {
        val gate = WebViewHostPauseGate()
        gate.resume()
        gate.fgsStarted()
        gate.pause()
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.backgroundStarted())
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.backgroundEnded())
        assertEquals(WebViewHostPauseGate.Action.PAUSE_ALL, gate.fgsEnded())
    }

    @Test
    fun foregroundServiceEndingDuringABackgroundRunKeepsTimers() {
        val gate = WebViewHostPauseGate()
        gate.resume()
        gate.fgsStarted()
        gate.pause()
        gate.backgroundStarted()
        assertEquals(WebViewHostPauseGate.Action.PAUSE_VIEWS, gate.fgsEnded())
        assertEquals(WebViewHostPauseGate.Action.PAUSE_TIMERS, gate.backgroundEnded())
    }

    @Test
    fun lastHostLeavingWhileAnOwnerIsResumedPausesViewsOnly() {
        val gate = WebViewHostPauseGate()
        val home = Any()
        gate.resume()
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.ownerResumed(home))
        assertEquals(WebViewHostPauseGate.Action.PAUSE_VIEWS, gate.pause())
        assertEquals(WebViewHostPauseGate.Action.PAUSE_TIMERS, gate.ownerPaused(home))
    }

    @Test
    fun ownerAloneResumesAndPausesTimers() {
        val gate = WebViewHostPauseGate()
        val home = Any()
        assertEquals(WebViewHostPauseGate.Action.RESUME_TIMERS, gate.ownerResumed(home))
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.ownerResumed(home))
        assertEquals(1, gate.resumedOwners)
        assertEquals(WebViewHostPauseGate.Action.PAUSE_TIMERS, gate.ownerPaused(home))
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.ownerPaused(home))
    }

    @Test
    fun backgroundRunEndingWhileAnOwnerIsResumedKeepsTimers() {
        val gate = WebViewHostPauseGate()
        val home = Any()
        val lint = Any()
        gate.ownerResumed(home)
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.backgroundStarted())
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.backgroundEnded())
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.ownerResumed(lint))
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.ownerPaused(home))
        assertEquals(WebViewHostPauseGate.Action.PAUSE_TIMERS, gate.ownerPaused(lint))
    }

    @Test
    fun ownerPausingWhileAHostIsResumedLeavesTimers() {
        val gate = WebViewHostPauseGate()
        val preview = Any()
        gate.ownerResumed(preview)
        assertEquals(WebViewHostPauseGate.Action.RESUME_ALL, gate.resume())
        assertEquals(WebViewHostPauseGate.Action.NONE, gate.ownerPaused(preview))
        assertEquals(WebViewHostPauseGate.Action.PAUSE_ALL, gate.pause())
    }
}
