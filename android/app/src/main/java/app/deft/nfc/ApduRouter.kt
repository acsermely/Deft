package app.deft.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Base64
import app.deft.bridge.BridgeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * PARITY B3/B4: sends each reader command of one card emulation service to the app holding it, as
 * [commandEvent] `{requestId, base64}`, and waits up to [HceRouting.RESPONSE_TIMEOUT_MS] for its
 * answer. Only an open page of the holder gets commands (never queued, no background run: a cold
 * start does not fit in the window). No holder, no open page or no answer in time: null (6F00).
 */
class ApduRouter(
    private val commandEvent: String,
    private val deactivatedEvent: String,
    private val holderId: suspend () -> String?,
) {
    private val pending = HcePending()

    suspend fun route(apdu: ByteArray): ByteArray? {
        val holder = holderId() ?: return null
        val service = BridgeService.instanceOrNull() ?: return null
        if (!service.isLive(holder)) return null
        val (requestId, deferred) = pending.open(holder)
        try {
            val payload = buildJsonObject {
                put("requestId", JsonPrimitive(requestId))
                put("base64", JsonPrimitive(Base64.encodeToString(apdu, Base64.NO_WRAP)))
            }.toString()
            if (!service.emitLive(holder, commandEvent, payload)) return null
            return withTimeoutOrNull(HceRouting.RESPONSE_TIMEOUT_MS) { deferred.await() }
        } finally {
            pending.close(requestId)
        }
    }

    suspend fun deactivated(linkLoss: Boolean) {
        val holder = holderId() ?: return
        val payload = buildJsonObject { put("reason", JsonPrimitive(HceRouting.deactivationReason(linkLoss))) }.toString()
        BridgeService.instanceOrNull()?.emitLive(holder, deactivatedEvent, payload)
    }

    /** The holder's answer to a command; only the app that received it may answer. */
    fun respond(webAppId: String, requestId: String, response: ByteArray): Boolean =
        pending.respond(webAppId, requestId, response)
}

/** PARITY B3/B4: a [HostApduService] whose commands go through [router]; answers 6F00 when no app does. */
abstract class RoutedApduService : HostApduService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    protected abstract val router: ApduRouter

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {
        scope.launch {
            val response = router.route(commandApdu) ?: HceRouting.SW_NO_ANSWER
            sendResponseApdu(response)
        }
        return null
    }

    override fun onDeactivated(reason: Int) {
        scope.launch { router.deactivated(reason == DEACTIVATION_LINK_LOSS) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
