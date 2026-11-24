package app.deft.bridge

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * What a bridge module may ask of the visible screen of one app (PARITY A4/A5/A8). The host
 * activity registers itself while it is in front; modules find it by web app id, so a call from
 * an app that is not on screen does nothing.
 */
interface HostSurface {
    /**
     * The agent's Preview: a panel inside the home screen. Window-wide controls (orientation, picture-in-picture,
     * secure, overlays) and a foreground service are refused there with [BridgeErrors.NOT_IN_PREVIEW].
     */
    val isPreview: Boolean get() = false

    fun keepScreenOn(on: Boolean)
    fun setOrientation(orientation: String): Boolean
    fun print(title: String?): Boolean

    /** PARITY F9: FLAG_SECURE on the app window. */
    fun setSecure(on: Boolean)

    /** PARITY F9: false when the platform cannot hide overlays (Android 12 and newer only). */
    fun hideOverlays(on: Boolean): Boolean

    /** PARITY D5: false when the device has no picture-in-picture. */
    fun enterPip(ratio: PipRatio): Boolean

    /** PARITY D5: enter picture-in-picture when the user leaves the app; false when unsupported. */
    fun setAutoPip(on: Boolean, ratio: PipRatio): Boolean

    /** PARITY B2: read tags while this app is on screen; false when NFC is off or missing. */
    fun startNfcScan(onTag: (android.nfc.Tag) -> Unit): Boolean
    fun stopNfcScan()

    /**
     * Shows Android's dialog for [permissions]. Null when the request cannot start (the screen is not resumed,
     * another request is open) or is cancelled. Main thread only.
     */
    suspend fun requestPermissions(permissions: List<String>): RuntimePermissions.Answer?

    /**
     * Starts the launcher's special-access sheet on top of this screen. False when the screen is not resumed
     * or is in picture-in-picture. The sheet's close is reported through [SpecialAsks], not here. Main thread only.
     */
    fun openSpecialSheet(intent: android.content.Intent): Boolean
}

object HostSurfaces {
    private val surfaces = ConcurrentHashMap<String, WeakReference<HostSurface>>()

    /** One request on the host screen at a time: a runtime permission dialog or the special-access sheet. */
    internal val dialogSlot = RequestSlot()

    fun register(webAppId: String, surface: HostSurface) {
        surfaces[webAppId] = WeakReference(surface)
    }

    /**
     * Registers [surface] only when no other live surface holds [webAppId]: the agent's Preview yields to the open
     * app, which replaces it with [register]. True when [surface] holds the app now.
     */
    fun registerIfFree(webAppId: String, surface: HostSurface): Boolean {
        var held = false
        surfaces.compute(webAppId) { _, ref ->
            val current = ref?.get()
            if (current == null || current === surface) {
                held = true
                WeakReference(surface)
            } else {
                ref
            }
        }
        return held
    }

    /** Removes [surface]; another surface that holds the app stays. True when [surface] was removed. */
    fun unregister(webAppId: String, surface: HostSurface): Boolean {
        var removed = false
        surfaces.computeIfPresent(webAppId) { _, ref ->
            if (ref.get() === surface) {
                removed = true
                null
            } else {
                ref
            }
        }
        return removed
    }

    fun forApp(webAppId: String): HostSurface? = surfaces[webAppId]?.get()

    /**
     * The screen [request]'s app is on, for on-screen checks. A background run (an agent tool run, a scheduled run)
     * never borrows the agent's Preview: Preview is the page in the panel, not the hidden one making the call. The
     * open app's full-screen host still counts for it, as before Preview existed.
     */
    fun forRequest(request: BridgeRequest): HostSurface? = visibleTo(forApp(request.webAppId), request.background)

    internal fun visibleTo(surface: HostSurface?, background: Boolean): HostSurface? =
        surface?.takeUnless { background && it.isPreview }

    /** What stops when an app leaves the screen: flashlight (F4), audio output, sensors (F1), listening (F3), clipboard watch (G12). */
    fun leftScreen(webAppId: String, context: android.content.Context) {
        app.deft.bridge.modules.TorchState.offFor(webAppId, context)
        app.deft.bridge.modules.AudioRoute.resetFor(webAppId, context)
        app.deft.bridge.modules.SensorsModule.stopFor(webAppId)
        app.deft.bridge.modules.SpeechModule.stopListeningFor(webAppId)
        app.deft.bridge.modules.ClipboardModule.stopFor(webAppId)
    }

    /**
     * PARITY W7: for a call that sent the user to an Android screen. Waits until the app's screen is gone (the screen
     * opened) and then back in front. True once it is back; false when it never left within [leaveWaitMs] (the
     * screen did not cover it, e.g. split screen) or did not return within [timeoutMs]. The caller re-reads the state.
     */
    suspend fun awaitReturn(webAppId: String, leaveWaitMs: Long = 3_000, timeoutMs: Long = 120_000, pollMs: Long = 150): Boolean {
        var waited = 0L
        while (forApp(webAppId) != null) {
            if (waited >= leaveWaitMs) return false
            kotlinx.coroutines.delay(pollMs)
            waited += pollMs
        }
        waited = 0L
        while (forApp(webAppId) == null) {
            if (waited >= timeoutMs) return false
            kotlinx.coroutines.delay(pollMs)
            waited += pollMs
        }
        return true
    }
}
