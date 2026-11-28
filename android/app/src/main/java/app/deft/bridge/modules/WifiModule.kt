package app.deft.bridge.modules

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.deft.DeftApp
import app.deft.bridge.OsPermissions
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.HostSurfaces
import app.deft.bridge.WifiSpec
import app.deft.bridge.jsonArg
import app.deft.catalog.Capability
import app.deft.catalog.isGranted
import app.deft.permissions.OsPermissionHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

// DEVICE-UNCHECKED (Wi-Fi scan and control): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a Wi-Fi analyzer sample lists networks.
// Remove this note once checked.
/**
 * `wifi.*`: nearby networks and the current one (`wifi.scan`, which also needs
 * Your location), the Wi-Fi panel (`wifi.openPanel`, only while the app is on screen) and
 * network suggestions (`wifi.suggest`). Android does not let apps switch Wi-Fi on or off.
 */
class WifiModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = when (request.method) {
        "wifi.scan" -> scan(request)
        "wifi.info" -> info(request)
        "wifi.openPanel" -> openPanel(request)
        "wifi.suggest" -> suggest(request)
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
    }

    private suspend fun scan(request: BridgeRequest): BridgeOutcome {
        locationGate(request)?.let { return it }
        val wifi = wifiManager() ?: return unsupported()
        return try {
            val fresh = requestScan(wifi)
            val rows = wifi.scanResults.sortedByDescending { it.level }
            BridgeOutcome.Ok(
                buildJsonObject {
                    put("fresh", JsonPrimitive(fresh))
                    put("networks", buildJsonArray { rows.forEach { add(networkJson(it)) } })
                },
            )
        } catch (_: SecurityException) {
            permissionMissing()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun info(request: BridgeRequest): BridgeOutcome {
        locationGate(request)?.let { return it }
        val wifi = wifiManager() ?: return unsupported()
        return try {
            val info = wifi.connectionInfo
            BridgeOutcome.Ok(
                buildJsonObject {
                    put("ssid", JsonPrimitive(WifiSpec.ssid(info.ssid)))
                    put("bssid", JsonPrimitive(WifiSpec.bssid(info.bssid)))
                    put("rssi", JsonPrimitive(info.rssi))
                    put("linkSpeed", JsonPrimitive(info.linkSpeed))
                },
            )
        } catch (_: SecurityException) {
            permissionMissing()
        }
    }

    private fun openPanel(request: BridgeRequest): BridgeOutcome {
        HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        val intent = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            BridgeOutcome.Ok()
        } catch (_: ActivityNotFoundException) {
            BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "This phone has no Wi-Fi settings screen")
        }
    }

    private fun suggest(request: BridgeRequest): BridgeOutcome {
        val ssid = jsonArg(request.args, "ssid")
            ?.takeIf { WifiSpec.ssidValid(it) }
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "ssid must be 1 to 32 bytes")
        val passphrase = jsonArg(request.args, "passphrase")
        if (passphrase != null && !WifiSpec.passphraseValid(passphrase)) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "passphrase must be 8 to 63 printable characters")
        }
        val wifi = wifiManager() ?: return unsupported()
        val suggestion = try {
            WifiNetworkSuggestion.Builder().apply {
                setSsid(ssid)
                if (passphrase != null) setWpa2Passphrase(passphrase)
            }.build()
        } catch (_: IllegalArgumentException) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "That network name or password cannot be used")
        }
        val status = try {
            wifi.addNetworkSuggestions(listOf(suggestion))
        } catch (_: SecurityException) {
            return permissionMissing()
        }
        return when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ->
                BridgeOutcome.Ok(buildJsonObject { put("status", JsonPrimitive("added")) })
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE ->
                BridgeOutcome.Ok(buildJsonObject { put("status", JsonPrimitive("duplicate")) })
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP ->
                BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "This app has too many suggested networks")
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Android did not accept the network")
        }
    }

    /** Null when the app may read Wi-Fi networks; otherwise the error to return. */
    private suspend fun locationGate(request: BridgeRequest): BridgeOutcome.Err? {
        val webAppId = request.webAppId
        val repo = DeftApp.instance.repository
        if (!repo.grantLevel(webAppId, Capability.LOCATION).isGranted()) {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "location")
        }
        // Review: nearby networks tell where the phone is, so only on screen, or in a background
        // run of an app that may use location in the background.
        if (HostSurfaces.forRequest(request) == null &&
            !(request.background && repo.grantLevel(webAppId, Capability.LOCATION_BACKGROUND).isGranted())
        ) {
            return BridgeOutcome.Err(BridgeErrors.NEEDS_FOREGROUND, "Open the app to see Wi-Fi networks")
        }
        OsPermissions.ensure(
            context,
            webAppId, request.background,
            OsPermissionHelper.runtimePermissionsFor(listOf(Capability.WIFI_SCAN)),
            WIFI_PERMISSION_MISSING,
        )?.let { return it }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            return permissionMissing()
        }
        return null
    }

    /**
     * Asks Android for a fresh scan and waits for its result broadcast. Android throttles scans
     * (about four per two minutes), so a refused start returns false and the cached results are used.
     */
    private suspend fun requestScan(wifi: WifiManager): Boolean {
        val done = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                done.complete(Unit)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            @Suppress("DEPRECATION")
            val started = runCatching { wifi.startScan() }.getOrDefault(false)
            if (!started) return false
            return withTimeoutOrNull(SCAN_TIMEOUT_MS) { done.await() } != null
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @Suppress("DEPRECATION")
    private fun networkJson(row: ScanResult): JsonObject = buildJsonObject {
        put("ssid", JsonPrimitive(WifiSpec.ssid(row.SSID)))
        put("bssid", JsonPrimitive(WifiSpec.bssid(row.BSSID)))
        put("level", JsonPrimitive(row.level))
        put("frequency", JsonPrimitive(row.frequency))
        put("band", JsonPrimitive(WifiSpec.band(row.frequency)))
        put("security", JsonPrimitive(WifiSpec.security(row.capabilities)))
    }

    private fun wifiManager(): WifiManager? = context.getSystemService(WifiManager::class.java)

    private fun unsupported() = BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Wi-Fi is not available on this phone")

    private fun permissionMissing() = BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, WIFI_PERMISSION_MISSING)

    private companion object {
        const val SCAN_TIMEOUT_MS = 10_000L
        const val WIFI_PERMISSION_MISSING = "Allow location and nearby Wi-Fi devices for this phone"
    }
}
