package app.deft.special

import app.deft.catalog.Capability
import app.deft.catalog.GrantLevel
import app.deft.catalog.Risk

/**
 * PARITY G0: what the daily check does with one granted special capability. Pure, so it can be
 * unit tested without Android.
 *
 * "Last activity" is the later of when the grant was made (or kept) and the last successful use.
 * After 30 idle days the user gets a "still want this?" reminder, again every 30 days while it
 * stays unused; after 90 idle days the grant is removed.
 */
object SpecialReview {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val REMIND_AFTER_MS = 30 * DAY_MS
    const val REVOKE_AFTER_MS = 90 * DAY_MS

    enum class Action { NONE, REMIND, REVOKE }

    fun decide(grantedAt: Long, lastUsedAt: Long?, remindedAt: Long?, now: Long): Action {
        val last = maxOf(grantedAt, lastUsedAt ?: 0L)
        val idle = now - last
        if (idle >= REVOKE_AFTER_MS) return Action.REVOKE
        if (idle < REMIND_AFTER_MS) return Action.NONE
        val remindedSinceLast = remindedAt != null && remindedAt >= last && remindedAt <= now
        if (remindedSinceLast && now - remindedAt!! < REMIND_AFTER_MS) return Action.NONE
        return Action.REMIND
    }

    /** Review: the audit outcome (source `special`) for a dashboard grant change, or null for none. */
    fun dashboardAudit(cap: Capability, level: GrantLevel): String? =
        if (cap.risk == Risk.SPECIAL && level == GrantLevel.DENIED) "revoked" else null
}
