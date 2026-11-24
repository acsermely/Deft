package app.deft.background

import app.deft.background.BackgroundTriggers.Candidate
import app.deft.background.BackgroundTriggers.NetworkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTriggersTest {
    private fun app(id: String, declares: Boolean = true, granted: Boolean = true, vararg triggers: String) =
        Candidate(id, declares, granted, triggers.toList())

    @Test
    fun onlyAppsThatDeclareAndHoldTheGrantAreWoken() {
        val apps = listOf(
            app("a", true, true, "network", "boot"),
            app("b", true, false, "network"),
            app("c", false, false, "network"),
            app("d", true, true, "charging"),
            app("e", true, true, "network"),
        )
        assertEquals(listOf("a", "e"), BackgroundTriggers.targets(apps, BackgroundTriggers.NETWORK))
        assertEquals(listOf("a"), BackgroundTriggers.targets(apps, BackgroundTriggers.BOOT))
        assertEquals(listOf("d"), BackgroundTriggers.targets(apps, BackgroundTriggers.CHARGING))
        assertEquals(emptyList<String>(), BackgroundTriggers.targets(apps, BackgroundTriggers.SMS))
    }

    @Test
    fun grantWithoutDeclaringTheCapabilityIsNotEnough() {
        assertFalse(BackgroundTriggers.wants(app("x", false, true, "sms"), BackgroundTriggers.SMS))
    }

    @Test
    fun triggerNamesMatchExactly() {
        val a = app("a", true, true, "notify.action", "periodic:30")
        assertTrue(BackgroundTriggers.wants(a, BackgroundTriggers.NOTIFY_ACTION))
        assertTrue(BackgroundTriggers.wants(a, RunnerQueue.PERIODIC))
        assertFalse(BackgroundTriggers.wants(a, "notify"))
        assertFalse(BackgroundTriggers.wants(a, "periodic:30"))
        assertFalse(BackgroundTriggers.wants(a, BackgroundTriggers.CALLS))
    }

    @Test
    fun periodicWorkOnlyWithDeclarationGrantAndValidInterval() {
        assertEquals(30, BackgroundTriggers.periodicMinutes(app("a", true, true, "boot", "periodic:30")))
        assertEquals(15, BackgroundTriggers.periodicMinutes(app("a", true, true, "periodic:15")))
        assertNull(BackgroundTriggers.periodicMinutes(app("a", true, false, "periodic:30")))
        assertNull(BackgroundTriggers.periodicMinutes(app("a", false, true, "periodic:30")))
        assertNull(BackgroundTriggers.periodicMinutes(app("a", true, true, "boot")))
        assertNull(BackgroundTriggers.periodicMinutes(app("a", true, true, "periodic:5")))
        assertNull(BackgroundTriggers.periodicMinutes(app("a", true, true, "periodic:x")))
    }

    @Test
    fun networkStateAndPayloads() {
        assertEquals(NetworkState(true, false), NetworkState.of(hasInternet = true, notMetered = true))
        assertEquals(NetworkState(true, true), NetworkState.of(hasInternet = true, notMetered = false))
        assertEquals(NetworkState.OFFLINE, NetworkState.of(hasInternet = false, notMetered = true))
        assertEquals("""{"connected":true,"metered":true}""", NetworkState(true, true).payload())
        assertEquals("""{"charging":false}""", BackgroundTriggers.chargingPayload(false))
    }
}

class TriggerDebounceTest {
    private val quiet = BackgroundTriggers.NETWORK_QUIET_MS

    @Test
    fun reportsAfterQuietTime() {
        val d = TriggerDebounce<String>(quiet)
        d.baseline("wifi")
        assertEquals(1_000 + quiet, d.offer("off", 1_000))
        assertNull(d.due(1_000 + quiet - 1))
        assertEquals("off", d.due(1_000 + quiet))
        assertNull(d.due(1_000 + quiet + 1))
    }

    @Test
    fun baselineIsNotReported() {
        val d = TriggerDebounce<String>(quiet)
        d.baseline("wifi")
        assertNull(d.offer("wifi", 0))
        assertNull(d.due(quiet * 2))
    }

    @Test
    fun aNewStateRestartsTheQuietTimeButARepeatDoesNot() {
        val d = TriggerDebounce<String>(quiet)
        d.baseline("wifi")
        assertEquals(quiet, d.offer("off", 0))
        assertEquals(quiet, d.offer("off", 10_000))
        assertEquals(20_000 + quiet, d.offer("cell", 20_000))
        assertNull(d.due(quiet))
        assertEquals("cell", d.due(20_000 + quiet))
    }

    @Test
    fun flappingBackToTheLastReportedStateReportsNothing() {
        val d = TriggerDebounce<String>(quiet)
        d.baseline("wifi")
        d.offer("off", 0)
        assertNull(d.offer("wifi", 5_000))
        assertNull(d.due(quiet * 3))
    }

    @Test
    fun theReportedStateBecomesTheNewReference() {
        val d = TriggerDebounce<String>(quiet)
        d.baseline("wifi")
        d.offer("off", 0)
        assertEquals("off", d.due(quiet))
        assertNull(d.offer("off", quiet + 1))
        assertEquals(quiet + 2 + quiet, d.offer("wifi", quiet + 2))
        assertEquals("wifi", d.due(quiet + 2 + quiet))
    }

    @Test
    fun withoutBaselineTheFirstStateIsReported() {
        val d = TriggerDebounce<String>(quiet)
        assertEquals(quiet, d.offer("wifi", 0))
        assertEquals("wifi", d.due(quiet))
    }
}
