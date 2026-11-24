package app.deft.bridge.modules

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Base64
import androidx.core.content.FileProvider
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BulkRows
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.jsonArg
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class SmsModule(
    private val context: Context,
) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        return when (request.method) {
            "sms.listThreads" -> listThreads(request)
            "sms.listMessages" -> listMessages(request)
            "sms.send" -> send(request)
            "sms.sendMms" -> sendMms(request)
            "sms.markRead" -> markRead(request)
            "sms.delete" -> delete(request)
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private suspend fun listThreads(request: BridgeRequest): BridgeOutcome {
        val limit = BulkRows.limit(request.args["limit"]?.jsonPrimitive?.intOrNull, default = 100)
        val offset = BulkRows.offset(request.args["offset"]?.jsonPrimitive?.intOrNull)
        BulkRows.gate(context, request, "Export messages", limit, "conversations")?.let { return it }
        val uri = Telephony.Sms.Conversations.CONTENT_URI
        val rows = mutableListOf<JsonObject>()
        var skipped = 0
        context.contentResolver.query(
            uri,
            arrayOf(Telephony.Sms.Conversations.THREAD_ID, Telephony.Sms.Conversations.SNIPPET, Telephony.Sms.Conversations.MESSAGE_COUNT),
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && rows.size < limit) {
                if (skipped < offset) {
                    skipped++
                    continue
                }
                rows += buildJsonObject {
                    put("threadId", JsonPrimitive(c.getString(0) ?: ""))
                    put("snippet", JsonPrimitive(c.getString(1) ?: ""))
                    put("count", JsonPrimitive(c.getInt(2)))
                }
            }
        }
        BulkRows.record(request.webAppId, "Export messages", rows.size)
        return BridgeOutcome.Ok(JsonArray(rows), BulkRows.metadata(rows.size))
    }

    private suspend fun listMessages(request: BridgeRequest): BridgeOutcome {
        val threadId = jsonArg(request.args, "threadId")
        val limit = BulkRows.limit(request.args["limit"]?.jsonPrimitive?.intOrNull)
        val offset = BulkRows.offset(request.args["offset"]?.jsonPrimitive?.intOrNull)
        if (offset + limit > BulkRows.MAX_MERGED_WINDOW) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "offset + limit must be at most ${BulkRows.MAX_MERGED_WINDOW}; use a smaller offset")
        }
        BulkRows.gate(context, request, "Export messages", limit, "messages")?.let { return it }
        // Each source returns offset + limit rows; SMS and MMS are merged by date, then the offset is dropped.
        val window = offset + limit
        val selection = if (threadId.isNullOrBlank()) null else "${Telephony.Sms.THREAD_ID}=?"
        val args = if (threadId.isNullOrBlank()) null else arrayOf(threadId)
        val rows = mutableListOf<JsonObject>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
                Telephony.Sms.THREAD_ID,
            ),
            selection,
            args,
            "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            var count = 0
            while (c.moveToNext() && count < window) {
                rows += buildJsonObject {
                    put("id", JsonPrimitive(c.getString(0) ?: ""))
                    put("address", JsonPrimitive(c.getString(1) ?: ""))
                    put("body", JsonPrimitive(c.getString(2) ?: ""))
                    put("date", JsonPrimitive(c.getLong(3)))
                    put("type", JsonPrimitive(c.getInt(4)))
                    put("read", JsonPrimitive(c.getInt(5) == 1))
                    put("threadId", JsonPrimitive(c.getString(6) ?: ""))
                    put("isMms", JsonPrimitive(false))
                }
                count++
            }
        }
        val mmsLimit = (window / 2).coerceAtLeast(1)
        rows += queryMmsRows(Telephony.Mms.MESSAGE_BOX_INBOX, mmsLimit, threadId)
        rows += queryMmsRows(Telephony.Mms.MESSAGE_BOX_SENT, mmsLimit, threadId)
        rows.sortByDescending { (it["date"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L }
        val page = rows.drop(offset).take(limit)
        BulkRows.record(request.webAppId, "Export messages", page.size)
        return BridgeOutcome.Ok(JsonArray(page), BulkRows.metadata(page.size))
    }

    private fun queryMmsRows(box: Int, limit: Int, threadId: String?): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.DATE,
                Telephony.Mms.READ,
                Telephony.Mms.SUBJECT,
                Telephony.Mms.THREAD_ID,
            ),
            "${Telephony.Mms.MESSAGE_BOX}=?" +
                if (threadId.isNullOrBlank()) "" else " AND ${Telephony.Mms.THREAD_ID}=?",
            if (threadId.isNullOrBlank()) arrayOf(box.toString()) else arrayOf(box.toString(), threadId),
            "${Telephony.Mms.DATE} DESC",
        )?.use { c ->
            var count = 0
            while (c.moveToNext() && count < limit) {
                val id = c.getString(0) ?: continue
                val address = mmsAddress(id)
                out += buildJsonObject {
                    put("id", JsonPrimitive("mms:$id"))
                    put("address", JsonPrimitive(address))
                    put("body", JsonPrimitive(c.getString(3) ?: ""))
                    put("date", JsonPrimitive(c.getLong(1) * 1000L))
                    put("type", JsonPrimitive(if (box == Telephony.Mms.MESSAGE_BOX_INBOX) 1 else 2))
                    put("read", JsonPrimitive(c.getInt(2) == 1))
                    put("threadId", JsonPrimitive(c.getString(4) ?: ""))
                    put("isMms", JsonPrimitive(true))
                }
                count++
            }
        }
        return out
    }

    private fun mmsAddress(messageId: String): String {
        val addrUri = Uri.parse("content://mms/$messageId/addr")
        context.contentResolver.query(
            addrUri,
            arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            "${Telephony.Mms.Addr.TYPE}=?",
            arrayOf(MMS_ADDR_TYPE_FROM.toString()),
            null,
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: ""
        }
        context.contentResolver.query(
            addrUri,
            arrayOf(Telephony.Mms.Addr.ADDRESS),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: ""
        }
        return ""
    }

    private fun send(request: BridgeRequest): BridgeOutcome {
        val to = jsonArg(request.args, "to") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "to required")
        val body = jsonArg(request.args, "body") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "body required")
        if (to.any { it == '*' || it == '#' }) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "refusing MMI")
        }
        val subId = optionalSubscriptionId(request)
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "subscriptionId must be a number")
        val sms = smsManager(subId)
        val parts = sms.divideMessage(body)
        if (parts.size <= 1) {
            sms.sendTextMessage(to, null, body, null, null)
        } else {
            sms.sendMultipartTextMessage(to, null, parts, null, null)
        }
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, to)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.READ, 1)
        }
        context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
        return BridgeOutcome.Ok()
    }

    private fun sendMms(request: BridgeRequest): BridgeOutcome {
        val to = jsonArg(request.args, "to") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "to required")
        val body = jsonArg(request.args, "body")
        val base64 = jsonArg(request.args, "base64")
        val mime = jsonArg(request.args, "mime")
        if (to.any { it == '*' || it == '#' }) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "refusing MMI")
        }
        if (body.isNullOrBlank() && base64.isNullOrBlank()) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "body or base64 required")
        }
        val attachment = if (!base64.isNullOrBlank()) {
            runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrElse {
                return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "invalid base64")
            }
        } else {
            null
        }
        if (attachment != null && attachment.size > app.deft.sms.MmsPdu.MAX_ATTACHMENT_BYTES) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "MMS attachment larger than 512KB")
        }
        val pdu = runCatching {
            app.deft.sms.MmsPdu.encodeSendReq(to, body, mime, attachment)
        }.getOrElse {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, it.message ?: "could not build MMS")
        }
        val file = File(File(context.cacheDir, "mms").apply { mkdirs() }, "out_${System.currentTimeMillis()}.pdu")
        file.writeBytes(pdu)
        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        val sms = smsManager(subId)
        sms.sendMultimediaMessage(context, contentUri, null, null, null)
        insertSentMms(to, body)
        return BridgeOutcome.Ok()
    }

    private fun insertSentMms(to: String, body: String?) {
        runCatching {
            val values = ContentValues().apply {
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT)
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L)
                put(Telephony.Mms.READ, 1)
                if (!body.isNullOrBlank()) put(Telephony.Mms.SUBJECT, body.take(100))
            }
            val uri = context.contentResolver.insert(Telephony.Mms.Sent.CONTENT_URI, values)
            val id = uri?.lastPathSegment
            if (id != null) {
                val addr = ContentValues().apply {
                    put(Telephony.Mms.Addr.ADDRESS, to)
                    put(Telephony.Mms.Addr.TYPE, MMS_ADDR_TYPE_TO)
                    put(Telephony.Mms.Addr.MSG_ID, id.toLong())
                    put(Telephony.Mms.Addr.CHARSET, 106)
                }
                context.contentResolver.insert(Uri.parse("content://mms/$id/addr"), addr)
            }
        }
    }

    /** The SIM the user picked, or the default SIM. Null when subscriptionId is not a number. */
    private fun optionalSubscriptionId(request: BridgeRequest): Int? {
        if (!request.args.containsKey("subscriptionId")) return SubscriptionManager.getDefaultSmsSubscriptionId()
        return (request.args["subscriptionId"] as? JsonPrimitive)?.intOrNull
    }

    private fun smsManager(subId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subId)
        }

    private fun markRead(request: BridgeRequest): BridgeOutcome {
        val id = jsonArg(request.args, "id") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "id required")
        if (id.startsWith("mms:")) {
            val mmsId = id.removePrefix("mms:")
            val values = ContentValues().apply { put(Telephony.Mms.READ, 1) }
            context.contentResolver.update(
                Telephony.Mms.CONTENT_URI,
                values,
                "${Telephony.Mms._ID}=?",
                arrayOf(mmsId),
            )
        } else {
            val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            context.contentResolver.update(Telephony.Sms.CONTENT_URI, values, "${Telephony.Sms._ID}=?", arrayOf(id))
        }
        return BridgeOutcome.Ok()
    }

    private fun delete(request: BridgeRequest): BridgeOutcome {
        val id = jsonArg(request.args, "id") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "id required")
        if (id.startsWith("mms:")) {
            val mmsId = id.removePrefix("mms:")
            context.contentResolver.delete(Telephony.Mms.CONTENT_URI, "${Telephony.Mms._ID}=?", arrayOf(mmsId))
        } else {
            context.contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID}=?", arrayOf(id))
        }
        return BridgeOutcome.Ok()
    }
}

private const val MMS_ADDR_TYPE_FROM = 137
private const val MMS_ADDR_TYPE_TO = 151
