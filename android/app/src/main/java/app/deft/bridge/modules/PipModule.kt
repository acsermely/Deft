package app.deft.bridge.modules

import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.HostSurfaces
import app.deft.bridge.PipAspect
import app.deft.bridge.jsonArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// DEVICE-UNCHECKED (picture-in-picture): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a video sample continues in PiP.
// Remove this note once checked.
/** Picture-in-picture for the app that is on screen. */
class PipModule : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        val surface = HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        if (surface.isPreview) return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_IN_PREVIEW)
        val ratio = PipAspect.parse(jsonArg(request.args, "aspect"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "aspect must look like 16:9")
        return withContext(Dispatchers.Main) {
            when (request.method) {
                "pip.enter" ->
                    if (surface.enterPip(ratio)) BridgeOutcome.Ok()
                    else BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Picture-in-picture is not available")
                "pip.setAuto" ->
                    if (surface.setAutoPip(jsonArg(request.args, "on") == "true", ratio)) BridgeOutcome.Ok()
                    else BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Picture-in-picture is not available")
                else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
            }
        }
    }
}
