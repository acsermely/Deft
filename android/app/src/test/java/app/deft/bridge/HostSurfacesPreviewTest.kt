package app.deft.bridge

import app.deft.bridge.modules.DisplayModule
import app.deft.bridge.modules.PipModule
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSurfacesPreviewTest {
    private val app = "preview-test-app"
    private val host = FakeSurface(preview = false)
    private val preview = FakeSurface(preview = true)

    @After
    fun cleanup() {
        HostSurfaces.unregister(app, host)
        HostSurfaces.unregister(app, preview)
    }

    @Test
    fun previewTakesAFreeSlotAndLeavesIt() {
        assertTrue(HostSurfaces.registerIfFree(app, preview))
        assertSame(preview, HostSurfaces.forApp(app))
        assertTrue(HostSurfaces.unregister(app, preview))
        assertNull(HostSurfaces.forApp(app))
        assertFalse(HostSurfaces.unregister(app, preview))
    }

    @Test
    fun previewYieldsToTheOpenApp() {
        HostSurfaces.register(app, host)
        assertFalse(HostSurfaces.registerIfFree(app, preview))
        assertSame(host, HostSurfaces.forApp(app))
        // Preview leaving does not take the open app's slot away.
        assertFalse(HostSurfaces.unregister(app, preview))
        assertSame(host, HostSurfaces.forApp(app))
    }

    @Test
    fun openAppReplacesPreview() {
        assertTrue(HostSurfaces.registerIfFree(app, preview))
        HostSurfaces.register(app, host)
        assertSame(host, HostSurfaces.forApp(app))
        assertFalse(HostSurfaces.unregister(app, preview))
        assertSame(host, HostSurfaces.forApp(app))
        assertTrue(HostSurfaces.unregister(app, host))
        assertNull(HostSurfaces.forApp(app))
    }

    @Test
    fun registerIfFreeAgainKeepsTheSlot() {
        assertTrue(HostSurfaces.registerIfFree(app, preview))
        assertTrue(HostSurfaces.registerIfFree(app, preview))
        assertSame(preview, HostSurfaces.forApp(app))
    }

    @Test
    fun windowWideControlsAreRefusedInPreview() = runBlocking {
        HostSurfaces.registerIfFree(app, preview)
        for (method in listOf("display.setOrientation", "display.secure", "display.hideOverlays")) {
            val out = DisplayModule().invoke(request(method, "orientation" to "portrait", "on" to "true"))
            assertEquals(method, BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_IN_PREVIEW), out)
        }
        val pip = PipModule().invoke(request("pip.enter", "aspect" to "16:9"))
        assertEquals(BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_IN_PREVIEW), pip)
        assertTrue(DisplayModule.worksInPreview("display.keepScreenOn"))
        assertTrue(DisplayModule.worksInPreview("display.print"))
    }

    @Test
    fun backgroundRunsIgnorePreviewButNotTheOpenApp() = runBlocking {
        HostSurfaces.registerIfFree(app, preview)
        assertSame(preview, HostSurfaces.forRequest(request("display.keepScreenOn")))
        assertNull(HostSurfaces.forRequest(request("display.keepScreenOn").copy(background = true)))
        val hidden = DisplayModule().invoke(request("display.keepScreenOn", "on" to "true").copy(background = true))
        assertEquals(BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN), hidden)
        // The open app's full-screen host counts for a background run, as before Preview existed.
        HostSurfaces.register(app, host)
        assertSame(host, HostSurfaces.forRequest(request("display.keepScreenOn").copy(background = true)))
        assertSame(host, HostSurfaces.visibleTo(host, background = true))
        assertNull(HostSurfaces.visibleTo(preview, background = true))
        assertSame(preview, HostSurfaces.visibleTo(preview, background = false))
        assertNull(HostSurfaces.visibleTo(null, background = false))
    }

    @Test
    fun notOnScreenNamesPreview() = runBlocking {
        val out = DisplayModule().invoke(request("display.keepScreenOn", "on" to "true"))
        assertEquals(BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN), out)
        assertTrue(BridgeErrors.NOT_ON_SCREEN.contains("Preview"))
    }

    private fun request(method: String, vararg args: Pair<String, String>) = BridgeRequest(
        webAppId = app,
        origin = "https://preview.test",
        method = method,
        args = buildJsonObject { args.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
    )
}

class SystemPermissionAskTest {
    private val perms = listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")

    @Test
    fun grantedWhenAllAreHeld() = runBlocking {
        val ask = SystemPermissionAsk()
        var launched: List<String>? = null
        val answer = async(start = CoroutineStart.UNDISPATCHED) { ask.request(perms) { launched = it.toList() } }
        assertEquals(perms, launched)
        ask.onResult(isGranted = { true }, showsRationale = { false })
        assertEquals(RuntimePermissions.Answer.GRANTED, answer.await())
    }

    @Test
    fun refusalIsDeniedOrBlocked() = runBlocking {
        val ask = SystemPermissionAsk()
        val denied = async(start = CoroutineStart.UNDISPATCHED) { ask.request(perms) { } }
        ask.onResult(isGranted = { it.endsWith("CAMERA") }, showsRationale = { true })
        assertEquals(RuntimePermissions.Answer.DENIED, denied.await())
        val blocked = async(start = CoroutineStart.UNDISPATCHED) { ask.request(perms) { } }
        ask.onResult(isGranted = { false }, showsRationale = { false })
        assertEquals(RuntimePermissions.Answer.BLOCKED, blocked.await())
    }

    @Test
    fun oneRequestAtATime() = runBlocking {
        val first = SystemPermissionAsk()
        val other = SystemPermissionAsk()
        val open = async(start = CoroutineStart.UNDISPATCHED) { first.request(perms) { } }
        assertNull(other.request(perms) { error("must not launch") })
        first.cancel()
        assertEquals(RuntimePermissions.Answer.DENIED, open.await())
    }

    @Test
    fun launchFailureAndTimeoutAreDenied() = runBlocking {
        assertEquals(RuntimePermissions.Answer.DENIED, SystemPermissionAsk().request(perms) { error("no activity") })
        assertEquals(RuntimePermissions.Answer.DENIED, SystemPermissionAsk(timeoutMs = 20).request(perms) { })
    }
}

private class FakeSurface(preview: Boolean) : HostSurface {
    override val isPreview = preview
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
