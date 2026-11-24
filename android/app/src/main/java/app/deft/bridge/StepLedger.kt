package app.deft.bridge

import java.time.LocalDate

/**
 * Daily step totals from the cumulative `TYPE_STEP_COUNTER` (steps since boot), as pure functions.
 *
 * - **Baseline**: the first reading of a new local day. Today's steps are `counter - dayBase`.
 * - **Reboot**: the counter went backwards, so it restarted from zero. Steps counted so far today
 *   are kept and the counter value is added from here on (`dayBase` becomes `-stepsSoFar`).
 * - **Day rollover**: the previous day is closed with the last reading it saw; the new day starts
 *   at the new reading, so steps between the last reading and midnight are counted to the old day
 *   only when the midnight flush delivered them first.
 * - Only [KEEP_DAYS] days are kept. The first reading ever starts today at 0: steps from before
 *   the store began cannot be known.
 */
object StepLedger {
    const val KEEP_DAYS = 30

    /** [date] is ISO `yyyy-MM-dd`. [days] holds closed days, never [date] itself. */
    data class State(
        val date: String? = null,
        val dayBase: Long = 0,
        val lastCounter: Long = 0,
        val days: Map<String, Int> = emptyMap(),
    )

    data class Day(val date: String, val steps: Int)
    data class Snapshot(val today: Int, val days: List<Day>)

    fun todaySteps(state: State): Int = (state.lastCounter - state.dayBase).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Folds one counter reading taken on local date [date] into [state]. */
    fun onReading(state: State, counter: Long, date: String): State {
        val current = state.date ?: return State(date, counter, counter, state.days)
        if (date == current) {
            return if (counter < state.lastCounter) {
                State(current, -todaySteps(state).toLong(), counter, state.days)
            } else {
                state.copy(lastCounter = counter)
            }
        }
        if (date < current) return state // the clock went back; ignore until it catches up
        // A new day. Close the old one with what it saw; then start fresh at this reading.
        val closed = (state.days + (current to todaySteps(state))).prune(date)
        return State(date, counter, counter, closed)
    }

    /**
     * The 30 days ending [today], oldest first, days with no data as 0. A day that is over but
     * not yet closed (no reading since) shows its last known total, and today is 0.
     */
    fun snapshot(state: State, today: String): Snapshot {
        val known = HashMap(state.days)
        val current = state.date
        val todayCount = if (current == today) {
            todaySteps(state)
        } else {
            if (current != null && current < today) known[current] = todaySteps(state)
            0
        }
        val end = LocalDate.parse(today)
        val days = (KEEP_DAYS - 1 downTo 0).map { back ->
            val d = end.minusDays(back.toLong()).toString()
            Day(d, if (d == today) todayCount else known[d] ?: 0)
        }
        return Snapshot(todayCount, days)
    }

    private fun Map<String, Int>.prune(today: String): Map<String, Int> {
        val oldest = LocalDate.parse(today).minusDays((KEEP_DAYS - 1).toLong()).toString()
        return filterKeys { it >= oldest }
    }
}
