package app.deft.telephony

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * PARITY F7b: the pure rules of call screening (`calls.screen`). No Android types; unit tested.
 * The Android side is [DeftCallScreeningService] and `CallsModule`.
 *
 * - Every incoming call goes to the holder as [EVENT]; its `calls.screenRespond` must arrive
 *   within [DEADLINE_MS], else the call is allowed. A call is never held longer.
 * - The number is in the event only when the holder may also read the call log or contacts
 *   ([showNumber]); otherwise it is null and the app only learns that a call came.
 * - Emergency numbers are never screened ([isEmergency]); Android already leaves them out.
 */
object ScreeningSpec {
    /** Event to the holder, live or as the payload of the background trigger `screening`. */
    const val EVENT = "calls.screening"

    /** How long the launcher waits for the holder; Android's own limit is 5 seconds. */
    const val DEADLINE_MS = 4_000L

    /** Longest `callId` accepted; the launcher's own ids are 32 hex characters. */
    const val CALL_ID_MAX = 64

    /** Longest number passed on; anything longer is not a phone number. */
    const val NUMBER_MAX = 32

    enum class Action(val id: String) {
        ALLOW("allow"),
        REJECT("reject"),
        SILENCE("silence"),
        ;

        companion object {
            fun fromId(id: String?): Action? = entries.firstOrNull { it.id == id }
        }
    }

    /** The holder's answer. [skipLog] and [skipNotification] only with [Action.REJECT]. */
    data class Decision(val action: Action, val skipLog: Boolean = false, val skipNotification: Boolean = false) {
        companion object {
            val ALLOW = Decision(Action.ALLOW)
        }
    }

    /** What `CallScreeningService.CallResponse.Builder` gets, one field per setter. */
    data class Response(
        val disallowCall: Boolean,
        val rejectCall: Boolean,
        val silenceCall: Boolean,
        val skipCallLog: Boolean,
        val skipNotification: Boolean,
    )

    /**
     * [decision] as Android's response. Reject disallows and rejects the call (the caller hears it
     * end); the log and notification flags only mean something then. Silence lets it through
     * without ringing. Allow changes nothing.
     */
    fun response(decision: Decision): Response = when (decision.action) {
        Action.ALLOW -> Response(false, false, false, false, false)
        Action.SILENCE -> Response(false, false, true, false, false)
        Action.REJECT -> Response(true, true, false, decision.skipLog, decision.skipNotification)
    }

    /** `calls.screenRespond` args, checked: either [callId] and [decision], or [error]. */
    data class Parsed(val callId: String?, val decision: Decision?, val error: String?)

    fun parseRespond(args: JsonObject): Parsed {
        fun bad(message: String) = Parsed(null, null, message)
        val idEl = args["callId"] as? JsonPrimitive
        val callId = idEl?.takeIf { it.isString }?.contentOrNull ?: return bad("callId must be a string")
        if (!validCallId(callId)) return bad("callId is not a call id")
        val actionEl = args["action"] as? JsonPrimitive
        val action = Action.fromId(actionEl?.takeIf { it.isString }?.contentOrNull)
            ?: return bad("action must be allow, reject or silence")
        val skipLog = flag(args, "skipLog") ?: return bad("skipLog must be true or false")
        val skipNotification = flag(args, "skipNotification") ?: return bad("skipNotification must be true or false")
        if (action != Action.REJECT && (skipLog || skipNotification)) {
            return bad("skipLog and skipNotification only go with reject")
        }
        return Parsed(callId, Decision(action, skipLog, skipNotification), null)
    }

    /** An optional boolean: absent or null is false; anything but a JSON boolean is null (invalid). */
    private fun flag(args: JsonObject, key: String): Boolean? {
        val el = args[key] ?: return false
        if (el is JsonNull) return false
        val p = el as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.booleanOrNull
    }

    fun validCallId(id: String): Boolean =
        id.isNotEmpty() && id.length <= CALL_ID_MAX && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

    /** Whether an answer for a call sent at [sentAt] comes too late at [now] (monotonic clock). */
    fun isLate(sentAt: Long, now: Long): Boolean = now < sentAt || now - sentAt >= DEADLINE_MS

    /** Time left to wait for the holder, from [startedAt] (when Android asked) to the deadline. */
    fun remainingMs(startedAt: Long, now: Long): Long = (DEADLINE_MS - (now - startedAt)).coerceIn(0, DEADLINE_MS)

    /**
     * Decided: the holder sees the caller's number only if it may also read the call log
     * (`calllog.read`) or contacts (`contacts.read`), which show the number anyway once the call
     * is logged or matches a contact. Without either it only learns that a call came.
     */
    fun showNumber(holdsCallLog: Boolean, holdsContacts: Boolean): Boolean = holdsCallLog || holdsContacts

    /**
     * The number as the app sees it: null when not [show]n, withheld, too long, or not made of
     * digits, `+`, spaces, `-`, `(`, `)` (no `*`/`#` codes, no SIP addresses).
     */
    fun visibleNumber(handle: String?, show: Boolean): String? {
        if (!show) return null
        val n = handle?.trim().orEmpty()
        if (n.isEmpty() || n.length > NUMBER_MAX) return null
        if (n.none { it in '0'..'9' }) return null
        if (!n.all { it in '0'..'9' || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }) return null
        return n
    }

    /** Numbers treated as emergency numbers even if Android does not say so (defensive only). */
    val FALLBACK_EMERGENCY = setOf("112", "911", "999", "000", "08", "110", "118", "119")

    /**
     * True when the call must not be screened: Android says so ([platformSays]: the network marks it
     * an emergency call or the number is an emergency number), or the bare number is in [FALLBACK_EMERGENCY].
     */
    fun isEmergency(platformSays: Boolean, number: String?): Boolean {
        if (platformSays) return true
        val digits = number?.filter { it != ' ' && it != '-' }.orEmpty()
        return digits in FALLBACK_EMERGENCY
    }

    /** `calls.screening` payload. Only `incoming` calls are sent today; outgoing ones are allowed at once. */
    fun payload(callId: String, number: String?, verified: Boolean, direction: String = "incoming"): String = buildJsonObject {
        put("callId", JsonPrimitive(callId))
        put("number", number?.let { JsonPrimitive(it) } ?: JsonNull)
        put("verified", JsonPrimitive(verified))
        put("direction", JsonPrimitive(direction))
    }.toString()

    /** Audit metadata of a decision: never the number. */
    fun auditMetadata(reason: String, ms: Long, verified: Boolean): String = buildJsonObject {
        put("reason", JsonPrimitive(reason))
        put("ms", JsonPrimitive(ms))
        put("verified", JsonPrimitive(verified))
    }.toString()

    /** Audit reasons. */
    const val REASON_ANSWERED = "answered"
    const val REASON_TIMEOUT = "timeout"
    const val REASON_NOT_DELIVERED = "not_delivered"
    const val REASON_EMERGENCY = "emergency"
    const val REASON_ERROR = "error"

    /** Audit outcome when the call was allowed because the holder did not decide. */
    const val OUTCOME_DEFAULT_ALLOW = "default_allow"

    /** The audit outcome: the holder's action when it answered, `allow` for emergency calls, else [OUTCOME_DEFAULT_ALLOW]. */
    fun auditOutcome(decision: Decision, reason: String): String = when (reason) {
        REASON_ANSWERED -> decision.action.id
        REASON_EMERGENCY -> Action.ALLOW.id
        else -> OUTCOME_DEFAULT_ALLOW
    }
}

/**
 * PARITY F7b: calls waiting for the holder's answer. Thread-safe. Only the app a call was sent
 * to may answer it, once, and only before [ScreeningSpec.DEADLINE_MS]; times are a monotonic clock.
 */
class ScreeningRegistry(private val newId: () -> String) {
    enum class Reply { APPLIED, UNKNOWN, LATE }

    private class Pending(val webAppId: String, val sentAt: Long, val answer: CompletableDeferred<ScreeningSpec.Decision>)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Registers a call sent to [webAppId] at [now]; returns its id and the answer to await. */
    fun open(webAppId: String, now: Long): Pair<String, CompletableDeferred<ScreeningSpec.Decision>> {
        var id = newId()
        while (pending.containsKey(id)) id = newId()
        val answer = CompletableDeferred<ScreeningSpec.Decision>()
        pending[id] = Pending(webAppId, now, answer)
        return id to answer
    }

    fun respond(webAppId: String, callId: String, decision: ScreeningSpec.Decision, now: Long): Reply {
        val p = pending[callId]?.takeIf { it.webAppId == webAppId } ?: return Reply.UNKNOWN
        if (ScreeningSpec.isLate(p.sentAt, now)) return Reply.LATE
        return if (p.answer.complete(decision)) Reply.APPLIED else Reply.UNKNOWN
    }

    /** The call is decided (or given up); later answers are [Reply.UNKNOWN]. */
    fun close(callId: String) {
        pending.remove(callId)?.answer?.cancel()
    }

    fun size(): Int = pending.size
}
