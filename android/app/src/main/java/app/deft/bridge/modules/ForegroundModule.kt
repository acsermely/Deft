package app.deft.bridge.modules

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.deft.DeftApp
import app.deft.background.AppForegroundService
import app.deft.background.FgsType
import app.deft.bridge.OsPermissions
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.HostSurfaces
import app.deft.bridge.jsonArg

// DEVICE-UNCHECKED (typed foreground services): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a workout sample records a GPS track with the screen off, notification visible.
// Remove this note once checked.
/**
 * `fgs.*`: keep the app's page running with a notification after the user leaves it.
 * `fgs.start` works only while the app is on screen (and never from a background run, see
 * `BackgroundPolicy.UI_METHODS`): Android 14 starts location and microphone services only from the
 * foreground, and the notification must belong to something the user just used.
 */
class ForegroundModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = when (request.method) {
        "fgs.start" -> start(request)
        "fgs.update" -> {
            val title = jsonArg(request.args, "title")?.trim()?.take(FgsType.TITLE_MAX)?.takeIf { it.isNotEmpty() }
            val text = jsonArg(request.args, "text")?.trim()?.take(FgsType.TEXT_MAX)
            if (AppForegroundService.update(context, request.webAppId, title, text)) BridgeOutcome.Ok()
            else BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "call fgs.start first")
        }
        "fgs.stop" -> {
            AppForegroundService.stop(context, request.webAppId, null)
            BridgeOutcome.Ok()
        }
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
    }

    private suspend fun start(request: BridgeRequest): BridgeOutcome {
        val type = FgsType.fromId(jsonArg(request.args, "type"))
            ?: return BridgeOutcome.Err(
                BridgeErrors.INVALID_ARGS,
                "type must be one of ${FgsType.entries.joinToString { it.id }}",
            )
        val title = jsonArg(request.args, "title")?.trim()?.take(FgsType.TITLE_MAX)
        if (title.isNullOrEmpty()) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "title required")
        val text = jsonArg(request.args, "text")?.trim()?.take(FgsType.TEXT_MAX)?.takeIf { it.isNotEmpty() }
        val surface = HostSurfaces.forRequest(request)
        if (request.background || surface == null) {
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        }
        // The service keeps the open app's page running; the Preview's page ends with the panel.
        if (surface.isPreview) return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_IN_PREVIEW)
        val repo = DeftApp.instance.repository
        val granted = repo.grantedCapabilities(request.webAppId).toSet()
        if (!type.allowedWith(granted)) {
            return BridgeOutcome.Err(
                BridgeErrors.NOT_GRANTED,
                "type ${type.id} also needs ${type.anyOf.joinToString(" or ") { it.id }}",
            )
        }
        if (type.osPermissions.isNotEmpty() && type.osPermissions.none { granted(it) }) {
            OsPermissions.ensure(
                context,
                request.webAppId, request.background,
                listOf(permissionToAsk(type)),
                "Android has not given Deft this permission",
            )?.let { return it }
        }
        // The notification is the point: no notification, no running in the background.
        if (Build.VERSION.SDK_INT >= 33) {
            OsPermissions.ensure(
                context,
                request.webAppId, request.background,
                listOf(Manifest.permission.POST_NOTIFICATIONS),
                "Turn on notifications for Deft first",
            )?.let { return it }
        }
        if (!AppForegroundService.notificationsShown(context)) {
            return BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Turn on notifications for Deft first")
        }
        val app = repo.getApp(request.webAppId)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Unknown app")
        val error = AppForegroundService.start(
            context,
            AppForegroundService.Session(request.webAppId, app.name, type, title, text),
        )
        return if (error == null) BridgeOutcome.Ok(metadata = """{"type":"${type.id}"}""")
        else BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, error)
    }

    /** The one permission to ask for when the type needs any of several: location asks coarse (approximate is enough). */
    private fun permissionToAsk(type: FgsType): String =
        if (type == FgsType.LOCATION) Manifest.permission.ACCESS_COARSE_LOCATION else type.osPermissions.first()

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
