package app.deft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePermissionsTest {
    private val camera = "android.permission.CAMERA"
    private val mic = "android.permission.RECORD_AUDIO"

    @Test
    fun askNeedsDeclaredPermissionsAVisibleHostAndATap() {
        assertTrue(RuntimePermissions.canAsk(listOf(camera), setOf(camera), hasVisibleHost = true, recentTap = true))
        assertFalse(RuntimePermissions.canAsk(listOf(camera), setOf(camera), hasVisibleHost = false, recentTap = true))
        assertFalse(RuntimePermissions.canAsk(listOf(camera), setOf(camera), hasVisibleHost = true, recentTap = false))
    }

    @Test
    fun undeclaredPermissionIsNeverAsked() {
        assertFalse(RuntimePermissions.canAsk(listOf(camera), setOf(mic), hasVisibleHost = true, recentTap = true))
    }

    @Test
    fun nothingMissingIsNotAsked() {
        assertFalse(RuntimePermissions.canAsk(emptyList(), setOf(camera), hasVisibleHost = true, recentTap = true))
    }

    @Test
    fun everyMissingPermissionMustBeDeclared() {
        val fine = "android.permission.ACCESS_FINE_LOCATION"
        val coarse = "android.permission.ACCESS_COARSE_LOCATION"
        assertFalse(RuntimePermissions.canAsk(listOf(fine, coarse), setOf(fine), hasVisibleHost = true, recentTap = true))
    }

    @Test
    fun grantIsGranted() {
        assertEquals(RuntimePermissions.Answer.GRANTED, RuntimePermissions.answerFor(allGranted = true, rationaleStillShown = false))
    }

    @Test
    fun refusalWithRationaleLeftIsDenied() {
        assertEquals(RuntimePermissions.Answer.DENIED, RuntimePermissions.answerFor(allGranted = false, rationaleStillShown = true))
    }

    @Test
    fun refusalWithoutRationaleIsBlocked() {
        assertEquals(RuntimePermissions.Answer.BLOCKED, RuntimePermissions.answerFor(allGranted = false, rationaleStillShown = false))
    }

    @Test
    fun blockedRefusalPointsAtAppSettings() {
        assertEquals(
            "Allow the camera in Android's app settings",
            RuntimePermissions.refusalMessage("Allow the camera", RuntimePermissions.Answer.BLOCKED),
        )
        assertEquals("Allow the camera", RuntimePermissions.refusalMessage("Allow the camera", RuntimePermissions.Answer.DENIED))
    }
}
