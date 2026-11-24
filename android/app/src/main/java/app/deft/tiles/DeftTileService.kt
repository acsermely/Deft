package app.deft.tiles

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.deft.bridge.BridgeService
import app.deft.DeftApp
import app.deft.ui.host.WebViewHostActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One Quick Settings tile slot (PARITY C5). The owning app sets its label and state; taps become `tile.clicked`. */
abstract class DeftTileService(private val slot: Int) : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val state = TileSlots.state(this, slot)
        if (state == null) {
            tile.label = "Deft tile $slot"
            tile.subtitle = "Not used"
            tile.state = Tile.STATE_UNAVAILABLE
        } else {
            tile.label = state.label
            tile.subtitle = state.subtitle
            tile.state = if (state.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    override fun onClick() {
        val state = TileSlots.state(this, slot) ?: return
        val payload = buildJsonObject { put("active", JsonPrimitive(state.active)) }.toString()
        val service = BridgeService.instanceOrNull()
        val live = service?.isLive(state.webAppId) == true
        CoroutineScope(Dispatchers.Default).launch {
            if (service != null) service.emit(state.webAppId, "tile.clicked", payload)
            else DeftApp.instance.repository.enqueueEvent(state.webAppId, "tile.clicked", payload)
        }
        // Open the app when it asked for that, or when it is not running to handle the tap.
        if (state.opensApp || !live) {
            val intent = WebViewHostActivity.intent(this, state.webAppId)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, slot, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
    }
}

class DeftTile1 : DeftTileService(1)
class DeftTile2 : DeftTileService(2)
class DeftTile3 : DeftTileService(3)
