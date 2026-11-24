package app.deft.bridge.modules

import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.BridgeService
import app.deft.bridge.RunContext

/** `background.*` (PARITY D1): a background run says it has finished, so its page closes early. */
class BackgroundModule(private val service: BridgeService) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = when (request.method) {
        "background.done" -> if (!request.background || request.context != RunContext.RUN) {
            BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Only a background run can call this")
        } else {
            service.background.done(request.webAppId)
            BridgeOutcome.Ok()
        }
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
    }
}
