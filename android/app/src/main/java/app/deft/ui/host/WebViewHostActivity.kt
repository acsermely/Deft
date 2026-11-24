package app.deft.ui.host

import app.deft.agent.ToolRunner
import app.deft.bridge.GrantedPrefixes
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import java.io.File
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebViewClient
import android.os.SystemClock
import app.deft.webview.RenderGone
import android.widget.TextView
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.deft.BuildConfig
import app.deft.DeftApp
import app.deft.ui.theme.applyDeftChrome
import app.deft.ui.theme.applyDeftTheme
import app.deft.bridge.BridgeService
import app.deft.bridge.DeftJsBridge
import app.deft.bridge.UserGesture
import app.deft.bridge.OriginNotifications
import android.view.View
import app.deft.webview.WebPlatform
import app.deft.bridge.HostSurfaces
import app.deft.background.AppForegroundService
import app.deft.bridge.modules.AuthModule
import app.deft.bridge.HostSurface
import app.deft.bridge.BridgeErrors
import app.deft.bridge.OsPermissions
import app.deft.bridge.SpecialAsk
import app.deft.bridge.RequestSlot
import app.deft.bridge.RuntimePermissions
import app.deft.bridge.SystemPermissionAsk
import app.deft.catalog.Capability
import app.deft.data.AppRepository
import app.deft.data.InstalledWebApp
import app.deft.data.originMatches
import app.deft.data.canonicalOrigin
import app.deft.webview.DeftNavigation
import app.deft.webview.DeftWebViewConfig
import app.deft.webview.CspPolicy
import app.deft.webview.EmbedScriptPolicy
import app.deft.webview.LocalOriginPathHandler
import app.deft.webview.MyAppDebugLog
import app.deft.webview.WebViewFindings
import app.deft.webview.WebViewHostRuntime
import app.deft.workspace.OverlayStore
import app.deft.workspace.BundledApps
import app.deft.workspace.DepStore
import app.deft.ui.consent.ConsentActivity
import app.deft.workspace.AppDeps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebViewHostActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var app: InstalledWebApp? = null
    private var service: BridgeService? = null
    private var jsBridge: DeftJsBridge? = null
    private var grantedPrefixes: Set<String> = emptySet()

    /** Review: the Payment Request setting the page was set up with; null before setup. */
    private var paymentsApplied: Boolean? = null
    @Volatile private var localAssets: WebViewAssetLoader? = null

    /** PARITY G1: the CSP inputs [localAssets] serves, and how to rebuild it when they change. */
    private var servedPolicy: CspPolicy? = null
    private var assetsFor: ((CspPolicy) -> WebViewAssetLoader)? = null
    private var connectivity: ConnectivityManager? = null
    private var originIndex: Map<String, String> = emptyMap()
    private var pendingUrl: String? = null
    private var documentStartScript: ScriptHandler? = null
    private var pendingFileCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private var pendingCaptureUri: android.net.Uri? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    /** PARITY D5: the shape to use when the app leaves the screen; null when auto mode is off. */
    private var autoPipRatio: app.deft.bridge.PipRatio? = null

    /** PARITY A1: `<input type="file">`, including `capture` via the system camera app. */
    private val fileChooser = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFileCallback ?: return@registerForActivityResult
        pendingFileCallback = null
        val capture = pendingCaptureUri
        pendingCaptureUri = null
        val uris: Array<android.net.Uri>? = when {
            result.resultCode != RESULT_OK -> null
            capture != null -> arrayOf(capture)
            else -> {
                val data = result.data
                val clip = data?.clipData
                if (clip != null && clip.itemCount > 0) {
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                } else {
                    WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
                }
            }
        }
        callback.onReceiveValue(uris)
    }

    private fun openFileChooser(
        callback: android.webkit.ValueCallback<Array<android.net.Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback
        pendingCaptureUri = null
        val kind = if (params.isCaptureEnabled) WebPlatform.captureKind(params.acceptTypes) else null
        val cameraAllowed = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val intent = if (kind != null && cameraAllowed) {
            val dir = File(cacheDir, "capture").apply { mkdirs() }
            val out = File(dir, "capture-${System.currentTimeMillis()}.${if (kind == "video") "mp4" else "jpg"}")
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", out)
            pendingCaptureUri = uri
            Intent(if (kind == "video") android.provider.MediaStore.ACTION_VIDEO_CAPTURE else android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            params.createIntent().apply {
                if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
        }
        return runCatching { fileChooser.launch(intent); true }.getOrElse { e ->
            Log.w(TAG, "file chooser launch failed", e)
            pendingFileCallback = null
            pendingCaptureUri = null
            callback.onReceiveValue(null)
            false
        }
    }

    /** PARITY A4: element/video fullscreen from the Fullscreen API. */
    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback?) {
        if (fullscreenView != null) {
            callback?.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        (window.decorView as android.view.ViewGroup).addView(
            view,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        (window.decorView as android.view.ViewGroup).removeView(view)
        fullscreenView = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    private var nfcScan: ((android.nfc.Tag) -> Unit)? = null

    /** Reader mode only works for the activity in front; re-enabled on resume while a scan is active. */
    private fun enableNfcReader() {
        val onTag = nfcScan ?: return
        NfcReader.enable(this, onTag)
    }

    /**
     * Runtime permission requests from bridge calls and web requests: one at a time, shown only from a resumed
     * screen. Registered before STARTED, like [fileChooser]. A pending request is answered DENIED on destroy.
     */
    private val permissionAsk = SystemPermissionAsk()
    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> permissionAsk.onResult(this) }

    /** PARITY A5/A8 and the orientation half of A4, reached through [HostSurfaces]. */
    private val hostSurface = object : HostSurface {
        override suspend fun requestPermissions(permissions: List<String>): RuntimePermissions.Answer? {
            if (destroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) || isInPictureInPictureMode) {
                return null
            }
            return permissionAsk.request(permissions) { permissionLauncher.launch(it) }
        }

        override fun openSpecialSheet(intent: android.content.Intent): Boolean {
            if (destroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) || isInPictureInPictureMode) {
                return false
            }
            return runCatching { startActivity(intent) }.isSuccess
        }

        override fun keepScreenOn(on: Boolean) {
            if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun setSecure(on: Boolean) {
            if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }

        override fun hideOverlays(on: Boolean): Boolean {
            if (android.os.Build.VERSION.SDK_INT < 31) return false
            return try {
                window.setHideOverlayWindows(on)
                true
            } catch (e: SecurityException) {
                false
            }
        }

        override fun enterPip(ratio: app.deft.bridge.PipRatio): Boolean {
            if (!pipSupported()) return false
            return runCatching { enterPictureInPictureMode(pipParams(ratio, autoEnter = false)) }.getOrDefault(false)
        }

        override fun setAutoPip(on: Boolean, ratio: app.deft.bridge.PipRatio): Boolean {
            if (!pipSupported()) return false
            autoPipRatio = if (on) ratio else null
            // Android 12 and newer enter on Home by themselves; older ones use onUserLeaveHint.
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                runCatching { setPictureInPictureParams(pipParams(ratio, autoEnter = on)) }
            }
            return true
        }

        override fun setOrientation(orientation: String): Boolean {
            val value = WebPlatform.orientationFor(orientation) ?: return false
            requestedOrientation = value
            return true
        }

        override fun startNfcScan(onTag: (android.nfc.Tag) -> Unit): Boolean {
            if (!NfcReader.available(this@WebViewHostActivity)) return false
            nfcScan = onTag
            enableNfcReader()
            return true
        }

        override fun stopNfcScan() {
            nfcScan = null
            NfcReader.disable(this@WebViewHostActivity)
        }

        override fun print(title: String?): Boolean {
            val view = webView ?: return false
            val job = title?.takeIf { it.isNotBlank() }?.take(80) ?: (app?.name ?: "Page")
            val pm = getSystemService(android.print.PrintManager::class.java) ?: return false
            return runCatching { pm.print(job, view.createPrintDocumentAdapter(job), null); true }.getOrDefault(false)
        }
    }
    @Volatile private var destroyed = false
    private var networkWatched = false

    /**
     * PARITY D3: when the app's foreground service ends while it is off screen, do what onStop
     * skipped. The page goes back to "hidden" too.
     */
    private val fgsListener = AppForegroundService.Listener { webAppId, active ->
        if (active || webAppId != app?.id || destroyed) return@Listener
        (webView as? HostWebView)?.refreshVisibility()
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) leaveScreen()
    }

    private fun keepsRunning(): Boolean = app?.id?.let { AppForegroundService.isActive(it) } == true

    /**
     * PARITY D3: while the app keeps running with a foreground service, its page must not learn
     * it was hidden: Chromium stops geolocation (and throttles the page) for hidden pages, which
     * would defeat a `location` service. The real visibility is passed on once the service ends.
     */
    private inner class HostWebView(context: Context) : WebView(context) {
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(if (keepsRunning()) View.VISIBLE else visibility)
        }

        fun refreshVisibility() {
            super.onWindowVisibilityChanged(windowVisibility)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = applyNetwork()
        override fun onLost(network: Network) = applyNetwork()
        override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) = applyNetwork()
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as BridgeService.LocalBinder).service()
            attachBridge()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            jsBridge?.service = null
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_WEB_APP_ID) ?: run { finish(); return }
        queueNotifyEvent(intent)
        pendingUrl = intent.getStringExtra(EXTRA_URL)
        BridgeService.start(this)
        bindService(Intent(this, BridgeService::class.java), conn, BIND_AUTO_CREATE)
        AppForegroundService.addListener(fgsListener)
        lifecycleScope.launch {
            DeftApp.instance.configStore.config.collect { cfg ->
                applyDeftChrome(cfg)
                webView?.applyDeftTheme(cfg.colors)
            }
        }
        lifecycleScope.launch {
            DeftApp.instance.repository.observeApps().collect { rows ->
                originIndex = rows.associate { canonicalOrigin(it.origin) to it.id }
            }
        }
        lifecycleScope.launch {
            val installed = withContext(Dispatchers.IO) {
                DeftApp.instance.repository.getApp(id)
            } ?: run { finish(); return@launch }
            if (destroyed) return@launch
            if (!ensureDependencies(installed)) return@launch
            if (destroyed) return@launch
            setupWebView(installed)
        }
    }

    /**
     * Spec 10.10 and PARITY H4: missing libraries and newly declared network origins are confirmed
     * on the consent screen, which reopens this app afterwards. Returns false when this activity
     * handed over to it.
     */
    private suspend fun ensureDependencies(installed: InstalledWebApp): Boolean {
        val slug = installed.workspaceSlug
        val missing = withContext(Dispatchers.IO) { AppDeps.missing(this@WebViewHostActivity, installed.id, slug) }
        val newOrigins = withContext(Dispatchers.IO) {
            DeftApp.instance.repository.unconsentedNetwork(installed.id, slug)
        }
        // PARITY G14: newly declared embed origins are approved (or refused) on the same screen.
        val newEmbeds = withContext(Dispatchers.IO) {
            DeftApp.instance.repository.unconsentedEmbeds(installed.id, slug)
        }
        // W5: newly declared (or changed) agent tools are listed on the same screen; one this device cannot run does not ask.
        val newTools = withContext(Dispatchers.IO) {
            runCatching { DeftApp.instance.repository.unconsentedTools(installed.id, slug) }.getOrDefault(emptyList())
                .filter { !it.isolate || ToolRunner.isolateSupported() }
        }
        if (missing.isEmpty() && newOrigins.isEmpty() && newEmbeds.isEmpty() && newTools.isEmpty()) return true
        val consent = if (slug != null) {
            ConsentActivity.workspaceIntent(
                this, slug, installed.name, installed.startUrl, installed.origin, installed.manifestUrl,
                emptyList(), newCapsOnly = true,
            )
        } else {
            val bundledId = BundledApps.idFromWebAppId(installed.id) ?: return true
            ConsentActivity.bundledIntent(
                this, bundledId, installed.name, installed.startUrl, installed.origin, emptyList(), newCapsOnly = true,
            )
        }
        startActivity(consent)
        finish()
        return false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun setupWebView(installed: InstalledWebApp) {
        app = installed
        // onResume may have run before the app was known (e.g. right after a restart).
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            HostSurfaces.register(installed.id, hostSurface)
        }
        title = installed.name
        installed.workspaceSlug?.let { MyAppDebugLog.clear(it) }
        if (!DeftJsBridge.webViewSupported() || DeftWebViewConfig.isTooOld(this)) {
            setContentView(
                TextView(this).apply {
                    text = "This device's WebView is too old for Deft web apps (need Android System WebView ${DeftWebViewConfig.MIN_WEBVIEW_MAJOR}+). Update WebView and try again."
                    setPadding(48, 48, 48, 48)
                },
            )
            return
        }
        val view = HostWebView(this)
        webView = view
        WebViewHostRuntime.attach(view)
        setContentView(view)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (fullscreenView != null) {
                        exitFullscreen()
                        return
                    }
                    val wv = webView
                    if (wv == null) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                        return
                    }
                    if (wv.canGoBack()) {
                        wv.goBack()
                        return
                    }
                    wv.evaluateJavascript(
                        "(function(){try{return !!(window.__deftOnBack&&window.__deftOnBack());}catch(e){return false;}})()",
                    ) { raw ->
                        if (raw == "true") return@evaluateJavascript
                        // PARITY D3: leaving an app that keeps running keeps its page; finishing would end it.
                        // Only after the page did not handle Back.
                        if (keepsRunning()) {
                            moveTaskToBack(true)
                            return@evaluateJavascript
                        }
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        // Native taps only; page code cannot synthesize these (spec 8.4, confirm-each).
        // Genuine taps are tracked in dispatchTouchEvent, so fullscreen content counts too.
        DeftWebViewConfig.apply(
            view,
            geolocation = true,
            profileName = DeftWebViewConfig.profileName(installed.originId, installed.origin),
        )
        view.settings.mediaPlaybackRequiresUserGesture = false
        val servingDir = installed.workspaceSlug?.let { slug ->
            DeftApp.instance.myApps.projectDir(slug)
        } ?: BundledApps.idFromWebAppId(installed.id)?.let { id ->
            BundledApps.dir(this, id)
        }
        val policy = withContext(Dispatchers.IO) {
            DeftApp.instance.repository.cspPolicyFor(installed)
        }
        localAssets = servingDir?.let { dir ->
            assetsFor = { p ->
                LocalOriginPathHandler.loader(
                    dir,
                    LocalOriginPathHandler.hostOfOrigin(installed.origin),
                    policy = p,
                    overlay = OverlayStore.dir(this, installed.id),
                    depStore = DepStore.root(this),
                )
            }
            servedPolicy = policy
            assetsFor?.invoke(policy)
        }
        applyNetwork()

        val gates = withContext(Dispatchers.IO) { gatesOf(installed) }
        val prefixes = gates.granted
        if (destroyed) return
        grantedPrefixes = prefixes
        DeftWebViewConfig.applyPaymentRequest(view, "payments.request" in prefixes)
        paymentsApplied = "payments.request" in prefixes
        val bridge = DeftJsBridge(installed, prefixes, lifecycleScope).also { it.gates = gates }
        jsBridge = bridge
        bridge.attach(view)
        val debugSlug = installed.workspaceSlug
        registerDocumentStartScript(view, installed, policy.embedOrigins)

        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                localAssets?.shouldInterceptRequest(request.url)?.let { response ->
                    if (debugSlug != null) {
                        WebViewFindings.fromIntercept(
                            request.isForMainFrame,
                            request.url.toString(),
                            response.statusCode,
                        )?.let { MyAppDebugLog.append(debugSlug, it) }
                    }
                    return response
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // PARITY G14: frames of approved embeds (payment form, 3-D Secure) are governed by the CSP.
                val embedsActive = servedPolicy?.embedOrigins?.isNotEmpty() == true
                if (DeftNavigation.allowSubframe(url, request.isForMainFrame, embedsActive)) return false
                when (val decision = DeftNavigation.decide(url, installed.origin)) {
                    DeftNavigation.Decision.Stay -> return false
                    DeftNavigation.Decision.Drop -> return true
                    is DeftNavigation.Decision.Chooser -> {
                        DeftNavigation.openChooser(this@WebViewHostActivity, decision.url)
                        return true
                    }
                    is DeftNavigation.Decision.ExternalHttps -> {
                        confirmExternal(decision.url)
                        return true
                    }
                    is DeftNavigation.Decision.OtherApp -> Unit
                }
                val target = canonicalOrigin(url)
                val cachedId = originIndex[target]
                if (cachedId != null) {
                    startActivity(intent(this@WebViewHostActivity, cachedId, url))
                    return true
                }
                lifecycleScope.launch {
                    val match = withContext(Dispatchers.IO) {
                        DeftApp.instance.repository.byOrigin(target)
                    }
                    if (match != null) {
                        startActivity(intent(this@WebViewHostActivity, match.id, url))
                    }
                }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (debugSlug != null &&
                    WebViewFindings.shouldRecordLoad(request.isForMainFrame, error.description)
                ) {
                    MyAppDebugLog.append(debugSlug, WebViewFindings.load(error.description, request.url.toString()))
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (debugSlug != null &&
                    WebViewFindings.shouldRecordHttp(request.isForMainFrame, request.url.toString())
                ) {
                    MyAppDebugLog.append(
                        debugSlug,
                        WebViewFindings.http(errorResponse.statusCode, request.url.toString()),
                    )
                }
            }

            // The renderer is shared by every WebView; its death must not take the launcher down.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === webView) onHostRenderGone(view, detail)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                view.applyDeftTheme(DeftApp.instance.configStore.config.value.colors)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                attachBridge()
                view.applyDeftTheme(DeftApp.instance.configStore.config.value.colors)
                // Local and bundled apps are served entirely through LocalOriginPathHandler.
            }
        }
        val blocking = DeftWebViewConfig.blockingChrome { kind ->
            Log.w(TAG, "$kind dialog blocked for ${installed.origin}")
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
                blocking.onJsAlert(view, url, message, result)

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
                blocking.onJsConfirm(view, url, message, result)

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: android.webkit.JsPromptResult?,
            ): Boolean = blocking.onJsPrompt(view, url, message, defaultValue, result)

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                // Debug builds only: some phones hide WebView console output from logcat, so the page's logs also go to DeftConsole.
                if (BuildConfig.DEBUG && consoleMessage != null) {
                    Log.i("DeftConsole", "${installed.id} ${consoleMessage.messageLevel()} ${consoleMessage.message().orEmpty().take(500)}")
                }
                val slug = debugSlug ?: return false
                val finding = consoleMessage?.let { WebViewFindings.fromConsole(it) } ?: return false
                MyAppDebugLog.append(slug, finding)
                return false
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                return openFileChooser(filePathCallback, fileChooserParams)
            }

            override fun onShowCustomView(customView: View?, callback: CustomViewCallback?) {
                if (customView == null) return
                enterFullscreen(customView, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                lifecycleScope.launch { handleGeo(origin.orEmpty(), callback) }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                // Only the app's own page may use the camera or microphone; an embedded frame is refused without a prompt.
                if (!originMatches(request.origin.toString(), installed.origin)) {
                    lifecycleScope.launch {
                        DeftApp.instance.repository.log(installed.id, "webview", "media", request.origin.toString(), "frame-denied")
                    }
                    request.deny()
                    return
                }
                lifecycleScope.launch {
                    var granted: Array<String> = emptyArray()
                    try {
                        granted = webMediaGrants(request, installed)
                    } catch (e: Exception) {
                        Log.w(TAG, "media request failed", e)
                    } finally {
                        // Exactly one answer, whatever happened above: a grant only when every resource is ready.
                        runCatching { if (granted.isEmpty() || destroyed) request.deny() else request.grant(granted) }
                            .onFailure { Log.w(TAG, "answering the web request failed", it) }
                    }
                }
            }
        }
        val start = validExtraUrl(installed, pendingUrl) ?: installed.startUrl
        pendingUrl = null
        view.loadUrl(start)
        attachBridge()
        view.applyDeftTheme(DeftApp.instance.configStore.config.value.colors)
    }

    /**
     * Opened from a notification tap or an "open" action (PARITY C2): queue `notify.action` so the
     * page gets it as soon as it subscribes. Only for this activity's own app.
     */
    private fun queueNotifyEvent(intent: Intent?) {
        val payload = intent?.getStringExtra(OriginNotifications.EXTRA_NOTIFY_EVENT) ?: return
        val id = intent.getStringExtra(EXTRA_WEB_APP_ID) ?: return
        intent.removeExtra(OriginNotifications.EXTRA_NOTIFY_EVENT)
        lifecycleScope.launch(Dispatchers.IO) {
            val service = BridgeService.instanceOrNull()
            if (service != null) service.emit(id, "notify.action", payload)
            else DeftApp.instance.repository.enqueueEvent(id, "notify.action", payload)
        }
    }

    /**
     * Every real touch on this app's window, whatever view it lands on (page or fullscreen
     * content). Page code cannot synthesize these (spec 8.4, PARITY H1/A7).
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        app?.id?.let { id -> UserGesture.track(window.decorView, ev, id) }
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        queueNotifyEvent(intent)
        val url = intent.getStringExtra(EXTRA_URL)
        val installed = app
        val view = webView
        if (installed != null && view != null) {
            validExtraUrl(installed, url)?.let { view.loadUrl(it) }
        } else {
            pendingUrl = url
        }
    }

    override fun onStart() {
        super.onStart()
        connectivity = getSystemService(ConnectivityManager::class.java)
        // PARITY D3: still registered when the app kept running off screen.
        if (!networkWatched) {
            networkWatched = runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }.isSuccess
        }
        applyNetwork()
    }

    /** PARITY D5: the Home press before Android 12, when auto mode is on. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val ratio = autoPipRatio ?: return
        if (android.os.Build.VERSION.SDK_INT < 31) hostSurface.enterPip(ratio)
    }

    /** PARITY D5: tell the page when the small window opens or closes. */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val id = app?.id ?: return
        val payload = """{"inPip":$isInPictureInPictureMode}"""
        lifecycleScope.launch(Dispatchers.IO) {
            val service = BridgeService.instanceOrNull()
            if (service != null) service.emit(id, "pip.changed", payload)
            else DeftApp.instance.repository.enqueueEvent(id, "pip.changed", payload)
        }
    }

    private fun pipSupported(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(ratio: app.deft.bridge.PipRatio, autoEnter: Boolean): android.app.PictureInPictureParams {
        val builder = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(ratio.width, ratio.height))
        if (android.os.Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(autoEnter)
        return builder.build()
    }

    override fun onStop() {
        // PARITY D3: an app that keeps running with a foreground service keeps all of this until
        // the service ends (see fgsListener).
        if (!keepsRunning()) leaveScreen()
        super.onStop()
    }

    /** What stops when the app leaves the screen. */
    private fun leaveScreen() {
        if (networkWatched) {
            runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
            networkWatched = false
        }
        app?.id?.let { HostSurfaces.leftScreen(it, this) }
    }

    override fun onResume() {
        super.onResume()
        WebViewHostRuntime.hostResumed()
        applyNetwork()
        val installed = app ?: return
        // PARITY E3: back from a sign-in page without a redirect means the user closed it.
        AuthModule.hostResumed(installed.id)
        HostSurfaces.register(installed.id, hostSurface)
        enableNfcReader()
        refreshGrants(installed)
    }

    override fun onPause() {
        app?.id?.let { HostSurfaces.unregister(it, hostSurface) }
        // Wake locks end when the page is hidden (Screen Wake Lock spec); do not leave the flag behind.
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (nfcScan != null) NfcReader.disable(this)
        webView?.clearFocus()
        WebViewHostRuntime.hostPaused()
        currentFocus?.clearFocus()
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        super.onPause()
    }

    private fun refreshGrants(installed: InstalledWebApp) {
        lifecycleScope.launch {
            val gates = withContext(Dispatchers.IO) { gatesOf(installed) }
            if (destroyed) return@launch
            // Review: Payment Request is a WebView setting; a changed grant applies to a fresh document.
            val paymentsBefore = paymentsApplied
            val paymentsNow = "payments.request" in gates.granted
            val paymentsChanged = paymentsBefore != null && paymentsNow != paymentsBefore
            grantedPrefixes = gates.granted
            jsBridge?.applyGates(gates)
            jsBridge?.sendGrants()
            if (paymentsChanged) {
                paymentsApplied = paymentsNow
                webView?.let { DeftWebViewConfig.applyPaymentRequest(it, paymentsNow) }
            }
            val reloaded = refreshCsp(installed)
            if (!reloaded && paymentsChanged) webView?.reload()
        }
    }

    /**
     * PARITY G1: a network grant or server changed on the dashboard while the app was open.
     * A CSP belongs to a loaded document, so serve the new one and reload; a narrower policy
     * must not wait for the next launch. True when it reloaded the page.
     */
    private suspend fun refreshCsp(installed: InstalledWebApp): Boolean {
        val build = assetsFor ?: return false
        val served = servedPolicy ?: return false
        val policy = withContext(Dispatchers.IO) {
            runCatching { DeftApp.instance.repository.cspPolicyFor(installed) }.getOrNull()
        } ?: return false
        if (destroyed || policy == served) return false
        servedPolicy = policy
        localAssets = build(policy)
        // PARITY G14: the Trusted Types default policy carries the approved embed list.
        if (policy.embedOrigins != served.embedOrigins) {
            webView?.let { registerDocumentStartScript(it, installed, policy.embedOrigins) }
        }
        webView?.reload()
        return true
    }

    private suspend fun gatesOf(installed: InstalledWebApp): GrantedPrefixes.Gates =
        GrantedPrefixes.gatesForApp(DeftApp.instance.repository, installed)

    private fun applyNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val online = cm.activeNetwork != null
        val view = webView ?: return
        view.post {
            if (destroyed) return@post
            view.setNetworkAvailable(online)
        }
    }

    private fun registerDocumentStartScript(view: WebView, installed: InstalledWebApp, embedOrigins: List<String>) {
        if (destroyed) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(TAG, "DOCUMENT_START_SCRIPT unsupported; JS bridge waits for pageFinished")
            return
        }
        val js = runCatching { DeftJsBridge.clientJs(this) }.getOrElse {
            Log.e(TAG, "failed to load Deft JS bridge script", it)
            return
        }
        runCatching { documentStartScript?.remove() }
        documentStartScript = null
        val origins = setOf(installed.origin)
        val withHook = true
        // PARITY G14: after the hook, so `fl` is created first; empty without approved embeds.
        val embedPolicy = EmbedScriptPolicy.js(embedOrigins)
        val source = (if (withHook) js + "\n" + WebViewFindings.HOOK_JS else js) + embedPolicy
        val injected = injectDocumentStart(view, source, origins)
        if (injected != null) {
            documentStartScript = injected
            return
        }
        if (withHook) {
            Log.e(TAG, "document-start inject failed with debug hook; retrying bridge-only")
            documentStartScript = injectDocumentStart(view, js + embedPolicy, origins)
        }
        if (documentStartScript == null) {
            Log.e(TAG, "document-start inject failed for JS bridge")
        }
    }

    private fun injectDocumentStart(view: WebView, source: String, origins: Set<String>): ScriptHandler? =
        runCatching { WebViewCompat.addDocumentStartJavaScript(view, source, origins) }
            .onFailure { Log.e(TAG, "addDocumentStartJavaScript failed", it) }
            .getOrNull()

    /**
     * The resources of one web request that may be granted: the launcher grant and, for camera or microphone,
     * Android's permission (one combined ask, on screen after a tap). Empty when any resource is refused.
     */
    private suspend fun webMediaGrants(request: PermissionRequest, installed: InstalledWebApp): Array<String> {
        val repo = DeftApp.instance.repository
        val plain = mutableListOf<Pair<String, Capability>>()
        for (r in request.resources) {
            val cap = WebPlatform.capabilityFor(r)
            if (cap == null) {
                repo.log(installed.id, "webview", "media", r, "ignored")
                continue
            }
            plain += r to cap
        }
        // One consent screen for every never-asked capability of this request (camera and microphone together).
        // A denied or refused one refuses the whole request.
        if (!launcherGrantsOk(repo, installed, plain.map { it.second }.distinct())) {
            plain.forEach { (r, cap) -> repo.log(installed.id, "webview", cap.id, r, "denied") }
            return emptyArray()
        }
        val ok = plain.map { it.first }.toMutableList()
        val osNeeded = ok.mapNotNull { WebPlatform.osPermissionFor(it) }
        if (osNeeded.isNotEmpty()) {
            val perms = osNeeded.map { it.first }.distinct()
            val message = "Allow ${RuntimePermissions.friendlyNames(perms)} for Deft"
            if (OsPermissions.ensure(this, installed.id, false, perms, message) != null) {
                ok.forEach { repo.log(installed.id, "webview", WebPlatform.capabilityFor(it)?.id ?: "media", it, "os-denied") }
                return emptyArray()
            }
        }
        ok.forEach { repo.log(installed.id, "webview", WebPlatform.capabilityFor(it)?.id ?: "media", it, "ok") }
        return ok.toTypedArray()
    }

    /**
     * The launcher grants for plain capabilities. Never-asked ones (no row) open one point-of-use consent screen with
     * the same gates as a bridge call; an explicit denial and any special capability stay a plain refusal.
     */
    private suspend fun launcherGrantsOk(repo: AppRepository, installed: InstalledWebApp, caps: List<Capability>): Boolean {
        if (caps.isEmpty()) return true
        return SpecialAsk.ensureConsent(
            this, repo, installed.id, caps, background = false,
            code = BridgeErrors.NOT_GRANTED, message = "${caps.joinToString { it.id }} is revoked",
        ) !is SpecialAsk.Outcome.Refused
    }

    /** Exactly one geolocation callback, whatever happens while deciding. */
    private suspend fun handleGeo(origin: String, callback: GeolocationPermissions.Callback?) {
        var allowed = false
        try {
            allowed = geoAllowed(origin)
        } catch (e: Exception) {
            Log.w(TAG, "geolocation prompt failed", e)
        } finally {
            // Exactly one callback; a destroyed page never gets a grant.
            runCatching { callback?.invoke(origin, allowed && !destroyed, false) }
                .onFailure { Log.w(TAG, "geolocation callback failed", it) }
        }
    }

    private suspend fun geoAllowed(origin: String): Boolean {
        val repo = DeftApp.instance.repository
        val installed = app
        // The origin is checked before any ask, so a frame from another origin never opens the consent screen.
        val originOk = installed != null && originMatches(origin, installed.origin)
        val grantOk = installed != null && originOk && launcherGrantsOk(repo, installed, listOf(Capability.LOCATION))
        val held = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        // Approximate location is enough for the page; asked on screen only when the origin and the launcher grant allow it.
        val osOk = held || (
            grantOk && installed != null &&
                OsPermissions.ensure(
                    this,
                    installed.id,
                    false,
                    listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                    "Allow the location for Deft",
                ) == null
            )
        val allowed = grantOk && osOk
        if (installed != null) {
            repo.log(
                installed.id,
                "webview",
                Capability.LOCATION.id,
                "geolocation",
                if (allowed) "ok" else "denied",
            )
        }
        return allowed
    }

    private fun confirmExternal(url: String) {
        AlertDialog.Builder(this)
            .setTitle("Open link?")
            .setMessage(url)
            .setPositiveButton("Open") { _, _ -> DeftNavigation.openHttps(this, url) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun attachBridge() {
        val svc = service ?: return
        val bridge = jsBridge ?: return
        bridge.service = svc
        svc.registerSink(bridge)
    }

    /**
     * The page's renderer died. The dead view and its bridge are dropped, and the activity is
     * recreated, which builds a fresh view, bridge and page. A second death within
     * [RENDER_GONE_LOOP_MS] for the same app finishes the activity instead of looping.
     */
    private fun onHostRenderGone(view: WebView, detail: RenderProcessGoneDetail) {
        val id = app?.id
        RenderGone.log(TAG, id.orEmpty(), detail)
        webView = null
        WebViewHostRuntime.detach(view)
        runCatching { jsBridge?.detach(view) }
        runCatching { jsBridge?.let { service?.unregisterSink(it) } }
        jsBridge = null
        runCatching { documentStartScript?.remove() }
        documentStartScript = null
        RenderGone.dispose(view)
        val now = SystemClock.elapsedRealtime()
        val last = lastRenderGone
        lastRenderGone = id to now
        if (last != null && last.first == id && now - last.second < RENDER_GONE_LOOP_MS) {
            finish()
        } else {
            recreate()
        }
    }

    override fun onDestroy() {
        destroyed = true
        // A permission request still waiting on this screen is refused now, not left to its timeout.
        permissionAsk.cancel()
        AppForegroundService.removeListener(fgsListener)
        // PARITY D3: the service holds this page; closing the app (e.g. from Recents) ends it.
        // A configuration change that recreates the activity is not closing it.
        if (!isChangingConfigurations) {
            app?.id?.let { AppForegroundService.stop(this, it, AppForegroundService.REASON_CLOSED) }
        }
        if (networkWatched) {
            runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
            networkWatched = false
        }
        // PARITY F9: the secure flag stays set while paused so Recents shows a blank card; it ends with the activity.
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 31) runCatching { window.setHideOverlayWindows(false) }
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        app?.id?.let { BridgeService.instanceOrNull()?.releaseBluetooth(it) }
        jsBridge?.detach(webView)
        jsBridge?.let { service?.unregisterSink(it) }
        jsBridge = null
        runCatching { documentStartScript?.remove() }
        documentStartScript = null
        runCatching { unbindService(conn) }
        webView?.let { view ->
            WebViewHostRuntime.detach(view)
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.webChromeClient = null
            view.webViewClient = WebViewClient()
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WebViewHost"
        private const val RENDER_GONE_LOOP_MS = 10_000L

        /** App id and time of the last renderer death; survives [recreate]. */
        private var lastRenderGone: Pair<String?, Long>? = null
        const val EXTRA_WEB_APP_ID = "webAppId"
        const val EXTRA_URL = "url"

        fun validExtraUrl(installed: InstalledWebApp, url: String?): String? {
            if (url.isNullOrBlank()) return null
            return if (canonicalOrigin(url) == canonicalOrigin(installed.origin)) url else null
        }

        fun intent(context: Context, webAppId: String, url: String? = null): Intent {
            val launch = Intent(context, WebViewHostActivity::class.java).apply {
                data = Uri.parse("freedomlauncher://webapp/$webAppId")
                putExtra(EXTRA_WEB_APP_ID, webAppId)
                if (url != null) putExtra(EXTRA_URL, url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            }
            if (context !is Activity) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return launch
        }
    }
}
