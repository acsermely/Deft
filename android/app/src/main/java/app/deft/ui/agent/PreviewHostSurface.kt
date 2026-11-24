package app.deft.ui.agent

import android.content.Intent
import android.nfc.Tag
import android.print.PrintManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import app.deft.background.AppForegroundService
import app.deft.bridge.HostSurface
import app.deft.bridge.HostSurfaces
import app.deft.bridge.PipRatio
import app.deft.bridge.RuntimePermissions
import app.deft.bridge.SystemPermissionAsk
import app.deft.bridge.modules.AuthModule
import app.deft.ui.host.NfcReader

/**
 * The agent's Preview as the app's screen ([HostSurfaces]): registered while the panel shows it and the home
 * activity is resumed, so bridge calls that need the app on screen work there. The open app wins: Preview only
 * takes a free slot, and the full-screen host replaces it. Window-wide controls are refused ([isPreview]).
 * Main thread only.
 */
internal class PreviewHostSurface(
    private val activity: ComponentActivity,
    private val webAppId: String,
    private val appName: String,
    private val web: () -> WebView?,
) : HostSurface {
    override val isPreview: Boolean get() = true

    val permissionAsk = SystemPermissionAsk()

    /** Launches Android's permission dialog; set from composition. */
    var launchPermissions: ((Array<String>) -> Unit)? = null

    private var resumed = false

    /** Held the app's slot since the last stop: what the app started on screen is stopped when it goes. */
    private var held = false
    private var nfcScan: ((Tag) -> Unit)? = null

    fun resume() {
        resumed = true
        if (HostSurfaces.registerIfFree(webAppId, this)) {
            held = true
            AuthModule.hostResumed(webAppId)
        }
        nfcScan?.let { NfcReader.enable(activity, it) }
    }

    /** Like the host's onPause: off the slot, no keep-screen-on, no reader mode. A permission dialog pauses too. */
    fun pause() {
        resumed = false
        HostSurfaces.unregister(webAppId, this)
        web()?.keepScreenOn = false
        if (nfcScan != null) NfcReader.disable(activity)
    }

    /**
     * Like the host's onStop: sensors, listening, flashlight and the like stop, unless the open app holds it now or
     * keeps running off screen with its foreground service (PARITY D3: the host stops them when the service ends).
     */
    fun stop() {
        if (stopsOnLeave(held, HostSurfaces.forApp(webAppId), AppForegroundService.isActive(webAppId))) {
            HostSurfaces.leftScreen(webAppId, activity)
        }
        held = false
    }

    /** The panel closed or the preview went away. */
    fun dispose() {
        if (resumed) pause()
        nfcScan = null
        permissionAsk.cancel()
        stop()
    }

    private fun inFront(): Boolean =
        resumed && !activity.isFinishing && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    override suspend fun requestPermissions(permissions: List<String>): RuntimePermissions.Answer? {
        val launch = launchPermissions ?: return null
        if (!inFront()) return null
        return permissionAsk.request(permissions, launch)
    }

    override fun openSpecialSheet(intent: Intent): Boolean {
        if (!inFront()) return false
        return runCatching { activity.startActivity(intent) }.isSuccess
    }

    override fun keepScreenOn(on: Boolean) {
        web()?.keepScreenOn = on && resumed
    }

    override fun print(title: String?): Boolean {
        val view = web() ?: return false
        val job = title?.takeIf { it.isNotBlank() }?.take(80) ?: appName
        val pm = activity.getSystemService(PrintManager::class.java) ?: return false
        return runCatching { pm.print(job, view.createPrintDocumentAdapter(job), null); true }.getOrDefault(false)
    }

    override fun startNfcScan(onTag: (Tag) -> Unit): Boolean {
        if (!NfcReader.available(activity)) return false
        nfcScan = onTag
        if (resumed) NfcReader.enable(activity, onTag)
        return true
    }

    override fun stopNfcScan() {
        nfcScan = null
        NfcReader.disable(activity)
    }

    // Window-wide: they would change the whole home screen. The modules refuse them first (isPreview).
    override fun setOrientation(orientation: String): Boolean = false
    override fun setSecure(on: Boolean) = Unit
    override fun hideOverlays(on: Boolean): Boolean = false
    override fun enterPip(ratio: PipRatio): Boolean = false
    override fun setAutoPip(on: Boolean, ratio: PipRatio): Boolean = false

    companion object {
        /**
         * Whether Preview leaving stops the app's on-screen work: only when it held the app and no full-screen
         * instance owns it now, on screen ([holder]) or kept running by a foreground service ([keptRunning]).
         * Preview never starts the service itself (`fgs.start` is refused there).
         */
        internal fun stopsOnLeave(held: Boolean, holder: HostSurface?, keptRunning: Boolean): Boolean =
            held && holder == null && !keptRunning
    }
}
