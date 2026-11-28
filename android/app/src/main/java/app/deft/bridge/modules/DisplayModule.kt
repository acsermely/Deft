package app.deft.bridge.modules

import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.HostSurfaces
import app.deft.bridge.jsonArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// DEVICE-UNCHECKED (screen privacy): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: screenshots of a secure screen come out black; an overlay app cannot draw over it.
// Remove this note once checked.
/** Screen controls for the app that is on screen. */
class DisplayModule : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        val surface = HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        if (surface.isPreview && !worksInPreview(request.method)) {
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_IN_PREVIEW)
        }
        return withContext(Dispatchers.Main) {
            when (request.method) {
                "display.keepScreenOn" -> {
                    surface.keepScreenOn(jsonArg(request.args, "on") == "true")
                    BridgeOutcome.Ok()
                }
                "display.setOrientation" -> {
                    val o = jsonArg(request.args, "orientation").orEmpty()
                    if (surface.setOrientation(o)) BridgeOutcome.Ok()
                    else BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "orientation must be any, natural, portrait, landscape, portrait-primary, portrait-secondary, landscape-primary or landscape-secondary")
                }
                "display.print" ->
                    if (surface.print(jsonArg(request.args, "title"))) BridgeOutcome.Ok()
                    else BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Printing is not available")
                "display.secure" -> {
                    surface.setSecure(jsonArg(request.args, "on") == "true")
                    BridgeOutcome.Ok()
                }
                "display.hideOverlays" ->
                    if (surface.hideOverlays(jsonArg(request.args, "on") == "true")) BridgeOutcome.Ok()
                    else BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Hiding overlays needs Android 12 or newer")
                else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
            }
        }
    }

    companion object {
        /** Keep-screen-on and print fit a panel inside the home screen; the rest changes the launcher's whole window. */
        internal fun worksInPreview(method: String): Boolean = method == "display.keepScreenOn" || method == "display.print"
    }
}
