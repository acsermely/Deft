package app.deft.bridge.modules

import android.app.NotificationManager
import android.content.Context
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.DndSpec
import app.deft.bridge.HostSurfaces
import app.deft.bridge.jsonArg
import app.deft.special.SpecialAccessFlow
import app.deft.special.SystemAccess
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** `dnd.*` (PARITY F5b): Do Not Disturb, only while the app is on screen. */
class DndModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        val nm = context.getSystemService(NotificationManager::class.java)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Do Not Disturb is not available")
        return when (request.method) {
            "dnd.state" -> BridgeOutcome.Ok(
                buildJsonObject {
                    put("filter", JsonPrimitive(filterName(nm.currentInterruptionFilter)))
                    put("accessGranted", JsonPrimitive(SystemAccess.DND_ACCESS.isGranted(context)))
                },
            )
            "dnd.set" -> setFilter(nm, request)
            "dnd.requestAccess" -> requestAccess(request.webAppId)
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private fun setFilter(nm: NotificationManager, request: BridgeRequest): BridgeOutcome {
        val name = jsonArg(request.args, "filter")
        if (name == null || name !in DndSpec.FILTERS) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "filter must be all, priority, alarms or none")
        }
        if (!SystemAccess.DND_ACCESS.isGranted(context)) {
            return BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Do Not Disturb access")
        }
        return try {
            nm.setInterruptionFilter(filterOf(name))
            BridgeOutcome.Ok(buildJsonObject { put("filter", JsonPrimitive(filterName(nm.currentInterruptionFilter))) })
        } catch (_: SecurityException) {
            BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Do Not Disturb access")
        }
    }

    /**
     * Opens the system screen once (PARITY W7: the deepest one for this app); the user turns the launcher's access on
     * there. The call waits for the user to come back to the app and then answers with the state read again, so the
     * app learns the result without polling.
     */
    private suspend fun requestAccess(webAppId: String): BridgeOutcome {
        if (!SpecialAccessFlow.start(context, SystemAccess.DND_ACCESS)) {
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "This phone has no Do Not Disturb settings screen")
        }
        HostSurfaces.awaitReturn(webAppId)
        return BridgeOutcome.Ok(buildJsonObject { put("accessGranted", JsonPrimitive(SystemAccess.DND_ACCESS.isGranted(context))) })
    }

    private fun filterOf(name: String): Int = when (name) {
        "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
        "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
        "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
        else -> NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun filterName(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
        else -> "all"
    }
}
