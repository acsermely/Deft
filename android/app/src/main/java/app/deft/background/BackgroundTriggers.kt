package app.deft.background

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// DEVICE-UNCHECKED (background triggers): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: an SMS auto-reply sample works with the app closed; boot, periodic and charging triggers fire.
// Remove this note once checked.
/**
 * Which apps a system event wakes, and the payloads of the events that have no UI
 * counterpart. Pure, unit tested; the Android side is [BackgroundWake], [SystemTriggers] and
 * [PeriodicTriggers].
 */
object BackgroundTriggers {
    const val ALARMS = "alarms"
    const val SMS = "sms"
    const val CALLS = "calls"
    const val NOTIFY_ACTION = "notify.action"
    const val BOOT = "boot"
    const val NETWORK = "network"
    const val CHARGING = "charging"

    /** A UnifiedPush message for a closed app; payload `{base64}`. */
    const val PUSH = "push"

    /** One of the app's geofences was entered or left; payload `{id, entering}`. */
    const val GEOFENCE = "geofence"

    /** Android runs a sync for one of the app's accounts; payload `{account}`. */
    const val SYNC = "sync"

    /**
     * Another app posted a (not ongoing) notification while the `notifications.listen`
     * holder was closed; payload is the `notifications.posted` event.
     */
    const val NOTIFICATION = "notification"

    /**
     * A call to screen came in while the `calls.screen` holder was closed; payload is
     * the `calls.screening` event. Urgent ([urgentMs]): the answer is only useful within seconds.
     */
    const val SCREENING = "screening"

    /**
     * How long an urgent trigger may wait to start, or null for an ordinary one. An
     * urgent trigger goes ahead of the waiting ones, joins a run already going (see
     * `BackgroundRunner`) and is dropped, not run, once its time is up.
     */
    fun urgentMs(name: String): Long? =
        if (name == SCREENING) app.deft.telephony.ScreeningSpec.DEADLINE_MS else null

    /** Shortest `periodic:N` interval; WorkManager does not run periodic work more often. */
    const val MIN_PERIODIC_MINUTES = 15

    /** Quiet time before a connectivity change is reported. */
    const val NETWORK_QUIET_MS = 30_000L

    /**
     * What decides whether an app gets a trigger: it declares the `background` capability
     * ([declaresCapability]), holds the grant ([granted]) and lists the trigger ([triggers],
     * the manifest `background.triggers`; empty when there is no `background` block).
     */
    data class Candidate(
        val webAppId: String,
        val declaresCapability: Boolean,
        val granted: Boolean,
        val triggers: List<String>,
    )

    fun wants(candidate: Candidate, name: String): Boolean =
        candidate.declaresCapability && candidate.granted && BackgroundPolicy.declares(candidate.triggers, name)

    /** The apps among [candidates] that [name] wakes, in order. */
    fun targets(candidates: List<Candidate>, name: String): List<String> =
        candidates.filter { wants(it, name) }.map { it.webAppId }

    /** The interval of the app's periodic work, or null when it should have none. */
    fun periodicMinutes(candidate: Candidate): Int? {
        if (!candidate.declaresCapability || !candidate.granted) return null
        return BackgroundPolicy.periodicMinutes(candidate.triggers)?.takeIf { it >= MIN_PERIODIC_MINUTES }
    }

    /** `network` payload. */
    data class NetworkState(val connected: Boolean, val metered: Boolean) {
        fun payload(): String = buildJsonObject {
            put("connected", JsonPrimitive(connected))
            put("metered", JsonPrimitive(metered))
        }.toString()

        companion object {
            val OFFLINE = NetworkState(connected = false, metered = false)

            /** From the default network's capabilities; a network without internet counts as offline. */
            fun of(hasInternet: Boolean, notMetered: Boolean): NetworkState =
                if (!hasInternet) OFFLINE else NetworkState(connected = true, metered = !notMetered)
        }
    }

    fun chargingPayload(charging: Boolean): String =
        buildJsonObject { put("charging", JsonPrimitive(charging)) }.toString()
}

/**
 * Reports a state only after it has stayed the same for [quietMs], and only when it
 * differs from the last one reported (or the [baseline]). Not thread-safe; [SystemTriggers]
 * uses it on the main thread. Times are any monotonic clock in milliseconds.
 */
class TriggerDebounce<T : Any>(private val quietMs: Long) {
    private var last: T? = null
    private var pending: T? = null
    private var changedAt = 0L

    /** The state when watching starts; it is not reported. */
    fun baseline(state: T) {
        last = state
        pending = null
    }

    /**
     * Records [state] seen at [now]. Returns when [due] should be called, or null when nothing
     * waits (the state is back to the last reported one). A repeat of the waiting state does
     * not restart the quiet time; a different one does.
     */
    fun offer(state: T, now: Long): Long? {
        if (state == last) {
            pending = null
            return null
        }
        if (state != pending) {
            pending = state
            changedAt = now
        }
        return changedAt + quietMs
    }

    /** The state to report at [now], or null when nothing waits or the quiet time is not over. */
    fun due(now: Long): T? {
        val state = pending ?: return null
        if (now - changedAt < quietMs) return null
        pending = null
        last = state
        return state
    }
}
