package app.deft.ui.agent

import app.deft.bridge.HostSurface
import app.deft.bridge.PipRatio
import app.deft.bridge.RuntimePermissions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewHostSurfaceTest {
    @Test
    fun leavingStopsWhatPreviewHeld() {
        assertTrue(PreviewHostSurface.stopsOnLeave(held = true, holder = null, keptRunning = false))
    }

    @Test
    fun neverHeldStopsNothing() {
        assertFalse(PreviewHostSurface.stopsOnLeave(held = false, holder = null, keptRunning = false))
    }

    @Test
    fun openAppOnScreenKeepsItsWork() {
        assertFalse(PreviewHostSurface.stopsOnLeave(held = true, holder = OpenApp, keptRunning = false))
    }

    @Test
    fun openAppWithForegroundServiceKeepsItsWork() {
        // PARITY D3: the full-screen app is off screen but kept running; its host stops the work when the service ends.
        assertFalse(PreviewHostSurface.stopsOnLeave(held = true, holder = null, keptRunning = true))
    }

    private object OpenApp : HostSurface {
        override fun keepScreenOn(on: Boolean) = Unit
        override fun setOrientation(orientation: String) = true
        override fun print(title: String?) = true
        override fun setSecure(on: Boolean) = Unit
        override fun hideOverlays(on: Boolean) = true
        override fun enterPip(ratio: PipRatio) = true
        override fun setAutoPip(on: Boolean, ratio: PipRatio) = true
        override fun startNfcScan(onTag: (android.nfc.Tag) -> Unit) = true
        override fun stopNfcScan() = Unit
        override suspend fun requestPermissions(permissions: List<String>): RuntimePermissions.Answer? = null
        override fun openSpecialSheet(intent: android.content.Intent) = true
    }
}
