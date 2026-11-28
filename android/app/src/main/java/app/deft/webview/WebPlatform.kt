package app.deft.webview

import android.Manifest
import android.content.pm.ActivityInfo
import android.webkit.PermissionRequest
import app.deft.catalog.Capability

/** Mappings from web platform requests to launcher grants and Android settings . */
object WebPlatform {
    /** The Android runtime permission a media resource needs, with the message to show if it is refused; null when none. */
    fun osPermissionFor(resource: String): Pair<String, String>? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA to "Allow the camera for Deft"
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO to "Allow the microphone"
        else -> null
    }

    /** The grant a WebView permission resource needs, or null if the host never grants it. */
    fun capabilityFor(resource: String): Capability? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Capability.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Capability.MICROPHONE
        PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> Capability.MEDIA_DRM
        PermissionRequest.RESOURCE_MIDI_SYSEX -> Capability.MIDI
        else -> null
    }

    /** Screen Orientation API values to `requestedOrientation`; null means "not a valid value". */
    fun orientationFor(value: String): Int? = when (value.trim().lowercase()) {
        "", "any", "unlock" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        "natural" -> ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> null
    }

    /** Whether an `accept` list asks for something a camera can produce. */
    fun captureKind(accept: Array<String>?): String? {
        val types = accept.orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return when {
            types.isEmpty() || types.any { it.startsWith("image/") || it in setOf(".jpg", ".jpeg", ".png", ".heic") } -> "image"
            types.any { it.startsWith("video/") || it in setOf(".mp4", ".webm", ".3gp") } -> "video"
            else -> null
        }
    }
}
