package app.deft.webview

import android.content.pm.ActivityInfo
import android.webkit.PermissionRequest
import app.deft.catalog.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebPlatformTest {
    @Test
    fun cameraAndMicrophoneNeedAndroidPermissions() {
        assertEquals("android.permission.CAMERA", WebPlatform.osPermissionFor(PermissionRequest.RESOURCE_VIDEO_CAPTURE)?.first)
        assertEquals("android.permission.RECORD_AUDIO", WebPlatform.osPermissionFor(PermissionRequest.RESOURCE_AUDIO_CAPTURE)?.first)
        assertNull(WebPlatform.osPermissionFor(PermissionRequest.RESOURCE_MIDI_SYSEX))
    }

    @Test
    fun permissionResourcesMapToGrants() {
        assertEquals(Capability.CAMERA, WebPlatform.capabilityFor(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        assertEquals(Capability.MICROPHONE, WebPlatform.capabilityFor(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        assertEquals(Capability.MEDIA_DRM, WebPlatform.capabilityFor(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
        assertEquals(Capability.MIDI, WebPlatform.capabilityFor(PermissionRequest.RESOURCE_MIDI_SYSEX))
        assertNull(WebPlatform.capabilityFor("android.webkit.resource.SOMETHING_NEW"))
    }

    @Test
    fun orientationValuesFollowTheScreenOrientationApi() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, WebPlatform.orientationFor("any"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, WebPlatform.orientationFor("landscape"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, WebPlatform.orientationFor("Portrait-Secondary"))
        assertNull(WebPlatform.orientationFor("sideways"))
    }

    @Test
    fun captureOnlyForCameraMedia() {
        assertEquals("image", WebPlatform.captureKind(arrayOf("image/*")))
        assertEquals("image", WebPlatform.captureKind(arrayOf("")))
        assertEquals("video", WebPlatform.captureKind(arrayOf("video/mp4")))
        assertNull(WebPlatform.captureKind(arrayOf("application/pdf", ".csv")))
    }
}
