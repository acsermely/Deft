package app.deft.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerQueueTest {
    private fun t(name: String, at: Long = 0L) = BackgroundTrigger(name, "{}", at)

    @Test
    fun oneRunPerAppAtATime() {
        val q = RunnerQueue()
        assertEquals(RunnerQueue.Offer.ACCEPTED, q.offer("a", t("sms")))
        assertEquals(RunnerQueue.Offer.ACCEPTED, q.offer("a", t("alarms")))
        assertEquals("sms", q.poll("a", 1_000).start?.name)
        assertTrue(q.isRunning("a"))
        assertNull(q.poll("a", 1_001).start)
        q.finished("a")
        assertEquals("alarms", q.poll("a", 1_002).start?.name)
    }

    @Test
    fun appsDoNotBlockEachOther() {
        val q = RunnerQueue()
        q.offer("a", t("sms"))
        q.offer("b", t("sms"))
        assertEquals("sms", q.poll("a", 0).start?.name)
        assertEquals("sms", q.poll("b", 0).start?.name)
    }

    @Test
    fun queueHoldsTenAndRefusesMore() {
        val q = RunnerQueue()
        q.offer("a", t("boot"))
        q.poll("a", 0) // running, not queued
        repeat(RunnerQueue.MAX_QUEUED) { assertEquals(RunnerQueue.Offer.ACCEPTED, q.offer("a", t("sms$it"))) }
        assertEquals(RunnerQueue.Offer.QUEUE_FULL, q.offer("a", t("late")))
        assertEquals(10, q.queued("a"))
        q.finished("a")
        assertEquals("sms0", q.poll("a", 1).start?.name)
    }

    @Test
    fun periodicIsCoalescedWhileWaiting() {
        val q = RunnerQueue()
        q.offer("a", t("boot"))
        q.poll("a", 0)
        assertEquals(RunnerQueue.Offer.ACCEPTED, q.offer("a", t(RunnerQueue.PERIODIC)))
        assertEquals(RunnerQueue.Offer.COALESCED, q.offer("a", t(RunnerQueue.PERIODIC)))
        assertEquals(RunnerQueue.Offer.ACCEPTED, q.offer("a", t("sms")))
        assertEquals(2, q.queued("a"))
    }

    @Test
    fun periodicIsNotCoalescedWithTheRunningOne() {
        val q = RunnerQueue()
        q.offer("a", t(RunnerQueue.PERIODIC))
        q.poll("a", 0)
        assertEquals(RunnerQueue.Offer.ACCEPTED, q.offer("a", t(RunnerQueue.PERIODIC)))
    }

    @Test
    fun sixtyRunsPerHourThenDropped() {
        val q = RunnerQueue()
        var now = 0L
        repeat(RunnerQueue.MAX_PER_HOUR) {
            q.offer("a", t("sms"))
            assertEquals("sms", q.poll("a", now).start?.name)
            q.finished("a")
            now += 1_000
        }
        q.offer("a", t("sms"))
        q.offer("a", t("alarms"))
        val poll = q.poll("a", now)
        assertNull(poll.start)
        assertEquals(listOf("sms", "alarms"), poll.dropped.map { it.name })
        assertEquals(0, q.queued("a"))
        assertFalse(q.isRunning("a"))
        // The first run leaves the 60-minute window.
        q.offer("a", t("sms"))
        assertEquals("sms", q.poll("a", RunnerQueue.HOUR_MS + 1).start?.name)
    }

    @Test
    fun seededRunsCountTowardTheLimit() {
        val q = RunnerQueue(maxPerHour = 2)
        val now = 10 * RunnerQueue.HOUR_MS
        q.seed("a", listOf(now - 10, now - 20, now - 2 * RunnerQueue.HOUR_MS), now)
        assertEquals(2, q.runsInLastHour("a", now))
        q.offer("a", t("sms"))
        assertEquals(1, q.poll("a", now).dropped.size)
    }

    @Test
    fun clearDropsWaitingTriggers() {
        val q = RunnerQueue()
        q.offer("a", t("boot"))
        q.poll("a", 0)
        q.offer("a", t("sms"))
        q.offer("a", t("calls"))
        assertEquals(listOf("sms", "calls"), q.clear("a").map { it.name })
        q.finished("a")
        assertNull(q.poll("a", 1).start)
    }

    @Test
    fun budgetIsThirtySecondsWallClock() {
        val q = RunnerQueue()
        assertEquals(30_000L, q.budgetMs)
        assertFalse(q.expired(startedAt = 1_000, now = 30_999))
        assertTrue(q.expired(startedAt = 1_000, now = 31_000))
        assertEquals(30_000L, q.remainingMs(1_000, 1_000))
        assertEquals(1L, q.remainingMs(1_000, 30_999))
        assertEquals(0L, q.remainingMs(1_000, 99_000))
    }

    // Urgent triggers (call screening) go first and are never started late.

    @Test
    fun urgentTriggerGoesAheadOfOrdinaryOnes() {
        val q = RunnerQueue()
        q.offer("a", t("boot"))
        q.poll("a", 0) // running
        q.offer("a", t("sms"))
        q.offer("a", BackgroundTrigger("screening", "{}", 10, expiresAt = 4_010))
        q.offer("a", BackgroundTrigger("screening2", "{}", 11, expiresAt = 4_011))
        q.finished("a")
        assertEquals("screening", q.poll("a", 100).start?.name)
        q.finished("a")
        assertEquals("screening2", q.poll("a", 200).start?.name)
        q.finished("a")
        assertEquals("sms", q.poll("a", 300).start?.name)
    }

    @Test
    fun expiredUrgentTriggerIsDroppedNotRun() {
        val q = RunnerQueue()
        q.offer("a", t("boot"))
        q.poll("a", 0)
        q.offer("a", BackgroundTrigger("screening", "{}", 0, expiresAt = 4_000))
        q.offer("a", t("sms"))
        q.finished("a")
        val poll = q.poll("a", 4_000)
        assertEquals(listOf("screening"), poll.expired.map { it.name })
        assertEquals("sms", poll.start?.name)
        assertTrue(poll.dropped.isEmpty())
    }

    @Test
    fun ordinaryTriggersNeverExpire() {
        val q = RunnerQueue()
        q.offer("a", t("sms"))
        val poll = q.poll("a", 10_000_000)
        assertEquals("sms", poll.start?.name)
        assertTrue(poll.expired.isEmpty())
        assertFalse(t("sms").urgent)
    }

    @Test
    fun urgentTriggerJoinsOnlyARunWithEnoughTimeLeft() {
        val q = RunnerQueue(budgetMs = 30_000)
        val urgent = BackgroundTrigger("screening", "{}", 100_000, expiresAt = 105_000)
        // Run started at elapsed 1_000: 25 s left at 6_000, 4 s left at 27_000.
        assertTrue(q.joins(1_000, 6_000, urgent, 100_000))
        assertFalse(q.joins(1_000, 27_000, urgent, 100_000))
        // Less time left for the trigger too: 3 s to wait, 4 s of run left.
        assertTrue(q.joins(1_000, 27_000, urgent, 102_000))
        assertFalse(q.joins(0, 6_000, urgent, 100_000))
        assertFalse(q.joins(1_000, 6_000, t("sms"), 100_000))
    }

    @Test
    fun atMostThreeRunsAtOnceAcrossApps() {
        val q = RunnerQueue()
        for (id in listOf("a", "b", "c", "d")) q.offer(id, t("sms"))
        assertEquals("sms", q.poll("a", 0).start?.name)
        assertEquals("sms", q.poll("b", 0).start?.name)
        assertEquals("sms", q.poll("c", 0).start?.name)
        val full = q.poll("d", 0)
        assertNull(full.start)
        assertTrue(full.dropped.isEmpty())
        assertEquals(1, q.queued("d"))
        assertEquals(listOf("d"), q.waitingApps())
        q.finished("b")
        assertEquals("sms", q.poll("d", 1).start?.name)
        assertTrue(q.waitingApps().isEmpty())
    }
}
