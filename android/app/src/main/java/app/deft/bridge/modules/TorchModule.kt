package app.deft.bridge.modules

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.HostSurfaces
import app.deft.bridge.TorchLevels
import app.deft.bridge.jsonArg
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// DEVICE-UNCHECKED (torch): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a QR-scanner-style sample switches the torch on and off.
// Remove this note once checked.
/**
 * The device has one flashlight, so remember which app lit it. Leaving that app
 * (its screen goes away) turns the light off.
 */
object TorchState {
    @Volatile
    private var owner: String? = null

    /** Review: what Android last reported for the flashlight, or null before the first report. */
    @Volatile
    private var systemOn: Boolean? = null

    @Volatile
    private var watching = false

    fun isOn(): Boolean = systemOn ?: (owner != null)

    /**
     * Review: follows the flashlight through Android, so `torch.state` is right after the user
     * or another app switches it, and an app whose light was switched off no longer owns it.
     */
    fun watch(context: Context) {
        if (watching) return
        val manager = context.getSystemService(CameraManager::class.java) ?: return
        val flashId = flashCamera(context)?.id ?: return
        watching = true
        manager.registerTorchCallback(
            object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId != flashId) return
                    systemOn = enabled
                    if (!enabled) owner = null
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    if (cameraId != flashId) return
                    systemOn = false
                    owner = null
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    fun setOwner(webAppId: String?) {
        owner = webAppId
    }

    fun offFor(webAppId: String, context: Context) {
        if (owner != webAppId) return
        owner = null
        val flash = flashCamera(context) ?: return
        runCatching { context.getSystemService(CameraManager::class.java)?.setTorchMode(flash.id, false) }
    }
}

private data class Flash(val id: String, val maxLevel: Int)

/** The first back camera with a flash, or null when the device has none. */
private fun flashCamera(context: Context): Flash? {
    val manager = context.getSystemService(CameraManager::class.java) ?: return null
    return runCatching {
        manager.cameraIdList.firstNotNullOfOrNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) return@firstNotNullOfOrNull null
            if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@firstNotNullOfOrNull null
            val max = if (Build.VERSION.SDK_INT >= 33) {
                characteristics.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            } else {
                1
            }
            Flash(id, max)
        }
    }.getOrNull()
}

/** `torch.*`: the camera flashlight, only while the app is on screen. */
class TorchModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        runCatching { TorchState.watch(context) }
        return when (request.method) {
            "torch.set" -> set(request)
            "torch.state" -> {
                val flash = flashCamera(context)
                BridgeOutcome.Ok(
                    buildJsonObject {
                        put("available", JsonPrimitive(flash != null))
                        put("on", JsonPrimitive(flash != null && TorchState.isOn()))
                        put("maxLevel", JsonPrimitive(flash?.maxLevel ?: 0))
                    },
                )
            }
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private fun set(request: BridgeRequest): BridgeOutcome {
        val flash = flashCamera(context)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "This device has no flashlight")
        val on = jsonArg(request.args, "on") == "true"
        val level = if (on) {
            TorchLevels.clamp(jsonArg(request.args, "level"), flash.maxLevel)
                ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "level must be 1 to ${flash.maxLevel}")
        } else {
            0
        }
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The flashlight is not available right now")
        try {
            // Review: with adjustable strength, level 1 is the dimmest light, not the default one.
            if (on && flash.maxLevel > 1 && Build.VERSION.SDK_INT >= 33) {
                manager.turnOnTorchWithStrengthLevel(flash.id, level)
            } else {
                manager.setTorchMode(flash.id, on)
            }
        } catch (_: CameraAccessException) {
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The flashlight is not available right now")
        } catch (_: IllegalArgumentException) {
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The flashlight is not available right now")
        }
        TorchState.setOwner(if (on) request.webAppId else null)
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("on", JsonPrimitive(on))
                put("level", JsonPrimitive(level))
                put("maxLevel", JsonPrimitive(flash.maxLevel))
            },
        )
    }
}
