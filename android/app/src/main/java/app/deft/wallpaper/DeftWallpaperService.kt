package app.deft.wallpaper

import android.annotation.SuppressLint
import android.app.Presentation
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import app.deft.DeftApp
import app.deft.background.BackgroundRunner
import app.deft.bridge.BridgeService
import app.deft.bridge.DeftJsBridge
import app.deft.catalog.Capability
import app.deft.data.InstalledWebApp
import app.deft.webview.CspPolicy
import app.deft.webview.EmbedScriptPolicy
import app.deft.webview.DeftWebViewConfig
import app.deft.webview.LocalOriginPathHandler
import app.deft.webview.WebViewHostRuntime
import app.deft.workspace.DepStore
import app.deft.workspace.OverlayStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PARITY G11 `wallpaper`: Deft's live wallpaper, showing the manifest `wallpaper.page`
 * of the one web app holding the `wallpaper` grant. Disabled in the manifest; [app.deft.roles.RoleComponents]
 * enables it only while an app holds the grant, so revoking it makes Android drop the wallpaper
 * and show its default one.
 *
 * Rendering: the page lives in a WebView inside a [Presentation] on a private [VirtualDisplay]
 * whose output surface is the wallpaper's own surface. The WebView is attached to a real
 * (virtual) window, so Chromium renders it with the GPU at the display's vsync straight into the
 * wallpaper, with working `requestAnimationFrame`, CSS animation and canvas, and no copy through
 * the CPU. (Drawing a detached WebView with `draw(Canvas)` is unreliable: a WebView that is not in
 * a window gets no vsync, so the page does not animate, and a software copy of every frame costs
 * far more.) `requestAnimationFrame` is capped at [WallpaperSpec.targetFps].
 *
 * While the wallpaper is hidden the virtual display has no surface (nothing is composited), the
 * view is invisible (the page sees `document.hidden`) and the WebView is paused as soon as that
 * cannot affect an app in front ([WebViewHostRuntime.wallpaperHidden]).
 *
 * The page gets a bridge with the holder's grants, flagged as background with `wallpaper` as its
 * kill switch: anything that would show a screen or a prompt returns `needs_foreground`, as in a
 * background run. Events: `wallpaper.touch {x, y, action}` and `wallpaper.offsets {xOffset, yOffset}`,
 * only while the page listens for them, at most 30 a second.
 *
 * No "in use" notification: the wallpaper itself shows the app is running.
 */
class DeftWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = PageEngine()

    private inner class PageEngine : Engine() {
        private val main = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var loadJob: Job? = null

        private var holderId: String? = null
        private var display: VirtualDisplay? = null
        private var presentation: Presentation? = null
        private var webView: WebView? = null
        private var bridge: DeftJsBridge? = null
        private var script: ScriptHandler? = null

        private var width = 0
        private var height = 0
        private var surfaceReady = false
        private var visible = false
        private var shownInRuntime = false
        private var destroyed = false

        private var lastMoveMs: Long? = null
        private var lastOffsetsMs: Long? = null
        private var offsetsQueued = false
        private var xOffset = 0.5f
        private var yOffset = 0.5f

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolder.setFormat(PixelFormat.RGBA_8888)
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceReady = true
            val resized = width != this.width || height != this.height
            this.width = width
            this.height = height
            val vd = display
            if (vd != null) {
                if (resized) vd.resize(width, height, densityDpi())
                attachSurface()
            } else if (loadJob == null) {
                paintBlank()
                load()
            }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            if (display == null) paintBlank()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            display?.surface = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            if (visible) show() else hide()
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            this.xOffset = xOffset
            this.yOffset = yOffset
            queueOffsets()
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            val action = WallpaperSpec.touchAction(event.actionMasked) ?: return
            sendTouch(event.x, event.y, action)
        }

        /** Home apps report a tap on empty home screen as a command rather than (or besides) touches. */
        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) sendTouch(x.toFloat(), y.toFloat(), "tap")
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onDestroy() {
            destroyed = true
            loadJob?.cancel()
            teardown()
            scope.cancel()
            super.onDestroy()
        }

        private fun show() {
            if (destroyed) return
            bridge?.let { if (it.service == null) it.service = BridgeService.instanceOrNull() }
            val view = webView
            if (view != null) {
                view.visibility = View.VISIBLE
                attachSurface()
                if (!shownInRuntime) {
                    shownInRuntime = true
                    WebViewHostRuntime.wallpaperShown(view)
                }
                applyFrameRate(view)
            } else if (loadJob == null && surfaceReady && width > 0) {
                load()
            }
            // The grant may have moved or gone while the wallpaper was hidden.
            recheckHolder()
            logUse()
        }

        private fun hide() {
            display?.surface = null
            val view = webView ?: return
            view.visibility = View.INVISIBLE
            if (shownInRuntime) {
                shownInRuntime = false
                WebViewHostRuntime.wallpaperHidden(view)
            }
        }

        private fun attachSurface() {
            val vd = display ?: return
            val surface = surfaceHolder.surface
            vd.surface = if (visible && surfaceReady && surface != null && surface.isValid) surface else null
        }

        private fun densityDpi(): Int = resources.displayMetrics.densityDpi

        private fun applyFrameRate(view: WebView) {
            val powerSave = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
            val gap = WallpaperSpec.frameGapMs(WallpaperSpec.targetFps(visible, powerSave))
            if (gap > 0) view.evaluateJavascript("window.__deftWallpaperFrameGap&&window.__deftWallpaperFrameGap($gap)", null)
        }

        private fun recheckHolder() {
            scope.launch {
                val now = withContext(Dispatchers.IO) { currentHolder() }
                if (destroyed || now == holderId || loadJob != null) return@launch
                teardown()
                paintBlank()
                if (now != null && surfaceReady && width > 0) load()
            }
        }

        private fun logUse() {
            val id = holderId ?: return
            val now = System.currentTimeMillis()
            if (!WallpaperSpec.shouldLogUse(lastUseLog, now)) return
            lastUseLog = now
            // The special-access review counts this as use, so a wallpaper in use is not auto-revoked.
            scope.launch(Dispatchers.IO) {
                runCatching {
                    DeftApp.instance.repository.log(id, "wallpaper", Capability.WALLPAPER.id, "wallpaper.shown", "ok")
                }
            }
        }

        private suspend fun currentHolder(): String? =
            DeftApp.instance.repository.specialHolder(Capability.WALLPAPER.exclusiveGroup!!)

        /** Reads the holder and its page off the main thread, then builds the page. */
        private fun load() {
            if (destroyed || loadJob != null) return
            loadJob = scope.launch {
                try {
                    val target = withContext(Dispatchers.IO) { resolve() }
                    if (destroyed) return@launch
                    if (target == null) {
                        holderId = null
                        paintBlank()
                        return@launch
                    }
                    if (!surfaceReady || width <= 0 || height <= 0) return@launch
                    holderId = target.app.id
                    if (!build(target)) {
                        teardown()
                        paintBlank()
                    }
                } finally {
                    loadJob = null
                }
            }
        }

        private suspend fun resolve(): Target? {
            val repo = DeftApp.instance.repository
            val id = currentHolder() ?: return null
            val app = repo.getApp(id) ?: return null
            if (Capability.WALLPAPER !in app.requiredCapabilities) return null
            val page = repo.wallpaperFor(app)?.page ?: return null
            val dir = BackgroundRunner.servingDir(app) ?: return null
            val policy = repo.cspPolicyFor(app)
            val prefixes = BackgroundRunner.grantedPrefixesOf(repo, app)
            val context = DeftApp.instance
            val assets = LocalOriginPathHandler.loader(
                dir,
                LocalOriginPathHandler.hostOfOrigin(app.origin),
                policy = policy,
                overlay = OverlayStore.dir(context, app.id),
                depStore = DepStore.root(context),
            )
            return Target(app, page, assets, policy, prefixes)
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun build(t: Target): Boolean {
            if (!DeftJsBridge.webViewSupported() || DeftWebViewConfig.isTooOld(this@DeftWallpaperService)) return false
            val dm = getSystemService(DisplayManager::class.java) ?: return false
            val vd = runCatching {
                dm.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    width,
                    height,
                    densityDpi(),
                    null,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                )
            }.onFailure { Log.w(TAG, "virtual display not created", it) }.getOrNull() ?: return false
            display = vd
            val pres = Presentation(this@DeftWallpaperService, vd.display)
            presentation = pres
            pres.window?.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            )
            val view = WebView(pres.context)
            webView = view
            view.setBackgroundColor(Color.BLACK)
            DeftWebViewConfig.apply(
                view,
                geolocation = false,
                profileName = DeftWebViewConfig.profileName(t.app.originId, t.app.origin),
            )
            DeftWebViewConfig.applyPaymentRequest(view, false)
            val sink = DeftJsBridge(t.app, t.prefixes, scope, background = true, runCapability = Capability.WALLPAPER)
            sink.service = BridgeService.instanceOrNull()
            sink.onSubscribe = { event -> if (event == WallpaperSpec.OFFSETS_EVENT) sendOffsetsNow() }
            sink.attach(view)
            bridge = sink
            val gap = WallpaperSpec.frameGapMs(WallpaperSpec.FPS)
            val js = runCatching { DeftJsBridge.clientJs(this@DeftWallpaperService) }.getOrNull() ?: return false
            script = runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    view,
                    js + EmbedScriptPolicy.js(t.policy.embedOrigins) + WallpaperSpec.frameCapJs(gap),
                    setOf(t.app.origin),
                )
            }.onFailure { Log.w(TAG, "bridge script not injected for ${t.app.id}", it) }.getOrNull() ?: return false
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    t.assets.shouldInterceptRequest(request.url)

                // The wallpaper stays on its one page.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                // A crashed page must not take the launcher down; it comes back on the next show.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    main.post {
                        if (webView === view) {
                            teardown()
                            paintBlank()
                        }
                    }
                    return true
                }
            }
            view.webChromeClient = DeftWebViewConfig.blockingChrome()
            val online = getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
            view.setNetworkAvailable(online)
            val shown = runCatching {
                pres.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                pres.show()
            }.onFailure { Log.w(TAG, "wallpaper window not shown", it) }.isSuccess
            if (!shown) return false
            view.loadUrl(t.app.origin.trimEnd('/') + t.page)
            if (visible) {
                show()
            } else {
                view.visibility = View.INVISIBLE
            }
            return true
        }

        /** Destroys the page, its window and the virtual display (idempotent). Main thread. */
        private fun teardown() {
            main.removeCallbacksAndMessages(null)
            offsetsQueued = false
            val view = webView
            webView = null
            bridge?.detach(view)
            bridge = null
            runCatching { script?.remove() }
            script = null
            if (view != null) {
                if (shownInRuntime) {
                    shownInRuntime = false
                    WebViewHostRuntime.wallpaperHidden(view)
                }
                WebViewHostRuntime.wallpaperGone(view)
                view.webChromeClient = null
                view.webViewClient = WebViewClient()
                view.stopLoading()
                view.loadUrl("about:blank")
            }
            runCatching { presentation?.dismiss() }
            presentation = null
            view?.destroy()
            display?.release()
            display = null
            holderId = null
        }

        /** Black, while there is no page (no holder, no page declared, or it failed). Only without a virtual display. */
        private fun paintBlank() {
            if (display != null || !surfaceReady) return
            runCatching {
                val canvas = surfaceHolder.lockCanvas() ?: return
                try {
                    canvas.drawColor(Color.BLACK)
                } finally {
                    surfaceHolder.unlockCanvasAndPost(canvas)
                }
            }
        }

        private fun sendTouch(x: Float, y: Float, action: String) {
            val sink = bridge ?: return
            if (!visible || !sink.hasSubscriber(WallpaperSpec.TOUCH_EVENT)) return
            val now = SystemClock.uptimeMillis()
            if (!WallpaperSpec.touchPasses(action, lastMoveMs, now)) return
            if (action == "move") lastMoveMs = now
            val density = resources.displayMetrics.density
            sink.dispatchEvent(WallpaperSpec.TOUCH_EVENT, WallpaperSpec.touchPayload(x, y, density, action))
        }

        private fun queueOffsets() {
            if (offsetsQueued) return
            val delay = WallpaperSpec.offsetsDelay(lastOffsetsMs, SystemClock.uptimeMillis())
            if (delay == 0L) {
                sendOffsetsNow()
            } else {
                offsetsQueued = true
                main.postDelayed({
                    offsetsQueued = false
                    sendOffsetsNow()
                }, delay)
            }
        }

        private fun sendOffsetsNow() {
            val sink = bridge ?: return
            if (!sink.hasSubscriber(WallpaperSpec.OFFSETS_EVENT)) return
            lastOffsetsMs = SystemClock.uptimeMillis()
            sink.dispatchEvent(WallpaperSpec.OFFSETS_EVENT, WallpaperSpec.offsetsPayload(xOffset, yOffset))
        }
    }

    private class Target(
        val app: InstalledWebApp,
        val page: String,
        val assets: WebViewAssetLoader,
        val policy: CspPolicy,
        val prefixes: Set<String>,
    )

    companion object {
        private const val TAG = "DeftWallpaper"
        private const val VIRTUAL_DISPLAY_NAME = "Deft wallpaper"

        /** Last daily use row (process memory; a new process writes one again, which is harmless). */
        @Volatile private var lastUseLog: Long? = null

        fun component(context: Context): ComponentName = ComponentName(context, DeftWallpaperService::class.java)

        /** Whether Deft's live wallpaper is the phone's (home screen) wallpaper now. */
        fun isCurrent(context: Context): Boolean = runCatching {
            WallpaperManager.getInstance(context).wallpaperInfo?.component == component(context)
        }.getOrDefault(false)
    }
}
