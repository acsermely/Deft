package app.deft.bridge.modules

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import app.deft.bridge.AudioSpec
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.HostSurfaces
import app.deft.bridge.jsonArg
import app.deft.special.SystemAccess
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** `audio.*` (PARITY F5a): volumes, ringer mode and output routing, only while the app is on screen. */
class AudioModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Audio is not available")
        return when (request.method) {
            "audio.getVolumes" -> getVolumes(audio)
            "audio.setVolume" -> setVolume(audio, request)
            "audio.getRingerMode" -> BridgeOutcome.Ok(buildJsonObject { put("mode", JsonPrimitive(ringerName(audio.ringerMode))) })
            "audio.setRingerMode" -> setRingerMode(audio, request)
            "audio.devices" -> devices(audio)
            "audio.route" -> route(audio, request)
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private fun getVolumes(audio: AudioManager): BridgeOutcome {
        val out = buildJsonObject {
            for (name in AudioSpec.STREAMS) {
                put(
                    name,
                    buildJsonObject {
                        put("value", JsonPrimitive(audio.getStreamVolume(streamOf(name))))
                        put("max", JsonPrimitive(audio.getStreamMaxVolume(streamOf(name))))
                    },
                )
            }
        }
        return BridgeOutcome.Ok(out)
    }

    private fun setVolume(audio: AudioManager, request: BridgeRequest): BridgeOutcome {
        val name = jsonArg(request.args, "stream")
        if (name == null || name !in AudioSpec.STREAMS) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "stream must be one of ${AudioSpec.STREAMS.joinToString()}")
        }
        val value = AudioSpec.parseValue(jsonArg(request.args, "value"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "value must be a whole number")
        val stream = streamOf(name)
        val max = audio.getStreamMaxVolume(stream)
        val clamped = AudioSpec.clamp(value, max)
        try {
            audio.setStreamVolume(stream, clamped, AudioManager.FLAG_SHOW_UI)
        } catch (_: SecurityException) {
            // Review: turning the ring down to silent is a Do Not Disturb change.
            return BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Do Not Disturb access")
        }
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("value", JsonPrimitive(audio.getStreamVolume(stream)))
                put("max", JsonPrimitive(max))
            },
        )
    }

    private fun setRingerMode(audio: AudioManager, request: BridgeRequest): BridgeOutcome {
        val name = jsonArg(request.args, "mode")
        if (name == null || name !in AudioSpec.RINGER_MODES) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "mode must be normal, vibrate or silent")
        }
        val mode = when (name) {
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        // Silent mode is a Do Not Disturb change on Android 7 and newer, so it needs that access.
        if (mode == AudioManager.RINGER_MODE_SILENT && !SystemAccess.DND_ACCESS.isGranted(context)) {
            return BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Do Not Disturb access")
        }
        return try {
            audio.ringerMode = mode
            BridgeOutcome.Ok(buildJsonObject { put("mode", JsonPrimitive(ringerName(audio.ringerMode))) })
        } catch (_: SecurityException) {
            BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Do Not Disturb access")
        }
    }

    private fun devices(audio: AudioManager): BridgeOutcome {
        val list = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return BridgeOutcome.Ok(
            buildJsonArray {
                for (device in list) {
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(device.id.toString()))
                            put("type", JsonPrimitive(typeName(device.type)))
                            put("name", JsonPrimitive(device.productName?.toString().orEmpty()))
                        },
                    )
                }
            },
        )
    }

    private fun route(audio: AudioManager, request: BridgeRequest): BridgeOutcome {
        val target = jsonArg(request.args, "target")
        if (target == null || target !in AudioSpec.ROUTE_TARGETS) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "target must be speaker, earpiece, bluetooth or default")
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (target == "default") {
                audio.clearCommunicationDevice()
                AudioRoute.setOwner(null)
            } else {
                val wanted = when (target) {
                    "speaker" -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    "earpiece" -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    else -> null
                }
                val device = audio.availableCommunicationDevices.firstOrNull {
                    if (target == "bluetooth") it.type in BLUETOOTH
                    else it.type == wanted
                } ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "No $target output is connected")
                if (!audio.setCommunicationDevice(device)) {
                    return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The $target output is not available right now")
                }
                AudioRoute.setOwner(request.webAppId)
            }
        } else {
            // Older Android: only the speaker and earpiece can be chosen, through speakerphone.
            when (target) {
                "speaker" -> {
                    audio.isSpeakerphoneOn = true
                    AudioRoute.setOwner(request.webAppId)
                }
                "earpiece", "default" -> {
                    audio.isSpeakerphoneOn = false
                    AudioRoute.setOwner(null)
                }
                else -> return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Bluetooth routing needs a newer Android")
            }
        }
        return BridgeOutcome.Ok(buildJsonObject { put("target", JsonPrimitive(target)) })
    }

    private fun streamOf(name: String): Int = when (name) {
        "music" -> AudioManager.STREAM_MUSIC
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        else -> AudioManager.STREAM_VOICE_CALL
    }

    private fun ringerName(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_SILENT -> "silent"
        else -> "normal"
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "bluetooth"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
        else -> "other"
    }
}

private val BLUETOOTH = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
)

/**
 * Review: `audio.route` changes the output for the whole phone, so the app that chose it gives it
 * back when it leaves the screen or loses `audio.control`.
 */
object AudioRoute {
    @Volatile
    private var owner: String? = null

    fun setOwner(webAppId: String?) {
        owner = webAppId
    }

    fun resetFor(webAppId: String, context: Context) {
        if (owner != webAppId) return
        owner = null
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice() else audio.isSpeakerphoneOn = false
        }
    }
}
