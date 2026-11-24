package app.deft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepLedgerTest {
    private fun fold(vararg readings: Pair<Long, String>): StepLedger.State =
        readings.fold(StepLedger.State()) { s, (c, d) -> StepLedger.onReading(s, c, d) }

    @Test
    fun firstReadingIsTheBaseline() {
        val s = fold(5000L to "2026-11-20")
        assertEquals(0, StepLedger.todaySteps(s))
        assertEquals(0, StepLedger.snapshot(s, "2026-11-20").today)
    }

    @Test
    fun stepsAccumulateWithinADay() {
        val s = fold(5000L to "2026-11-20", 5400L to "2026-11-20", 6100L to "2026-11-20")
        assertEquals(1100, StepLedger.snapshot(s, "2026-11-20").today)
    }

    @Test
    fun rebootKeepsStepsSoFarAndAddsTheNewCount() {
        val s = fold(5000L to "2026-11-20", 6000L to "2026-11-20", 40L to "2026-11-20", 340L to "2026-11-20")
        // 1000 before the reboot; the counter restarted at 0, so 340 more.
        assertEquals(1340, StepLedger.snapshot(s, "2026-11-20").today)
    }

    @Test
    fun rebootThenNextDayStartsAtTheNewBaseline() {
        val s = fold(5000L to "2026-11-20", 6000L to "2026-11-20", 40L to "2026-11-20", 100L to "2026-11-21", 160L to "2026-11-21")
        val snap = StepLedger.snapshot(s, "2026-11-21")
        assertEquals(60, snap.today)
        assertEquals(1040, snap.days.first { it.date == "2026-11-20" }.steps)
    }

    @Test
    fun dayRolloverClosesTheOldDayAndRebases() {
        val s = fold(1000L to "2026-11-20", 3000L to "2026-11-20", 3100L to "2026-11-21", 3600L to "2026-11-21")
        val snap = StepLedger.snapshot(s, "2026-11-21")
        assertEquals(500, snap.today)
        assertEquals(2000, snap.days.first { it.date == "2026-11-20" }.steps)
    }

    @Test
    fun snapshotIsThirtyDaysOldestFirstWithZeroGaps() {
        val s = fold(1000L to "2026-11-10", 2000L to "2026-11-10", 2100L to "2026-11-21")
        val snap = StepLedger.snapshot(s, "2026-11-21")
        assertEquals(30, snap.days.size)
        assertEquals("2026-11-21", snap.days.last().date)
        assertEquals("2026-10-23", snap.days.first().date)
        assertEquals(1000, snap.days.first { it.date == "2026-11-10" }.steps)
        assertEquals(0, snap.days.first { it.date == "2026-11-15" }.steps)
    }

    @Test
    fun overdueDayShowsInSnapshotBeforeAnyNewReading() {
        val s = fold(1000L to "2026-11-20", 1800L to "2026-11-20")
        val snap = StepLedger.snapshot(s, "2026-11-21")
        assertEquals(0, snap.today)
        assertEquals(800, snap.days.first { it.date == "2026-11-20" }.steps)
    }

    @Test
    fun oldDaysArePrunedAndClockGoingBackIsIgnored() {
        var s = fold(0L to "2026-09-01", 100L to "2026-09-01")
        s = StepLedger.onReading(s, 150L, "2026-11-21")
        assertTrue(s.days.keys.none { it < "2026-10-23" })
        val back = StepLedger.onReading(s, 200L, "2026-11-01")
        assertEquals(s, back)
    }

    @Test
    fun stateSurvivesEncodeDecode() {
        val s = fold(1000L to "2026-11-20", 3000L to "2026-11-20", 3100L to "2026-11-21", -5L to "2026-11-21")
        assertEquals(s, StepStore.decode(StepStore.encode(s)))
    }
}
