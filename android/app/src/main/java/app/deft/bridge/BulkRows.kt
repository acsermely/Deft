package app.deft.bridge

import android.content.Context
import app.deft.DeftApp

/**
 * Row limits for bulk reads of SMS and the call log (P16). Pure logic, unit tested.
 * A read above [CONFIRM_ABOVE] rows is confirmed by the user first; the audit log keeps the row count.
 */
object BulkRows {
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 50_000
    const val MAX_OFFSET = 50_000
    const val CONFIRM_ABOVE = 500

    /** Review: rows read in this long a stretch count together toward [CONFIRM_ABOVE]. */
    const val WINDOW_MS = 10 * 60_000L

    /** Review: the merged SMS + MMS list holds offset + limit rows in memory, so that sum is capped. */
    const val MAX_MERGED_WINDOW = 10_000

    fun limit(raw: Int?, default: Int = DEFAULT_LIMIT): Int = (raw ?: default).coerceIn(1, MAX_LIMIT)

    fun offset(raw: Int?): Int = (raw ?: 0).coerceIn(0, MAX_OFFSET)

    fun needsConfirm(limit: Int): Boolean = limit > CONFIRM_ABOVE

    /** Audit metadata for a list call: the number of rows returned. */
    fun metadata(rows: Int): String = "{\"rows\":$rows}"

    /**
     * Asks the user before a bulk read of more than [CONFIRM_ABOVE] rows. The dialog names the app.
     * Returns false when the user says no, or when the app is unknown.
     */
    suspend fun confirm(context: Context, webAppId: String, title: String, limit: Int, noun: String, paged: Boolean = false): Boolean {
        val app = DeftApp.instance.repository.getApp(webAppId) ?: return false
        val choice = GrantPrompt.ask(
            context,
            app,
            title = title,
            message = if (paged) "Read more than $CONFIRM_ABOVE $noun within a few minutes" else "Read up to $limit $noun at once",
            biometric = false,
        )
        return choice == ConfirmChoice.ALLOW
    }

    /** Review: every app's recent bulk reads, keyed by app and kind (the confirm title). */
    val recent = ReadWindow()

    /**
     * Review: null when a list call of [limit] rows may go ahead, else the error to return. One
     * read above [CONFIRM_ABOVE] asks every time; paging asks once a window when the rows read in
     * it would pass the limit. A background run cannot ask and gets `needs_foreground`.
     */
    suspend fun gate(context: Context, request: BridgeRequest, title: String, limit: Int, noun: String): BridgeOutcome.Err? {
        val key = "${request.webAppId}|$title"
        val now = System.currentTimeMillis()
        if (!recent.needsConfirm(key, limit, now)) return null
        if (request.background) {
            return BridgeOutcome.Err(BridgeErrors.NEEDS_FOREGROUND, "Open the app to read more than $CONFIRM_ABOVE $noun")
        }
        if (!confirm(context, request.webAppId, title, limit, noun, paged = !needsConfirm(limit))) {
            return BridgeOutcome.Err(BridgeErrors.CANCELLED, "User denied this action")
        }
        recent.confirmed(key, now)
        return null
    }

    /** Review: counts [rows] returned by a list call toward the app's window. */
    fun record(webAppId: String, title: String, rows: Int) {
        recent.record("$webAppId|$title", rows, System.currentTimeMillis())
    }

    /**
     * Review: rows read per key in a rolling [windowMs], so paging through a large store in
     * small pages still meets the confirm. The clock is passed in so it can be unit tested.
     */
    class ReadWindow(private val windowMs: Long = WINDOW_MS) {
        private val reads = HashMap<String, ArrayDeque<Pair<Long, Int>>>()
        private val confirmedAt = HashMap<String, Long>()

        @Synchronized
        fun needsConfirm(key: String, limit: Int, now: Long): Boolean {
            if (needsConfirm(limit)) return true
            val since = now - windowMs
            if ((confirmedAt[key] ?: Long.MIN_VALUE) > since) return false
            return rowsSince(key, since) + limit > CONFIRM_ABOVE
        }

        @Synchronized
        fun confirmed(key: String, now: Long) {
            confirmedAt[key] = now
        }

        @Synchronized
        fun record(key: String, rows: Int, now: Long) {
            if (rows <= 0) return
            reads.getOrPut(key) { ArrayDeque() }.addLast(now to rows)
        }

        private fun rowsSince(key: String, since: Long): Int {
            val list = reads[key] ?: return 0
            while (list.isNotEmpty() && list.first().first <= since) list.removeFirst()
            if (list.isEmpty()) reads.remove(key)
            return list.sumOf { it.second }
        }
    }
}
