package app.deft.bridge.modules

import android.content.Context
import app.deft.alarms.AlarmScheduler
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.jsonArg
import app.deft.data.AlarmEntity
import app.deft.data.originOf
import app.deft.DeftApp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class AlarmsModule(
    private val context: Context,
) : BridgeModule {
    private val repo get() = DeftApp.instance.repository

    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        return when (request.method) {
            "alarms.schedule" -> schedule(request)
            "alarms.cancel" -> cancel(request)
            "alarms.list" -> list(request)
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private suspend fun schedule(request: BridgeRequest): BridgeOutcome {
        val triggerAt = request.args["triggerAt"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "triggerAt required (epoch ms)")
        val title = jsonArg(request.args, "title") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "title required")
        val body = jsonArg(request.args, "body") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "body required")
        val url = jsonArg(request.args, "url")
        if (url != null) {
            val app = repo.getApp(request.webAppId)
                ?: return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "unknown app")
            if (originOf(url) != app.origin) {
                return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "url must be same origin")
            }
        }
        if (triggerAt <= System.currentTimeMillis()) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "triggerAt must be in the future")
        }
        val id = UUID.randomUUID().toString()
        val row = AlarmEntity(
            id = id,
            webAppId = request.webAppId,
            triggerAt = triggerAt,
            title = title,
            body = body,
            url = url,
        )
        repo.insertAlarm(row)
        val exact = AlarmScheduler.schedule(context, row)
        // PARITY D1: a background run never opens the settings screen; the alarm is inexact then.
        if (!exact && !request.background) {
            AlarmScheduler.requestExactAlarmAccess(context)
        }
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("exact", JsonPrimitive(exact))
                if (!exact) put("warning", JsonPrimitive("inexact_fallback"))
            },
        )
    }

    private suspend fun cancel(request: BridgeRequest): BridgeOutcome {
        val id = jsonArg(request.args, "id") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "id required")
        val existing = repo.getAlarm(id)
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "alarm not found")
        if (existing.webAppId != request.webAppId) {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "alarm belongs to another app")
        }
        repo.deleteAlarm(id)
        AlarmScheduler.cancel(context, id)
        return BridgeOutcome.Ok()
    }

    private suspend fun list(request: BridgeRequest): BridgeOutcome {
        val rows = repo.alarmsForApp(request.webAppId)
        val arr = buildJsonArray {
            for (row in rows) {
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(row.id))
                        put("triggerAt", JsonPrimitive(row.triggerAt))
                        put("title", JsonPrimitive(row.title))
                        put("body", JsonPrimitive(row.body))
                        if (row.url != null) put("url", JsonPrimitive(row.url))
                    },
                )
            }
        }
        return BridgeOutcome.Ok(arr)
    }
}
