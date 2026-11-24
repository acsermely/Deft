package app.deft.telephony

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.telephony.TelephonyManager
import android.util.Log
import app.deft.DeftApp
import app.deft.background.BackgroundTriggers
import app.deft.background.BackgroundWake
import app.deft.bridge.BridgeService
import app.deft.catalog.Capability
import app.deft.catalog.isGranted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PARITY F7b: the launcher's call screening service, for the one web app holding `calls.screen`.
 * Declared disabled; `RoleComponents` enables it only while a holder exists. As the default dialer
 * the launcher gets every incoming call here; otherwise it needs Android's call screening role,
 * which the special-access sheet asks for.
 *
 * Each incoming call goes to the holder as `calls.screening` (live if its page is open, else a
 * background run with the urgent trigger `screening`), and its `calls.screenRespond` decides.
 * The call is never held longer than [ScreeningSpec.DEADLINE_MS]: no answer, no holder, no way to
 * reach it, or any failure lets the call ring as usual. Outgoing and emergency calls are allowed
 * at once and never sent. Each decision for a holder is an audit row, without the number.
 */
class DeftCallScreeningService : CallScreeningService() {
    // Not cancelled on destroy: each call's work ends by itself within the deadline, and its
    // final answer and audit row must still go out.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onScreenCall(details: Call.Details) {
        val startedAt = SystemClock.elapsedRealtime()
        val responded = AtomicBoolean(false)
        fun answer(decision: ScreeningSpec.Decision) {
            if (!responded.compareAndSet(false, true)) return
            runCatching { respondToCall(details, build(decision)) }
                .onFailure { Log.w(TAG, "response not sent", it) }
        }
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            answer(ScreeningSpec.Decision.ALLOW)
            return
        }
        val handle = runCatching { details.handle?.schemeSpecificPart }.getOrNull()
        val verified = runCatching {
            details.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_PASSED
        }.getOrDefault(false)
        if (ScreeningSpec.isEmergency(platformEmergency(details, handle), handle)) {
            answer(ScreeningSpec.Decision.ALLOW)
            scope.launch { auditForHolder(ScreeningSpec.Decision.ALLOW, ScreeningSpec.REASON_EMERGENCY, startedAt, verified) }
            return
        }
        scope.launch {
            var decision = ScreeningSpec.Decision.ALLOW
            var reason = ScreeningSpec.REASON_TIMEOUT
            var holder: String? = null
            try {
                val routed = withTimeoutOrNull(ScreeningSpec.remainingMs(startedAt, SystemClock.elapsedRealtime())) {
                    route(handle, verified) { holder = it }
                }
                if (routed != null) {
                    decision = routed.first
                    reason = routed.second
                }
            } catch (t: Throwable) {
                Log.w(TAG, "screening failed", t)
                reason = ScreeningSpec.REASON_ERROR
            } finally {
                answer(decision)
            }
            holder?.let { audit(it, decision, reason, startedAt, verified) }
        }
    }

    /**
     * Sends the call to the holder and waits for its answer (the caller bounds the wait). A call
     * with no holder, or with no way to reach it, is allowed at once.
     */
    private suspend fun route(
        handle: String?,
        verified: Boolean,
        onHolder: (String) -> Unit,
    ): Pair<ScreeningSpec.Decision, String> {
        val allowNow = ScreeningSpec.Decision.ALLOW to ScreeningSpec.REASON_NOT_DELIVERED
        val repo = DeftApp.instance.repository
        val holder = repo.specialHolder(GROUP) ?: return allowNow
        if (!repo.grantLevel(holder, CAP).isGranted()) return allowNow
        onHolder(holder)
        val show = ScreeningSpec.showNumber(
            holdsCallLog = repo.grantLevel(holder, Capability.CALLLOG_READ).isGranted(),
            holdsContacts = repo.grantLevel(holder, Capability.CONTACTS_READ).isGranted(),
        )
        val (callId, pending) = registry.open(holder, SystemClock.elapsedRealtime())
        try {
            val payload = ScreeningSpec.payload(callId, ScreeningSpec.visibleNumber(handle, show), verified)
            // Live only, never queued: a stored event would arrive long after the call.
            val live = BridgeService.instanceOrNull()?.emitLive(holder, ScreeningSpec.EVENT, payload) == true
            if (!live) {
                val wakes = BackgroundWake.targets(repo, BackgroundTriggers.SCREENING, only = holder).isNotEmpty()
                if (!wakes) return allowNow
                if (!BridgeService.backgroundTrigger(this, holder, BackgroundTriggers.SCREENING, payload)) return allowNow
            }
            return pending.await() to ScreeningSpec.REASON_ANSWERED
        } finally {
            registry.close(callId)
        }
    }

    private fun platformEmergency(details: Call.Details, handle: String?): Boolean {
        val network = runCatching {
            details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)
        }.getOrDefault(false)
        if (network) return true
        if (handle.isNullOrBlank()) return false
        return runCatching {
            getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(handle) == true
        }.getOrDefault(false)
    }

    private fun build(decision: ScreeningSpec.Decision): CallResponse {
        val r = ScreeningSpec.response(decision)
        return CallResponse.Builder()
            .setDisallowCall(r.disallowCall)
            .setRejectCall(r.rejectCall)
            .setSilenceCall(r.silenceCall)
            .setSkipCallLog(r.skipCallLog)
            .setSkipNotification(r.skipNotification)
            .build()
    }

    private suspend fun auditForHolder(decision: ScreeningSpec.Decision, reason: String, startedAt: Long, verified: Boolean) {
        val holder = runCatching { DeftApp.instance.repository.specialHolder(GROUP) }.getOrNull() ?: return
        audit(holder, decision, reason, startedAt, verified)
    }

    private suspend fun audit(holder: String, decision: ScreeningSpec.Decision, reason: String, startedAt: Long, verified: Boolean) {
        val outcome = ScreeningSpec.auditOutcome(decision, reason)
        val ms = SystemClock.elapsedRealtime() - startedAt
        runCatching {
            DeftApp.instance.repository.log(
                holder, "screening", CAP.id, ScreeningSpec.EVENT, outcome,
                ScreeningSpec.auditMetadata(reason, ms, verified),
            )
        }
    }

    companion object {
        private const val TAG = "DeftCallScreening"
        val CAP = Capability.CALLS_SCREEN
        val GROUP: String = Capability.CALLS_SCREEN.exclusiveGroup!!

        /** Calls waiting for `calls.screenRespond`. */
        val registry = ScreeningRegistry { UUID.randomUUID().toString().replace("-", "") }

        fun component(context: Context): ComponentName = ComponentName(context, DeftCallScreeningService::class.java)
    }
}
