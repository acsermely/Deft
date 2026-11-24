package app.deft.background

/**
 * PARITY D1: one trigger waiting for, or given to, a background run. [at] is when it fired.
 * PARITY F7b: [expiresAt] marks an urgent trigger (see [BackgroundTriggers.urgentMs]): it waits
 * ahead of the ordinary ones and is dropped, not run, from [expiresAt] on.
 */
data class BackgroundTrigger(val name: String, val payloadJson: String, val at: Long, val expiresAt: Long? = null) {
    val urgent: Boolean get() = expiresAt != null
}

/**
 * PARITY D1: the scheduling rules of background runs, without Android, so they can be unit
 * tested. Not thread-safe; [BackgroundRunner] holds it under a lock.
 *
 * - One run per app at a time, and at most [maxRunning] runs at once across apps (the others
 *   wait in their apps' queues); up to [maxQueued] triggers wait behind it. A new trigger is
 *   refused when the queue is full (the waiting ones keep their place).
 * - A `periodic` trigger is merged into one already waiting.
 * - At most [maxPerHour] runs start per app in any 60 minutes; triggers over the limit are
 *   dropped when their turn comes, never delayed.
 * - Each run gets [budgetMs] of wall-clock time from the moment its page starts loading.
 * - PARITY F7b: an urgent trigger waits behind other urgent ones but ahead of ordinary ones, and
 *   is dropped (reported as expired) instead of started once its time is up.
 */
class RunnerQueue(
    val maxQueued: Int = MAX_QUEUED,
    val maxPerHour: Int = MAX_PER_HOUR,
    val budgetMs: Long = BUDGET_MS,
    val maxRunning: Int = MAX_RUNNING,
) {
    enum class Offer { ACCEPTED, COALESCED, QUEUE_FULL }

    /**
     * [start] is the trigger to run now, if any; [dropped] were over the hourly limit; [expired]
     * were urgent triggers whose time was up (PARITY F7b).
     */
    data class Poll(
        val start: BackgroundTrigger?,
        val dropped: List<BackgroundTrigger>,
        val expired: List<BackgroundTrigger> = emptyList(),
    )

    private val queues = HashMap<String, ArrayDeque<BackgroundTrigger>>()
    private val running = HashMap<String, BackgroundTrigger>()
    private val starts = HashMap<String, ArrayDeque<Long>>()

    fun offer(appId: String, trigger: BackgroundTrigger): Offer {
        val queue = queues.getOrPut(appId) { ArrayDeque() }
        if (trigger.name == PERIODIC && queue.any { it.name == PERIODIC }) return Offer.COALESCED
        if (queue.size >= maxQueued) return Offer.QUEUE_FULL
        if (trigger.urgent) {
            val at = queue.indexOfFirst { !it.urgent }
            if (at < 0) queue.addLast(trigger) else queue.add(at, trigger)
        } else {
            queue.addLast(trigger)
        }
        return Offer.ACCEPTED
    }

    /** The next trigger to start for [appId], or none while its run (or [maxRunning] runs) go on. */
    fun poll(appId: String, now: Long): Poll {
        if (appId in running) return Poll(null, emptyList())
        if (running.size >= maxRunning) return Poll(null, emptyList())
        val queue = queues[appId] ?: return Poll(null, emptyList())
        val window = starts.getOrPut(appId) { ArrayDeque() }
        prune(window, now)
        val dropped = mutableListOf<BackgroundTrigger>()
        val expired = mutableListOf<BackgroundTrigger>()
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            val until = next.expiresAt
            if (until != null && now >= until) {
                expired += next
                continue
            }
            if (window.size >= maxPerHour) {
                dropped += next
                continue
            }
            window.addLast(now)
            running[appId] = next
            if (queue.isEmpty()) queues.remove(appId)
            return Poll(next, dropped, expired)
        }
        queues.remove(appId)
        return Poll(null, dropped, expired)
    }

    fun finished(appId: String) {
        running.remove(appId)
    }

    fun isRunning(appId: String): Boolean = appId in running

    fun queued(appId: String): Int = queues[appId]?.size ?: 0

    /** Apps with triggers waiting and no run going on (for example for a free slot). */
    fun waitingApps(): List<String> = queues.keys.filter { it !in running }

    /** Drops everything waiting for [appId] (kill switch, uninstall) and returns it. */
    fun clear(appId: String): List<BackgroundTrigger> = queues.remove(appId)?.toList() ?: emptyList()

    /** Earlier run times (for example from the audit log after a restart) count toward the limit. */
    fun seed(appId: String, times: List<Long>, now: Long) {
        val window = starts.getOrPut(appId) { ArrayDeque() }
        val merged = (window + times.filter { it > now - HOUR_MS && it <= now }).sorted()
        window.clear()
        window.addAll(merged)
    }

    fun runsInLastHour(appId: String, now: Long): Int {
        val window = starts[appId] ?: return 0
        prune(window, now)
        return window.size
    }

    fun expired(startedAt: Long, now: Long): Boolean = now - startedAt >= budgetMs

    fun remainingMs(startedAt: Long, now: Long): Long = (budgetMs - (now - startedAt)).coerceIn(0, budgetMs)

    /**
     * PARITY F7b: whether urgent [trigger] (fired-at clock [now]) may join a run that started at
     * [startedAt] (elapsed clock [elapsedNow]): only when the run has at least as long left as the
     * trigger may still wait. Otherwise the run ends first and the trigger is queued for the next.
     */
    fun joins(startedAt: Long, elapsedNow: Long, trigger: BackgroundTrigger, now: Long): Boolean {
        val until = trigger.expiresAt ?: return false
        if (startedAt <= 0) return false
        return remainingMs(startedAt, elapsedNow) >= until - now
    }

    private fun prune(window: ArrayDeque<Long>, now: Long) {
        while (window.isNotEmpty() && window.first() <= now - HOUR_MS) window.removeFirst()
    }

    companion object {
        const val MAX_QUEUED = 10
        const val MAX_PER_HOUR = 60
        const val BUDGET_MS = 30_000L
        const val MAX_RUNNING = 3
        const val HOUR_MS = 3_600_000L

        /** Trigger name for every `periodic:N` declaration. */
        const val PERIODIC = "periodic"
    }
}
