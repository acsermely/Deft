package app.deft.bridge.modules

import android.Manifest
import android.content.Context
import app.deft.DeftApp
import app.deft.bridge.OsPermissions
import app.deft.bridge.SpecialAsk
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.LocationSpec
import app.deft.bridge.jsonArg
import app.deft.catalog.Capability
import app.deft.catalog.isGranted
import app.deft.location.BackgroundLocation
import app.deft.location.Geofences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `location.*` (PARITY G5, capability `location.background`, special): location while the app is
 * not on screen, through Android's own LocationManager (no Google services). Foreground location
 * stays the standard `navigator.geolocation`. Every method also needs the `location` grant.
 *
 * - `watchBackground` works only while the app's `fgs.start {type: "location"}` service runs.
 * - Geofences are proximity alerts; they survive a reboot and wake the app with trigger `geofence`.
 */
class LocationModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = withContext(Dispatchers.IO) {
        val repo = DeftApp.instance.repository
        if (!repo.grantLevel(request.webAppId, Capability.LOCATION).isGranted()) {
            // The plain location grant is asked at the point of use (the consent screen, this capability only).
            val ask = SpecialAsk.ensure(
                context, repo, request.webAppId, Capability.LOCATION, request.background, needSystem = false,
                code = BridgeErrors.NOT_GRANTED, message = "location.* also needs the location grant",
            )
            if (ask is SpecialAsk.Outcome.Refused) return@withContext ask.err
        }
        when (request.method) {
            "location.watchBackground" -> watch(request)
            "location.watchStop" -> {
                val id = jsonArg(request.args, "watchId")
                    ?: return@withContext BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "watchId required")
                if (BackgroundLocation.stop(context, request.webAppId, id)) BridgeOutcome.Ok()
                else BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "no such watch")
            }
            "location.geofenceAdd" -> geofenceAdd(request)
            "location.geofenceRemove" -> {
                val id = jsonArg(request.args, "id")
                    ?: return@withContext BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "id required")
                val removed = Geofences.remove(context, request.webAppId, id)
                BackgroundLocation.syncUse(context, request.webAppId)
                if (removed) BridgeOutcome.Ok() else BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "no such geofence")
            }
            "location.geofenceList" ->
                BridgeOutcome.Ok(JsonArray(Geofences.list(context, request.webAppId).map { LocationSpec.geofenceJson(it) }))
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private suspend fun watch(request: BridgeRequest): BridgeOutcome {
        val spec = when (val p = LocationSpec.parseWatch(request.args)) {
            is LocationSpec.Parsed.Ok -> p.value
            is LocationSpec.Parsed.Bad -> return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, p.message)
        }
        if (!BackgroundLocation.fgsLocationActive(request.webAppId)) {
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BackgroundLocation.NO_FGS)
        }
        if (BackgroundLocation.count(request.webAppId) >= LocationSpec.MAX_WATCHES) {
            return BridgeOutcome.Err(BridgeErrors.IN_USE, "at most ${LocationSpec.MAX_WATCHES} watches; stop one first")
        }
        OsPermissions.ensure(context, request.webAppId, request.background, listOf(Manifest.permission.ACCESS_COARSE_LOCATION), BackgroundLocation.NO_PERMISSION)
            ?.let { return it }
        return when (val r = BackgroundLocation.start(context, request.webAppId, spec)) {
            is BackgroundLocation.StartResult.Started -> {
                BackgroundLocation.syncUse(context, request.webAppId)
                BridgeOutcome.Ok(buildJsonObject { put("watchId", JsonPrimitive(r.watchId)) })
            }
            is BackgroundLocation.StartResult.Failed -> {
                val code = when (r.message) {
                    BackgroundLocation.NO_PERMISSION -> BridgeErrors.OS_PERMISSION_MISSING
                    else -> BridgeErrors.UNSUPPORTED
                }
                BridgeOutcome.Err(code, r.message)
            }
        }
    }

    private suspend fun geofenceAdd(request: BridgeRequest): BridgeOutcome {
        val fence = when (val p = LocationSpec.parseGeofence(request.args, System.currentTimeMillis())) {
            is LocationSpec.Parsed.Ok -> p.value
            is LocationSpec.Parsed.Bad -> return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, p.message)
        }
        OsPermissions.ensure(
            context,
            request.webAppId, request.background,
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            "Android has not given Deft the precise location permission",
        )?.let { return it }
        // Without "Allow all the time" Android checks geofences only while the launcher is in front.
        if (!Geofences.hasBackgroundLocation(context)) {
            return BridgeOutcome.Err(
                BridgeErrors.OS_PERMISSION_MISSING,
                "Set Deft's location to \"Allow all the time\" from the app's card on the dashboard",
            )
        }
        return when (val r = Geofences.add(context, request.webAppId, fence)) {
            Geofences.AddResult.Added -> {
                BackgroundLocation.syncUse(context, request.webAppId)
                BridgeOutcome.Ok()
            }
            Geofences.AddResult.TooMany ->
                BridgeOutcome.Err(BridgeErrors.IN_USE, "at most ${LocationSpec.MAX_GEOFENCES} geofences; remove one first")
            is Geofences.AddResult.Failed -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, r.message)
        }
    }
}
