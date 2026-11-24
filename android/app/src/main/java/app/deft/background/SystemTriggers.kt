package app.deft.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * PARITY D2: the `network` and `charging` triggers, watched while [app.deft.bridge.BridgeService]
 * runs. `network` follows the default network and is reported 30 s after it settles, only when
 * `{connected, metered}` changed; `charging` is reported on every plug and unplug. [wake] gets
 * the trigger name and payload and fans it out to the apps that declare it.
 */
class SystemTriggers(
    private val context: Context,
    private val wake: (name: String, payloadJson: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val network = TriggerDebounce<BackgroundTriggers.NetworkState>(BackgroundTriggers.NETWORK_QUIET_MS)
    private var started = false

    private val fireNetwork = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val state = network.due(now)
            if (state != null) {
                wake(BackgroundTriggers.NETWORK, state.payload())
                return
            }
            // Not quiet yet (a change slipped in between): try again when it is.
            network.offer(lastSeen ?: return, now)?.let { main.postDelayed(this, (it - now).coerceAtLeast(0)) }
        }
    }

    private var lastSeen: BackgroundTriggers.NetworkState? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(net: Network, caps: NetworkCapabilities) = seen(stateOf(caps))
        override fun onLost(net: Network) = seen(BackgroundTriggers.NetworkState.OFFLINE)
    }

    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val charging = when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> return
            }
            wake(BackgroundTriggers.CHARGING, BackgroundTriggers.chargingPayload(charging))
        }
    }

    /** Main thread. */
    fun start() {
        if (started) return
        started = true
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val now = caps?.let { stateOf(it) } ?: BackgroundTriggers.NetworkState.OFFLINE
            lastSeen = now
            network.baseline(now)
            cm.registerDefaultNetworkCallback(networkCallback, main)
        }.onFailure { Log.w(TAG, "network trigger unavailable", it) }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, power, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Main thread. */
    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(fireNetwork)
        runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        runCatching { context.unregisterReceiver(power) }
    }

    private fun seen(state: BackgroundTriggers.NetworkState) {
        lastSeen = state
        val now = SystemClock.elapsedRealtime()
        main.removeCallbacks(fireNetwork)
        val at = network.offer(state, now) ?: return
        main.postDelayed(fireNetwork, (at - now).coerceAtLeast(0))
    }

    private fun stateOf(caps: NetworkCapabilities): BackgroundTriggers.NetworkState =
        BackgroundTriggers.NetworkState.of(
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED),
        )

    private companion object {
        const val TAG = "SystemTriggers"
    }
}
