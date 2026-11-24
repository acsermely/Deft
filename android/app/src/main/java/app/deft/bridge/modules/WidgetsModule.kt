package app.deft.bridge.modules

import android.content.Context
import app.deft.DeftApp
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.jsonArg
import app.deft.data.originOf
import app.deft.widgets.WidgetStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** `widgets.*` (PARITY C4): data cards on the Home screen. */
class WidgetsModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        val store = WidgetStore.get(context)
        return when (request.method) {
            "widgets.update" -> {
                val id = jsonArg(request.args, "id") ?: "main"
                if (!WidgetStore.ID.matches(id)) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "id must be 1-32 chars [a-z0-9_-]")
                val title = jsonArg(request.args, "title")?.take(40) ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "title required")
                val url = jsonArg(request.args, "url")
                if (url != null && originOf(url) != request.origin) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "url must be same origin")
                val name = DeftApp.instance.repository.getApp(request.webAppId)?.name ?: "Web app"
                val ok = store.upsert(
                    WidgetStore.Card(
                        webAppId = request.webAppId,
                        id = id,
                        appName = name,
                        title = title,
                        text = jsonArg(request.args, "text")?.take(120).orEmpty(),
                        value = jsonArg(request.args, "value")?.take(16),
                        url = url,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                if (ok) BridgeOutcome.Ok() else BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "at most ${WidgetStore.MAX_PER_APP} widgets per app")
            }
            "widgets.remove" -> {
                store.remove(request.webAppId, jsonArg(request.args, "id"))
                BridgeOutcome.Ok()
            }
            "widgets.list" -> BridgeOutcome.Ok(
                buildJsonArray {
                    store.forApp(request.webAppId).forEach { c ->
                        add(buildJsonObject { put("id", JsonPrimitive(c.id)); put("title", JsonPrimitive(c.title)) })
                    }
                },
            )
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }
}
