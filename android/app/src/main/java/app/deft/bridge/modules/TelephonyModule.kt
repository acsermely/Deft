package app.deft.bridge.modules

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.deft.bridge.OsPermissions
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BulkRows
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.jsonArg
import app.deft.telephony.CallsState
import app.deft.telephony.TelephonyBridge
import app.deft.telephony.UssdCodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

class TelephonyModule(
    private val context: Context,
) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        return when (request.method) {
            "telephony.call" -> call(request)
            "telephony.listSims" -> listSims(request)
            "telephony.blockedList" -> withDialer { blockedList() }
            "telephony.block" -> withDialer { block(request) }
            "telephony.unblock" -> withDialer { unblock(request) }
            "telephony.ussd" -> ussd(request)
            "telephony.answer" -> withDialer { answer() }
            "telephony.reject" -> withDialer { reject() }
            "telephony.end" -> withDialer { end() }
            "telephony.hold" -> withDialer { hold(request) }
            "telephony.dtmf" -> withDialer { dtmf(request) }
            "telephony.listCalls" -> withDialer { listCalls(request) }
            "telephony.deleteCall" -> withDialer { deleteCall(request) }
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private suspend fun withDialer(block: suspend () -> BridgeOutcome): BridgeOutcome {
        if (!isDefaultDialer()) {
            return BridgeOutcome.Err(BridgeErrors.ROLE_MISSING, "Default dialer role required")
        }
        return block()
    }

    private fun isDefaultDialer(): Boolean {
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleHeld(RoleManager.ROLE_DIALER)
    }

    private suspend fun call(request: BridgeRequest): BridgeOutcome {
        val raw = jsonArg(request.args, "e164") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "e164 required")
        val number = raw.trim()
        if (!TelephonyBridge.isSafeE164(number)) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "Refusing MMI/USSD or non E.164 number")
        }
        OsPermissions.ensure(context, request.webAppId, request.background, listOf(Manifest.permission.CALL_PHONE), CALL_PHONE_MISSING)?.let { return it }
        val tm = context.getSystemService(TelecomManager::class.java)
            ?: return BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Telecom unavailable")
        val extras = Bundle()
        val subId = subscriptionArg(request)
        if (subId == null && request.args.containsKey("subscriptionId")) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "subscriptionId must be a number")
        }
        if (subId != null) {
            // Review: a phone account id is usually the ICCID, not the subscription id; ask Android.
            val phone = context.getSystemService(TelephonyManager::class.java)
                ?: return BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Telephony unavailable")
            val accounts = try {
                tm.callCapablePhoneAccounts
            } catch (_: SecurityException) {
                return BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Phone")
            }
            val handle = accounts.firstOrNull { phone.getSubscriptionId(it) == subId }
                ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "No SIM with that subscriptionId can place calls")
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        }
        tm.placeCall(Uri.fromParts("tel", number, null), extras)
        return BridgeOutcome.Ok()
    }

    /** SIM cards by subscriptionId and display name only: never IMSI, ICCID or the phone number. */
    private suspend fun listSims(request: BridgeRequest): BridgeOutcome {
        OsPermissions.ensure(context, request.webAppId, request.background, listOf(Manifest.permission.READ_PHONE_STATE), "READ_PHONE_STATE not granted")
            ?.let { return it }
        val subs = SubscriptionManager.from(context).activeSubscriptionInfoList.orEmpty()
        val arr = buildJsonArray {
            subs.forEach { info ->
                add(
                    buildJsonObject {
                        put("subscriptionId", JsonPrimitive(info.subscriptionId))
                        put("displayName", JsonPrimitive(info.displayName?.toString() ?: ""))
                        put("carrierName", JsonPrimitive(info.carrierName?.toString() ?: ""))
                        put("slot", JsonPrimitive(info.simSlotIndex))
                    },
                )
            }
        }
        return BridgeOutcome.Ok(arr)
    }

    private fun blockedList(): BridgeOutcome {
        val arr = buildJsonArray {
            context.contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
                null,
                null,
                null,
            )?.use { c ->
                var count = 0
                while (c.moveToNext() && count < 500) {
                    add(buildJsonObject { put("number", JsonPrimitive(c.getString(0) ?: "")) })
                    count++
                }
            }
        }
        return BridgeOutcome.Ok(arr)
    }

    private fun block(request: BridgeRequest): BridgeOutcome {
        val number = e164Arg(request) ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "Refusing MMI/USSD or non E.164 number")
        val values = ContentValues().apply {
            put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
        }
        context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values)
        return BridgeOutcome.Ok()
    }

    private fun unblock(request: BridgeRequest): BridgeOutcome {
        val number = e164Arg(request) ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "Refusing MMI/USSD or non E.164 number")
        context.contentResolver.delete(
            BlockedNumberContract.BlockedNumbers.CONTENT_URI,
            "${BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER}=?",
            arrayOf(number),
        )
        return BridgeOutcome.Ok()
    }

    /** Sends a carrier code the user confirmed, and returns the carrier's reply text. */
    private suspend fun ussd(request: BridgeRequest): BridgeOutcome {
        val code = jsonArg(request.args, "code")?.trim()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "code required")
        if (!UssdCodes.isValid(code)) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "code must be a carrier code like *100#; call settings codes are not allowed")
        }
        OsPermissions.ensure(context, request.webAppId, request.background, listOf(Manifest.permission.CALL_PHONE), CALL_PHONE_MISSING)?.let { return it }
        val base = context.getSystemService(TelephonyManager::class.java)
            ?: return BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Telephony unavailable")
        val subId = subscriptionArg(request)
        if (subId == null && request.args.containsKey("subscriptionId")) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "subscriptionId must be a number")
        }
        val tm = if (subId != null) base.createForSubscriptionId(subId) else base
        val result = try {
            withTimeoutOrNull(USSD_TIMEOUT_MS) {
                suspendCancellableCoroutine<UssdResult> { cont ->
                    tm.sendUssdRequest(
                        code,
                        object : TelephonyManager.UssdResponseCallback() {
                            override fun onReceiveUssdResponse(
                                telephonyManager: TelephonyManager,
                                req: String,
                                response: CharSequence,
                            ) {
                                if (cont.isActive) cont.resume(UssdResult.Reply(response.toString()))
                            }

                            override fun onReceiveUssdResponseFailed(
                                telephonyManager: TelephonyManager,
                                req: String,
                                failureCode: Int,
                            ) {
                                if (cont.isActive) cont.resume(UssdResult.Failed(failureCode))
                            }
                        },
                        Handler(Looper.getMainLooper()),
                    )
                }
            }
        } catch (e: SecurityException) {
            return BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "CALL_PHONE not granted to the launcher")
        }
        return when (result) {
            is UssdResult.Reply -> BridgeOutcome.Ok(buildJsonObject { put("response", JsonPrimitive(result.text)) })
            is UssdResult.Failed -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The carrier did not answer (code ${result.code})")
            null -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The carrier did not answer in time")
        }
    }

    /** Optional subscriptionId arg. Null when absent or not a whole number. */
    private fun subscriptionArg(request: BridgeRequest): Int? =
        (request.args["subscriptionId"] as? JsonPrimitive)?.intOrNull

    private fun e164Arg(request: BridgeRequest): String? {
        val number = jsonArg(request.args, "e164")?.trim() ?: return null
        return number.takeIf { TelephonyBridge.isSafeE164(it) }
    }

    private fun answer(): BridgeOutcome {
        val call = CallsState.pick()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "no active call")
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
        return BridgeOutcome.Ok()
    }

    private fun reject(): BridgeOutcome {
        val call = CallsState.pick()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "no active call")
        call.reject(false, null)
        return BridgeOutcome.Ok()
    }

    private fun end(): BridgeOutcome {
        val call = CallsState.pick()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "no active call")
        call.disconnect()
        return BridgeOutcome.Ok()
    }

    private fun hold(request: BridgeRequest): BridgeOutcome {
        val call = CallsState.pick()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "no active call")
        val hold = request.args["hold"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "hold required")
        if (hold) call.hold() else call.unhold()
        return BridgeOutcome.Ok()
    }

    private suspend fun dtmf(request: BridgeRequest): BridgeOutcome {
        val call = CallsState.pick()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "no active call")
        val digit = jsonArg(request.args, "digit")
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "digit required")
        if (digit.length != 1 || digit[0] !in "0123456789*#") {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "digit must be one of 0-9*#")
        }
        call.playDtmfTone(digit[0])
        delay(300)
        call.stopDtmfTone()
        return BridgeOutcome.Ok()
    }

    private suspend fun listCalls(request: BridgeRequest): BridgeOutcome {
        OsPermissions.ensure(context, request.webAppId, request.background, listOf(Manifest.permission.READ_CALL_LOG), "READ_CALL_LOG not granted")
            ?.let { return it }
        val limit = BulkRows.limit(request.args["limit"]?.jsonPrimitive?.intOrNull)
        val offset = BulkRows.offset(request.args["offset"]?.jsonPrimitive?.intOrNull)
        BulkRows.gate(context, request, "Export calls", limit, "calls")?.let { return it }
        val rows = mutableListOf<JsonObject>()
        var skipped = 0
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && rows.size < limit) {
                if (skipped < offset) {
                    skipped++
                    continue
                }
                rows += buildJsonObject {
                    put("id", JsonPrimitive(c.getString(0) ?: ""))
                    put("number", JsonPrimitive(c.getString(1) ?: ""))
                    put("type", JsonPrimitive(c.getInt(2)))
                    put("date", JsonPrimitive(c.getLong(3)))
                    put("duration", JsonPrimitive(c.getLong(4)))
                }
            }
        }
        BulkRows.record(request.webAppId, "Export calls", rows.size)
        return BridgeOutcome.Ok(JsonArray(rows), BulkRows.metadata(rows.size))
    }

    private suspend fun deleteCall(request: BridgeRequest): BridgeOutcome {
        OsPermissions.ensure(context, request.webAppId, request.background, listOf(Manifest.permission.WRITE_CALL_LOG), "WRITE_CALL_LOG not granted")
            ?.let { return it }
        val id = jsonArg(request.args, "id") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "id required")
        context.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID}=?", arrayOf(id))
        return BridgeOutcome.Ok()
    }

    companion object {
        private const val CALL_PHONE_MISSING = "CALL_PHONE not granted to the launcher"

        fun isSafeE164(number: String): Boolean = TelephonyBridge.isSafeE164(number)
    }
}

private const val USSD_TIMEOUT_MS = 30_000L

private sealed class UssdResult {
    data class Reply(val text: String) : UssdResult()
    data class Failed(val code: Int) : UssdResult()
}
