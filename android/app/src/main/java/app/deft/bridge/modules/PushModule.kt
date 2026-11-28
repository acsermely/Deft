package app.deft.bridge.modules

import android.content.Context
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.push.PushRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// DEVICE-UNCHECKED (UnifiedPush): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: an ntfy-backed sample receives a push with the app closed.
// Remove this note once checked.
/**
 * `push.*`: a UnifiedPush endpoint per app, from a distributor app such as ntfy.
 * Messages arrive through `push.PushReceiver`; endpoints are not written to the audit log.
 */
class PushModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = withContext(Dispatchers.IO) {
        when (request.method) {
            "push.register" -> register(request.webAppId)
            "push.unregister" -> {
                PushRegistry.unregister(context, request.webAppId)
                BridgeOutcome.Ok()
            }
            "push.status" -> status(request.webAppId)
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private suspend fun register(webAppId: String): BridgeOutcome {
        val waiter = PushRegistry.register(context, webAppId)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, NO_DISTRIBUTOR)
        // Already registered: the distributor answers again, and a new endpoint arrives as push.endpointChanged.
        PushRegistry.endpoint(context, webAppId)?.let { return endpoint(it) }
        val endpoint = withTimeoutOrNull(REGISTER_TIMEOUT_MS) { waiter.await() }
        return when {
            endpoint != null -> endpoint(endpoint)
            waiter.isCompleted -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The push app did not accept this app")
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The push app did not answer. Open it and try again")
        }
    }

    private fun endpoint(endpoint: String) = BridgeOutcome.Ok(buildJsonObject { put("endpoint", JsonPrimitive(endpoint)) })

    private fun status(webAppId: String): BridgeOutcome {
        val endpoint = PushRegistry.endpoint(context, webAppId)
        val distributor = PushRegistry.distributor(context)
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("registered", JsonPrimitive(endpoint != null))
                if (endpoint != null) put("endpoint", JsonPrimitive(endpoint))
                if (distributor != null) put("distributor", JsonPrimitive(distributor))
            },
        )
    }

    companion object {
        const val NO_DISTRIBUTOR = "Install a UnifiedPush distributor such as ntfy"
        const val REGISTER_TIMEOUT_MS = 15_000L
    }
}
