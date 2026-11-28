package app.deft.bridge.modules

import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.BridgeService
import app.deft.bridge.RunContext

// DEVICE-UNCHECKED (headless background runner): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a background task runs after the app process is killed and shows in the audit log.
// Remove this note once checked.
/** `background.*`: a background run says it has finished, so its page closes early. */
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
