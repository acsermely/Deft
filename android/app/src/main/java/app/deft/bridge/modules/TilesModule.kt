package app.deft.bridge.modules

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService
import app.deft.R
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.jsonArg
import app.deft.tiles.TileSlots
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.coroutines.resume

/** `tiles.*` (PARITY C5): claim a Quick Settings tile slot, update it, release it. */
class TilesModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = when (request.method) {
        "tiles.request" -> request(request)
        "tiles.update" -> update(request)
        "tiles.release" -> {
            TileSlots.slotOf(context, request.webAppId)?.let { refresh(it) }
            TileSlots.release(context, request.webAppId)
            BridgeOutcome.Ok()
        }
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
    }

    private fun component(slot: Int) = ComponentName(context, TileSlots.serviceClass(slot))

    private fun refresh(slot: Int) = TileService.requestListeningState(context, component(slot))

    private suspend fun request(request: BridgeRequest): BridgeOutcome {
        val label = jsonArg(request.args, "label")?.take(30) ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "label required")
        val slot = TileSlots.claim(context, request.webAppId, label)
            ?: return BridgeOutcome.Err(BridgeErrors.IN_USE, "all ${TileSlots.COUNT} tile slots are taken")
        refresh(slot)
        if (Build.VERSION.SDK_INT < 33) {
            return BridgeOutcome.Ok(buildJsonObject { put("slot", JsonPrimitive(slot)); put("added", JsonPrimitive(false)) })
        }
        // Android shows its own "Add tile?" dialog; the app is told what the user chose.
        val result = suspendCancellableCoroutine<Int> { cont ->
            val sbm = context.getSystemService(StatusBarManager::class.java)
            if (sbm == null) {
                cont.resume(-1)
                return@suspendCancellableCoroutine
            }
            sbm.requestAddTileService(
                component(slot),
                label,
                Icon.createWithResource(context, R.drawable.ic_notification),
                context.mainExecutor,
            ) { code -> if (cont.isActive) cont.resume(code) }
        }
        val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
            result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
        return BridgeOutcome.Ok(buildJsonObject { put("slot", JsonPrimitive(slot)); put("added", JsonPrimitive(added)) })
    }

    private fun update(request: BridgeRequest): BridgeOutcome {
        val slot = TileSlots.slotOf(context, request.webAppId)
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "call tiles.request first")
        TileSlots.update(
            context,
            slot,
            label = jsonArg(request.args, "label"),
            subtitle = jsonArg(request.args, "subtitle"),
            active = jsonArg(request.args, "active")?.let { it == "true" },
            opensApp = jsonArg(request.args, "open")?.let { it == "true" },
        )
        refresh(slot)
        return BridgeOutcome.Ok()
    }
}
