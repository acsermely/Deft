package app.deft.bridge.modules

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.deft.DeftApp
import app.deft.bridge.OsPermissions
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.NotifySpec
import app.deft.bridge.OriginNotifications
import app.deft.bridge.jsonArg
import app.deft.data.originOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/** `notify.*`: channels, rich posts, update, cancel, list. */
class NotifyModule(
    private val context: Context,
) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        val appName = DeftApp.instance.repository.getApp(request.webAppId)?.name ?: "Web app"
        return when (request.method) {
            "notify.post", "notify.update" -> post(request, appName)
            "notify.cancel" -> {
                val id = jsonArg(request.args, "id") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "id required")
                OriginNotifications.cancel(context, request.webAppId, id)
                BridgeOutcome.Ok()
            }
            "notify.list" -> BridgeOutcome.Ok(
                buildJsonArray { OriginNotifications.active(context, request.webAppId).forEach { add(JsonPrimitive(it)) } },
            )
            "notify.createChannel" -> {
                val ch = NotifySpec.channel(request.args).getOrElse { return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, it.message ?: "bad channel") }
                val existing = OriginNotifications.channels(context, request.webAppId)
                if (existing.none { it.first == ch.id } && existing.size >= NotifySpec.MAX_CHANNELS) {
                    return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "at most ${NotifySpec.MAX_CHANNELS} channels")
                }
                OriginNotifications.createChannel(context, request.webAppId, appName, ch)
                BridgeOutcome.Ok()
            }
            "notify.deleteChannel" -> {
                val id = jsonArg(request.args, "id") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "id required")
                OriginNotifications.deleteChannel(context, request.webAppId, id)
                BridgeOutcome.Ok()
            }
            "notify.listChannels" -> BridgeOutcome.Ok(
                buildJsonArray {
                    OriginNotifications.channels(context, request.webAppId).forEach { (id, name) ->
                        add(buildJsonObject { put("id", JsonPrimitive(id)); put("name", JsonPrimitive(name)) })
                    }
                },
            )
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private suspend fun post(request: BridgeRequest, appName: String): BridgeOutcome {
        val spec = NotifySpec.post(request.args) { UUID.randomUUID().toString() }
            .getOrElse { return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, it.message ?: "bad notification") }
        if (request.method == "notify.update" && jsonArg(request.args, "id") == null) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "notify.update needs the id of a posted notification")
        }
        if (spec.url != null && originOf(spec.url) != request.origin) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "url must be same origin")
        }
        // POST_NOTIFICATIONS is a runtime permission from Android 13 (minSdk is 30).
        if (Build.VERSION.SDK_INT >= 33) {
            OsPermissions.ensure(context, request.webAppId, request.background, listOf(Manifest.permission.POST_NOTIFICATIONS), "POST_NOTIFICATIONS not granted")
                ?.let { return it }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "POST_NOTIFICATIONS not granted")
        }
        return runCatching { OriginNotifications.post(context, request.webAppId, appName, spec) }
            .fold(
                onSuccess = { BridgeOutcome.Ok(buildJsonObject { put("id", JsonPrimitive(spec.id)) }) },
                onFailure = { BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, it.message ?: "could not post") },
            )
    }
}
