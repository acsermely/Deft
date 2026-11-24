package app.deft.telephony

import android.telecom.Call
import android.telecom.InCallService
import app.deft.bridge.BridgeService
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.CopyOnWriteArrayList

class DeftInCallService : InCallService() {
    override fun onCallAdded(call: Call) {
        CallsState.add(call)
        call.registerCallback(callback)
        val details = call.details
        if (details.callDirection == Call.Details.DIRECTION_INCOMING &&
            details.state == Call.STATE_RINGING
        ) {
            val handle = details.handle?.schemeSpecificPart.orEmpty()
            val e164 = if (TelephonyBridge.isSafeE164(handle)) handle else ""
            val visible = if (handle.any { it == '*' || it == '#' }) "" else handle
            BridgeService.telephonyEvent(
                applicationContext,
                "telephony.incoming",
                buildJsonObject {
                    put("handle", JsonPrimitive(visible))
                    put("e164", JsonPrimitive(e164))
                }.toString(),
            )
        }
        emitState(call)
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        CallsState.remove(call)
        BridgeService.telephonyEvent(applicationContext, "telephony.ended", "{}")
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            emitState(call)
        }
    }

    private fun emitState(call: Call) {
        BridgeService.telephonyEvent(
            applicationContext,
            "telephony.state",
            buildJsonObject {
                put("state", JsonPrimitive(stateName(call.state)))
            }.toString(),
        )
    }

    private fun stateName(state: Int): String = when (state) {
        Call.STATE_NEW -> "new"
        Call.STATE_RINGING -> "ringing"
        Call.STATE_DIALING -> "dialing"
        Call.STATE_ACTIVE -> "active"
        Call.STATE_HOLDING -> "holding"
        Call.STATE_DISCONNECTED -> "disconnected"
        Call.STATE_CONNECTING -> "connecting"
        Call.STATE_DISCONNECTING -> "disconnecting"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "select_account"
        Call.STATE_PULLING_CALL -> "pulling"
        else -> "unknown"
    }
}

object CallsState {
    private val calls = CopyOnWriteArrayList<Call>()

    fun add(call: Call) {
        if (!calls.contains(call)) calls.add(call)
    }

    fun remove(call: Call) {
        calls.remove(call)
    }

    fun pick(): Call? {
        calls.firstOrNull { it.state == Call.STATE_RINGING }?.let { return it }
        calls.firstOrNull {
            it.state == Call.STATE_ACTIVE || it.state == Call.STATE_HOLDING ||
                it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING
        }?.let { return it }
        return calls.lastOrNull()
    }
}

/** Shared dialer-role helpers for TelephonyModule (avoids circular imports). */
object TelephonyBridge {
    fun isSafeE164(number: String): Boolean {
        if (number.any { it == '*' || it == '#' }) return false
        return Regex("^\\+[1-9]\\d{6,14}$").matches(number) ||
            Regex("^\\d{7,15}$").matches(number)
    }
}
