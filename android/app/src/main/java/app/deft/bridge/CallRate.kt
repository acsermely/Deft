package app.deft.bridge

import java.util.concurrent.ConcurrentHashMap

/**
 * Sliding-window call limit per key (an app id): at most [limit] calls in any [windowMs]. Pure
 * apart from the clock, which the caller passes in, so it can be unit tested.
 */
class CallRate(private val limit: Int, private val windowMs: Long) {
    private val calls = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** True and counted when [key] may call at [now]; false when it is over the limit. */
    fun tryAcquire(key: String, now: Long): Boolean {
        val q = calls.getOrPut(key) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() >= windowMs) q.removeFirst()
            if (q.size >= limit) return false
            q.addLast(now)
            return true
        }
    }
}
