package app.deft.bridge.modules

import android.os.SystemClock
import app.deft.DeftApp
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.telephony.DeftCallScreeningService
import app.deft.telephony.ScreeningRegistry
import app.deft.telephony.ScreeningSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// DEVICE-UNCHECKED (telephony extras (call screening, blocked numbers, SIM choice, USSD)): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a spam-blocker sample rejects a listed number; SIM choice and a safe USSD code work.
// Remove this note once checked.
/**
 * `calls.*` (SPECIAL exclusive `calls.screen`): the holder's answer to a
 * `calls.screening` event. Opens no screen, so background runs may call it. Only the app the call
 * was sent to may answer it, once, within [ScreeningSpec.DEADLINE_MS]; a late or unknown answer
 * returns `{applied: false}` and changes nothing (the call has already rung as usual).
 */
class CallsModule : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = when (request.method) {
        "calls.screenRespond" -> respond(request)
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
    }

    private suspend fun respond(request: BridgeRequest): BridgeOutcome {
        if (DeftApp.instance.repository.specialHolder(DeftCallScreeningService.GROUP) != request.webAppId) {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "calls.screen is revoked")
        }
        val parsed = ScreeningSpec.parseRespond(request.args)
        val callId = parsed.callId
        val decision = parsed.decision
        if (callId == null || decision == null) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, parsed.error ?: "invalid answer")
        }
        val reply = DeftCallScreeningService.registry.respond(request.webAppId, callId, decision, SystemClock.elapsedRealtime())
        val applied = reply == ScreeningRegistry.Reply.APPLIED
        return BridgeOutcome.Ok(
            buildJsonObject { put("applied", applied) },
            metadata = buildJsonObject {
                put("action", decision.action.id)
                put("applied", applied)
                if (reply == ScreeningRegistry.Reply.LATE) put("late", true)
            }.toString(),
        )
    }
}
