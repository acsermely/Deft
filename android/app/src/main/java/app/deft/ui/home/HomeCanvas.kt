package app.deft.ui.home

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.deft.DeftApp
import app.deft.bridge.GrantedPrefixes
import app.deft.config.ColorTokens
import app.deft.data.InstalledWebApp
import app.deft.ui.host.WebViewHostActivity
import app.deft.ui.workspace.WorkspaceLaunchActivity
import app.deft.ui.theme.applyDeftTheme
import app.deft.webview.MyAppDebugLog
import app.deft.webview.WebViewFindings
import app.deft.webview.WebViewHostRuntime
import java.io.ByteArrayInputStream
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.hypot

@OptIn(FlowPreview::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HomeCanvas(
    store: HomeCanvasStore,
    revision: Int,
    tokens: ColorTokens,
    modifier: Modifier = Modifier,
    peekEnabled: Boolean = true,
    paused: Boolean = false,
    visible: Boolean = true,
    onPeekDelta: (Float) -> Unit = {},
    onPeekEnd: () -> Unit = {},
    onPeekCancel: () -> Unit = {},
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var loadedRev by remember { mutableIntStateOf(-1) }
    var viewRef by remember { mutableStateOf<WebView?>(null) }
    // W1: one server per canvas; it rebuilds its loaders (and the CSP frame-src) per canvas revision.
    val widgetServer = remember(store) { CanvasWidgetServer(store) }
    val placed by store.placedWidgets.collectAsState()
    // W2: the router of the current WebView generation (the canvas page itself has no bridge).
    val routerHolder = remember(store) { arrayOfNulls<WidgetBridgeRouter>(1) }
    // B: stamps of the placed widget apps' files, the last time we looked; null until the first look.
    val stampHolder = remember(store) { arrayOfNulls<Map<String, Long>>(1) }
    suspend fun reloadChangedWidgets() {
        val now = runCatching { widgetServer.fileStamps() }.getOrNull() ?: return
        val changed = CanvasWidgets.changedStamps(stampHolder[0], now)
        stampHolder[0] = now
        if (changed.isNotEmpty()) viewRef?.evaluateJavascript(CanvasWidgets.reloadFramesJs(changed), null)
    }
    // The canvas reloads with fresh frames: what is on disk now is what they load, so it is the new baseline.
    suspend fun refreshCanvas() {
        store.refresh()
        stampHolder[0] = runCatching { widgetServer.fileStamps() }.getOrNull()
    }
    // An install, uninstall or manifest edit changes which widget apps the canvas may frame: reload it then.
    LaunchedEffect(store, placed, viewRef) {
        if (placed.isEmpty()) {
            routerHolder[0]?.setApps(emptyList())
            stampHolder[0] = null
            return@LaunchedEffect
        }
        runCatching { routerHolder[0]?.setApps(widgetServer.widgetApps()) }
        val app = DeftApp.instance
        combine(app.repository.observeApps(), app.myApps.previewRevision) { _, _ -> }
            .debounce(500)
            .collect {
                if (runCatching { widgetServer.staleSignature() }.getOrDefault(false)) refreshCanvas()
                else {
                    runCatching { routerHolder[0]?.setApps(widgetServer.widgetApps()) }
                    reloadChangedWidgets()
                }
            }
    }
    val themedHolder = remember(store) { arrayOfNulls<ColorTokens>(1) }
    val peekEnabledState = rememberUpdatedState(peekEnabled)
    val pausedState = rememberUpdatedState(paused)
    val onPeekDeltaState = rememberUpdatedState(onPeekDelta)
    val onPeekEndState = rememberUpdatedState(onPeekEnd)
    val onPeekCancelState = rememberUpdatedState(onPeekCancel)

    var generation by remember { mutableIntStateOf(0) }
    var bornAt by remember { mutableLongStateOf(0L) }
    var broken by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    if (broken) {
                        broken = false
                        loadedRev = -1
                        generation++
                    }
                }
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Draws and runs timers only while it can be seen: resumed, settled on Home, not covered.
    val running = resumed && visible && !paused
    LaunchedEffect(running, paused, viewRef) {
        val view = viewRef ?: return@LaunchedEffect
        routerHolder[0]?.visible = running
        if (running) {
            view.visibility = View.VISIBLE
            WebViewHostRuntime.ownerResumed(view, view)
            routerHolder[0]?.refreshGrants()
            // Back from a setup (or any grant change): a widget that was waiting for setup reloads with its grants.
            if (store.placedWidgets.value.isNotEmpty()) {
                if (runCatching { widgetServer.staleSignature() }.getOrDefault(false)) refreshCanvas()
                else {
                    // Same index: still drop the page's memoized copy (setup may have turned ready), and pick up files edited while away.
                    view.evaluateJavascript(CanvasWidgets.REDRAW_WIDGETS_JS, null)
                    reloadChangedWidgets()
                }
            }
        } else {
            // No view.onPause(): it tears down the process-wide geolocation/orientation dispatcher
            // that a live app preview may be using. Hidden views stop drawing; animations are frozen.
            view.visibility = View.INVISIBLE
            view.evaluateJavascript(REST_ANIMATIONS_JS, null)
            WebViewHostRuntime.ownerPaused(view, view)
        }
    }
    // Animate, then rest: freeze the page's animations a few seconds after it shows or is touched.
    var wake by remember { mutableIntStateOf(0) }
    var lastWake by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running, viewRef, loadedRev, wake) {
        if (!running) return@LaunchedEffect
        val view = viewRef ?: return@LaunchedEffect
        view.evaluateJavascript(RESUME_ANIMATIONS_JS, null)
        delay(REST_AFTER_MS)
        if (viewRef === view) view.evaluateJavascript(REST_ANIMATIONS_JS, null)
    }

    Box(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.changedToDown() || it.changedToUp() }) routerHolder[0]?.noteTouch()
                    if (event.changes.any { it.changedToDown() }) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastWake > WAKE_THROTTLE_MS) {
                            lastWake = now
                            wake++
                        }
                    }
                }
            }
        },
    ) {
        if (!broken) key(generation) { AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                bornAt = SystemClock.elapsedRealtime()
                WebView(context).apply {
                    isNestedScrollingEnabled = false
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    setBackgroundColor(tokens.background.toArgb())
                    app.deft.webview.DeftWebViewConfig.apply(
                        this,
                        geolocation = false,
                        profileName = "home",
                    )
                    settings.setSupportZoom(false)
                    settings.displayZoomControls = false
                    // W2: the widget frames' bridge; the canvas document gets none (the router refuses its origin).
                    routerHolder[0]?.detach()
                    routerHolder[0] = WidgetBridgeRouter(
                        context.applicationContext,
                        HomeCanvasStore.ORIGIN,
                        resolve = { widgetServer.widgetApps() },
                        loadGates = { GrantedPrefixes.gatesForApp(DeftApp.instance.repository, it) },
                    ).also { it.attach(this) }
                    webViewClient = canvasClient(
                        widgetServer,
                        tokens,
                        setup = { request ->
                            // A tap on a "set up" placeholder: only a placed widget app that needs setup, only after a real touch.
                            val target = WidgetRouting.setupTarget(
                                request.url.toString(),
                                request.hasGesture() && !request.isRedirect,
                                routerHolder[0]?.touchFresh() == true,
                                widgetServer.setupIds(),
                            )?.let { widgetServer.setupApp(it) }
                            if (target != null) {
                                val host = this@apply.context
                                runCatching { host.startActivity(setupIntent(host, target)) }
                            }
                            WidgetRouting.isSetupUrl(request.url.toString())
                        },
                    ) { dead ->
                        // Dead renderer: never crash the launcher. Rebuild once; a quick second death shows plain background.
                        if (viewRef === dead) {
                            routerHolder[0]?.detach()
                            routerHolder[0] = null
                            viewRef = null
                        }
                        if (SystemClock.elapsedRealtime() - bornAt < CRASH_LOOP_MS) {
                            broken = true
                        } else {
                            loadedRev = -1
                            generation++
                        }
                    }
                    val blocking = app.deft.webview.DeftWebViewConfig.blockingChrome()
                    webChromeClient = object : WebChromeClient() {
                        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?) =
                            blocking.onJsAlert(view, url, message, result)
                        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?) =
                            blocking.onJsConfirm(view, url, message, result)
                        override fun onJsPrompt(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            defaultValue: String?,
                            result: android.webkit.JsPromptResult?,
                        ) = blocking.onJsPrompt(view, url, message, defaultValue, result)
                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            val finding = consoleMessage?.let { WebViewFindings.fromConsole(it) } ?: return true
                            // W2: a line from a widget frame goes to that app's log and, summarised, to the canvas log.
                            if (routerHolder[0]?.logFrame(finding) == true) return true
                            MyAppDebugLog.append(HomeCanvasStore.SLUG, finding)
                            return true
                        }
                    }
                }
            },
            update = { view ->
                viewRef = view
                view.setBackgroundColor(tokens.background.toArgb())
                view.applyDeftTheme(tokens)
                // A: widget frames are other documents; they reload their theme.css only when told.
                if (themedHolder[0] != tokens) {
                    if (themedHolder[0] != null) view.evaluateJavascript(CanvasWidgets.THEME_FRAMES_JS, null)
                    themedHolder[0] = tokens
                }
                store.ensureSeeded()
                if (loadedRev != revision) {
                    loadedRev = revision
                    MyAppDebugLog.clear(HomeCanvasStore.SLUG)
                    view.loadUrl(HomeCanvasStore.startUrl(revision))
                }
            },
            onRelease = { view ->
                routerHolder[0]?.detach()
                routerHolder[0] = null
                if (viewRef === view) viewRef = null
                WebViewHostRuntime.ownerPaused(view, view)
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.webChromeClient = null
                view.webViewClient = WebViewClient()
                runCatching {
                    view.stopLoading()
                    view.loadUrl("about:blank")
                }
                view.destroy()
            },
        ) }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectCanvasPeekGestures(
                        enabled = { peekEnabledState.value },
                        onPeekDelta = { onPeekDeltaState.value(it) },
                        onPeekEnd = { onPeekEndState.value() },
                        onPeekCancel = { onPeekCancelState.value() },
                        onTap = { viewRef?.syntheticClick(it.x, it.y) },
                    )
                },
        )
    }
}

/**
 * Compose owns this layer so [HorizontalPager] can see sideways moves.
 * Vertical past slop is search/agent; a tap with no slop is forwarded to the WebView.
 */
private suspend fun PointerInputScope.detectCanvasPeekGestures(
    enabled: () -> Boolean,
    onPeekDelta: (Float) -> Unit,
    onPeekEnd: () -> Unit,
    onPeekCancel: () -> Unit,
    onTap: (Offset) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        var down = awaitPointerEvent().changes.firstOrNull { it.changedToDown() && !it.isConsumed }
        while (down == null) {
            down = awaitPointerEvent().changes.firstOrNull { it.changedToDown() && !it.isConsumed }
        }
        if (!enabled()) return@awaitEachGesture
        val pointerId = down.id
        val start = down.position
        var lastY = start.y
        var peeking = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: run {
                    if (peeking) onPeekCancel()
                    return@awaitEachGesture
                }
                if (!change.pressed) {
                    if (peeking) {
                        change.consume()
                        onPeekEnd()
                    } else if (hypot(change.position.x - start.x, change.position.y - start.y) < slop) {
                        onTap(start)
                    }
                    return@awaitEachGesture
                }
                val fromStart = change.position - start
                if (!peeking) {
                    if (abs(fromStart.x) < slop && abs(fromStart.y) < slop) continue
                    if (abs(fromStart.x) > abs(fromStart.y)) return@awaitEachGesture
                    peeking = true
                    val dy = change.position.y - lastY
                    lastY = change.position.y
                    change.consume()
                    onPeekDelta(dy)
                } else {
                    val dy = change.position.y - lastY
                    lastY = change.position.y
                    change.consume()
                    onPeekDelta(dy)
                }
            }
        } catch (e: CancellationException) {
            if (peeking) onPeekCancel()
            throw e
        }
    }
}

private fun WebView.syntheticClick(x: Float, y: Float) {
    val t = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
    val up = MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_UP, x, y, 0)
    dispatchTouchEvent(down)
    dispatchTouchEvent(up)
    down.recycle()
    up.recycle()
}

/** The app's normal consent/launch flow: a My App goes through WorkspaceLaunchActivity (consent, then it opens). */
private fun setupIntent(context: android.content.Context, target: InstalledWebApp): android.content.Intent {
    val slug = target.workspaceSlug
    val intent = if (slug != null) {
        WorkspaceLaunchActivity.intent(context, slug)
    } else {
        WebViewHostActivity.intent(context, target.id)
    }
    return if (context is android.app.Activity) intent else intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
}

private fun canvasClient(
    widgets: CanvasWidgetServer,
    tokens: ColorTokens,
    setup: (WebResourceRequest) -> Boolean,
    onGone: (WebView) -> Unit,
) = object : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // The canvas files, plus (W1) the widget index and the files of placed widget apps. Every other origin gets 403.
        widgets.intercept(request.url)?.let { response ->
            WebViewFindings.fromIntercept(
                request.isForMainFrame,
                request.url.toString(),
                response.statusCode,
            )?.let { MyAppDebugLog.append(HomeCanvasStore.SLUG, it) }
            return response
        }
        if (request.url.toString().startsWith(HomeCanvasStore.ORIGIN)) {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                404,
                "Not Found",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (setup(request)) return true
        return !request.url.toString().startsWith(HomeCanvasStore.ORIGIN)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        view.applyDeftTheme(tokens)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        view.applyDeftTheme(tokens)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onGone(view)
        return true
    }
}

private const val REST_AFTER_MS = 6_000L
private const val WAKE_THROTTLE_MS = 1_500L
private const val CRASH_LOOP_MS = 5_000L

// W2: widget frames are other documents (their own origin): the canvas tells each one to rest or resume with a
// 7-byte message; the widget runtime (motion.js) does the same work in its own document. A frame that loads later
// asks nothing: elements.js sends it the current state on load (it reads window.__deftRested).
private const val REST_ANIMATIONS_JS =
    "(function(){try{var l=window.__deftRested=window.__deftRested||[];document.getAnimations().forEach(function(a){" +
        "var t=a.effect&&a.effect.getComputedTiming&&a.effect.getComputedTiming();if(a.playState==='running'&&t&&t.iterations===Infinity){a.pause();l.push(a);}});}catch(e){}" +
        "try{document.querySelectorAll('deft-widget iframe,fl-widget iframe').forEach(function(f){f.contentWindow.postMessage('deft:rest','*');f.contentWindow.postMessage('fl:rest','*');});}catch(e){}})();"

private const val RESUME_ANIMATIONS_JS =
    "(function(){try{var l=window.__deftRested;window.__deftRested=null;if(l)l.forEach(function(a){" +
        "if(a.playState==='paused')a.play();});}catch(e){}" +
        "try{document.querySelectorAll('deft-widget iframe,fl-widget iframe').forEach(function(f){f.contentWindow.postMessage('deft:resume','*');f.contentWindow.postMessage('fl:resume','*');});}catch(e){}})();"
