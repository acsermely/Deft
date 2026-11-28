package app.deft.bridge.modules

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.Base64
import app.deft.bridge.AppsSpec
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.HostSurfaces
import app.deft.bridge.jsonArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

// DEVICE-UNCHECKED (app list, launch, usage stats and per-app device id): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a screen-time sample shows per-app usage.
// Remove this note once checked.
/**
 * `apps.*` (SPECIAL `apps.list`): the launchable apps on this phone (`apps.list`), an
 * app's icon as base64 PNG (`apps.icon`), and opening another app (`apps.launch`, which refuses
 * the launcher itself). All of them need the app on screen.
 */
class AppsModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        return when (request.method) {
            "apps.list" -> list()
            "apps.icon" -> icon(request)
            "apps.launch" -> launch(request)
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private fun launcherApps(): LauncherApps? = context.getSystemService(LauncherApps::class.java)

    private suspend fun list(): BridgeOutcome = withContext(Dispatchers.IO) {
        val launcher = launcherApps()
            ?: return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Apps are not available on this phone")
        val infos = launcher.getActivityList(null, Process.myUserHandle())
            .filter { it.applicationInfo.packageName != context.packageName }
            .distinctBy { it.applicationInfo.packageName }
            .sortedBy { it.label.toString().lowercase() }
        BridgeOutcome.Ok(
            buildJsonArray {
                for (info in infos) {
                    add(
                        buildJsonObject {
                            put("package", info.applicationInfo.packageName)
                            put("label", info.label.toString())
                            put("system", (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                        },
                    )
                }
            },
        )
    }

    private suspend fun icon(request: BridgeRequest): BridgeOutcome = withContext(Dispatchers.IO) {
        val pkg = packageArg(request)
            ?: return@withContext BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "package must be an Android package name")
        val launcher = launcherApps()
            ?: return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Apps are not available on this phone")
        val info = launcher.getActivityList(pkg, Process.myUserHandle()).firstOrNull()
            ?: return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "No app with that package on this phone")
        val drawable = info.getIcon(context.resources.displayMetrics.densityDpi)
        BridgeOutcome.Ok(JsonPrimitive(drawable.toPngBase64()))
    }

    private suspend fun launch(request: BridgeRequest): BridgeOutcome = withContext(Dispatchers.Main) {
        val pkg = packageArg(request)
            ?: return@withContext BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "package must be an Android package name")
        if (pkg == context.packageName) {
            return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "That is this launcher")
        }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "That app cannot be opened")
        return@withContext try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            BridgeOutcome.Ok()
        } catch (_: ActivityNotFoundException) {
            BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "That app cannot be opened")
        } catch (_: SecurityException) {
            BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "That app cannot be opened")
        }
    }

    private fun packageArg(request: BridgeRequest): String? =
        jsonArg(request.args, "package")?.takeIf { AppsSpec.validPackage(it) }

    private fun Drawable.toPngBase64(): String {
        val size = AppsSpec.ICON_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            setBounds(0, 0, size, size)
            draw(Canvas(bitmap))
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }
}
