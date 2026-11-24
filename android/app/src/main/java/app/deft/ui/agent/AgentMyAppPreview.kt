package app.deft.ui.agent

import app.deft.agent.ToolRunner
import app.deft.bridge.GrantedPrefixes
import app.deft.registry.DeftDependency
import app.deft.workspace.AppDeps
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebViewClient
import app.deft.webview.RenderGone
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.deft.BuildConfig
import app.deft.DeftApp
import app.deft.bridge.BridgeService
import app.deft.bridge.DeftJsBridge
import app.deft.catalog.Capability
import app.deft.catalog.GrantLevel
import app.deft.data.InstalledWebApp
import app.deft.data.canonicalOrigin
import app.deft.ui.consent.ConsentActivity
import app.deft.ui.theme.applyDeftTheme
import app.deft.webview.LocalOriginPathHandler
import app.deft.webview.MyAppDebugLog
import app.deft.webview.VerifyScript
import app.deft.webview.WebViewFindings
import app.deft.webview.WebViewHostRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun AgentMyAppPreview(slug: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = DeftApp.instance
    var grantTick by remember(slug) { mutableIntStateOf(0) }
    var loading by remember(slug) { mutableStateOf(true) }
    var error by remember(slug) { mutableStateOf<String?>(null) }
    var installed by remember(slug) { mutableStateOf<InstalledWebApp?>(null) }
    var pending by remember(slug) { mutableStateOf<List<Capability>>(emptyList()) }
    var newCapsOnly by remember(slug) { mutableStateOf(false) }
    var missingDeps by remember(slug) { mutableStateOf<List<DeftDependency>>(emptyList()) }
    // W5: tools the app declares that the user has not allowed yet (Apply lists them on the consent screen).
    var pendingTools by remember(slug) { mutableStateOf<List<String>>(emptyList()) }
    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { grantTick += 1 }

    LaunchedEffect(slug, grantTick) {
        loading = true
        error = null
        installed = null
        pending = emptyList()
        pendingTools = emptyList()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (!app.myApps.hasProject(slug)) {
                    error("my app '$slug' has no manifest.json")
                }
                val row = app.repository.ensureWorkspaceApp(slug)
                val need = app.repository.ungrantedCapabilities(row.id, row.requiredCapabilities)
                val hasGrants = app.repository.grantsForApp(row.id).isNotEmpty()
                val deps = AppDeps.missing(context, row.id, slug)
                val tools = app.repository.unconsentedTools(row.id, slug)
                    .filter { !it.isolate || ToolRunner.isolateSupported() }
                    .map { it.name }
                pendingTools = tools
                Pair(Triple(row, need, hasGrants), deps)
            }
        }
        loading = false
        result.fold(
            onSuccess = { (granted, deps) ->
                val (row, need, hasGrants) = granted
                installed = row
                pending = need
                newCapsOnly = hasGrants
                missingDeps = deps
            },
            onFailure = { error = it.message ?: "Could not open preview." },
        )
    }

    // apps.preview: why no app page shows, so the agent can tell the user what to do.
    val blocker = when {
        loading -> null
        error != null -> "Preview shows an error: $error"
        pending.isNotEmpty() || missingDeps.isNotEmpty() || pendingTools.isNotEmpty() ->
            "Preview is waiting for the user to tap Allow (access, libraries or tools for this app)"
        else -> null
    }
    LaunchedEffect(slug, blocker) { PreviewLink.setBlocker(slug, blocker) }
    DisposableEffect(slug) { onDispose { PreviewLink.setBlocker(slug, null) } }

    when {
        loading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        error != null -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        pending.isNotEmpty() || missingDeps.isNotEmpty() || pendingTools.isNotEmpty() -> {
            val row = installed
            Column(
                modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    when {
                        pending.isEmpty() && missingDeps.isEmpty() -> "Allow the agent to use this app's tools."
                        pending.isEmpty() -> "Download libraries to preview this app."
                        else -> "Allow access to preview this app."
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    (pending.map { it.title } + missingDeps.map { it.host + "/" + it.path.substringAfterLast('/') } +
                        pendingTools.map { "tool $it" }).joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row != null) {
                    Button(
                        onClick = {
                            consent.launch(
                                ConsentActivity.workspaceIntent(
                                    context,
                                    slug,
                                    row.name,
                                    row.startUrl,
                                    row.origin,
                                    row.manifestUrl,
                                    pending,
                                    newCapsOnly = newCapsOnly,
                                    skipLaunch = true,
                                ),
                            )
                        },
                    ) { Text("Allow") }
                }
            }
        }
        installed != null -> {
            PreviewWebView(
                installed = installed!!,
                slug = slug,
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewWebView(
    installed: InstalledWebApp,
    slug: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val composeView = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val revision by DeftApp.instance.myApps.previewRevision.collectAsState()
    val colors by DeftApp.instance.configStore.config.collectAsState()
    val bridge = remember(installed.id) {
        DeftJsBridge(installed, emptySet(), scope)
    }
    val tokens = rememberUpdatedState(colors.colors)
    val overlay = remember { PreviewOverlayHandle() }
    val loadGate = remember {
        PreviewLoadGate()
    }
    loadGate.revision = revision
    loadGate.slug = slug
    loadGate.url = startUrl(installed, revision)

    val askedDeps = remember(installed.id) { mutableSetOf<String>() }
    LaunchedEffect(installed.id, revision) {
        // The agent added a library mid-session: ask on the same consent screen (spec 10.10), once per URL set.
        val deps = withContext(Dispatchers.IO) {
            AppDeps.missing(context, installed.id, slug)
        }
        val key = deps.joinToString("|") { it.url }
        if (deps.isEmpty() || !askedDeps.add(key)) return@LaunchedEffect
        context.startActivity(
            ConsentActivity.workspaceIntent(
                context,
                slug,
                installed.name,
                installed.startUrl,
                installed.origin,
                installed.manifestUrl,
                emptyList(),
                newCapsOnly = true,
                skipLaunch = true,
            ),
        )
    }

    // PARITY H3/G1: the preview gets the same CSP as the host (consented origins and network grants only).
    var network by remember(installed.id) { mutableStateOf<app.deft.webview.CspPolicy?>(null) }
    LaunchedEffect(installed.id, revision) {
        network = withContext(Dispatchers.IO) {
            runCatching { DeftApp.instance.repository.cspPolicyFor(installed) }
                .getOrDefault(app.deft.webview.CspPolicy())
        }
    }

    LaunchedEffect(installed.id) {
        val gates = withContext(Dispatchers.IO) { gatesOf(installed) }
        bridge.applyGates(gates)
        bridge.sendGrants()
    }

    DisposableEffect(context, bridge) {
        BridgeService.start(context)
        var service: BridgeService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as BridgeService.LocalBinder).service()
                bridge.service = service
                service?.registerSink(bridge)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                bridge.service = null
                service = null
            }
        }
        context.bindService(Intent(context, BridgeService::class.java), conn, Context.BIND_AUTO_CREATE)
        onDispose {
            service?.unregisterSink(bridge)
            bridge.service = null
            runCatching { context.unbindService(conn) }
        }
    }

    DisposableEffect(lifecycle, overlay) {
        val observer = LifecycleEventObserver { _, event ->
            val view = overlay.web
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (view != null) {
                        view.onResume()
                        WebViewHostRuntime.ownerResumed(view, view)
                    }
                    scope.launch {
                        val gates = withContext(Dispatchers.IO) { gatesOf(installed) }
                        bridge.applyGates(gates)
                        bridge.sendGrants()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> if (view != null) {
                    view.onPause()
                    WebViewHostRuntime.ownerPaused(view, view)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // The preview is the app's screen while it shows and the home activity is resumed (bridge calls that need it).
    val activity = remember(context) { context.findActivity() as? androidx.activity.ComponentActivity }
    val surface = remember(installed.id, activity) {
        activity?.let { PreviewHostSurface(it, installed.id, installed.name) { overlay.web } }
    }
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> if (surface != null && activity != null) surface.permissionAsk.onResult(activity) }
    surface?.launchPermissions = { permissions.launch(it) }
    DisposableEffect(lifecycle, surface) {
        val shown = surface ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> shown.resume()
                Lifecycle.Event.ON_PAUSE -> shown.pause()
                Lifecycle.Event.ON_STOP -> shown.stop()
                else -> Unit
            }
        }
        // Catches up: a resumed lifecycle sends ON_RESUME right away.
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            shown.dispose()
        }
    }

    DisposableEffect(slug, installed.id, composeView, bridge, network) {
        val connect = network ?: return@DisposableEffect onDispose { }
        val host = previewHostOf(composeView) ?: return@DisposableEffect onDispose { }
        overlay.attach(
            host = host,
            context = context,
            installed = installed,
            slug = slug,
            network = connect,
            bridge = bridge,
            scope = scope,
            tokens = { tokens.value },
            loadGate = loadGate,
        )
        overlay.web?.let { PreviewLink.attach(slug, it, bridge) }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            overlay.web?.let {
                it.onResume()
                WebViewHostRuntime.ownerResumed(it, it)
            }
        }
        onDispose {
            PreviewLink.detach(slug, overlay.web)
            bridge.detach(overlay.web)
            overlay.detach()
        }
    }

    // apps.preview `ask`: a banner above the page (the page is a native overlay, so nothing can draw on top of it).
    // Collapsed, the banner goes and a question-mark bubble sits over the page's top-right corner (see PreviewOverlayHandle).
    val ask by PreviewLink.ask.collectAsState()
    val shownAsk = ask?.takeIf { it.slug == slug }
    var collapsed by remember(shownAsk?.id) { mutableStateOf(false) }
    // Drafts live here, not in AskBanner, so collapsing the banner keeps what the user typed. Per ask.
    var skipping by remember(shownAsk?.id) { mutableStateOf(false) }
    var typed by remember(shownAsk?.id) { mutableStateOf("") }
    var note by remember(shownAsk?.id) { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    val bubbleOpen = shownAsk != null && collapsed
    LaunchedEffect(bubbleOpen, shownAsk?.id, scheme) {
        if (bubbleOpen) overlay.setAskBubble(scheme) { collapsed = false } else overlay.setAskBubble(null, null)
    }
    Column(modifier.fillMaxSize()) {
        if (shownAsk != null && !collapsed) {
            AskBanner(
                ask = shownAsk,
                skipping = skipping,
                onSkipping = { skipping = it },
                typed = typed,
                onTyped = { typed = it },
                note = note,
                onNote = { note = it },
                onCollapse = { collapsed = true },
            )
        }
        PreviewBounds(overlay, composeView, Modifier.weight(1f).fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskBanner(
    ask: PreviewLink.Ask,
    skipping: Boolean,
    onSkipping: (Boolean) -> Unit,
    typed: String,
    onTyped: (String) -> Unit,
    note: String,
    onNote: (String) -> Unit,
    onCollapse: () -> Unit,
) {
    val spec = ask.spec
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The agent asks", style = MaterialTheme.typography.labelMedium)
                    // Agent text, shown as plain text only.
                    Text(spec.text.take(VerifyScript.MAX_ASK), style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Filled.ExpandLess, contentDescription = "Hide question")
                }
            }
            if (skipping) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { onNote(it.take(SKIP_NOTE_MAX)) },
                    singleLine = true,
                    placeholder = { Text("Why? (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { ask.answer.complete(AskAnswer.Skipped(note.trim().ifEmpty { null })) }) { Text("Send") }
                    TextButton(onClick = { onSkipping(false) }) { Text("Back") }
                }
            } else {
                when {
                    spec.options.isNotEmpty() -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        spec.options.forEach { option ->
                            OutlinedButton(onClick = { ask.answer.complete(AskAnswer.Answered(option)) }) { Text(option) }
                        }
                    }
                    spec.input != null -> {
                        val number = spec.input == "number"
                        val value = typed.trim()
                        val valid = value.isNotEmpty() && (!number || value.toDoubleOrNull()?.isFinite() == true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = typed,
                                onValueChange = { onTyped(it.take(VerifyScript.MAX_ASK)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
                                modifier = Modifier.weight(1f),
                            )
                            Button(enabled = valid, onClick = { ask.answer.complete(AskAnswer.Answered(value)) }) { Text("Send") }
                        }
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ask.answer.complete(AskAnswer.Done) }) { Text("Done") }
                    }
                }
                TextButton(onClick = { onSkipping(true) }) { Text("Skip") }
            }
        }
    }
}

private const val SKIP_NOTE_MAX = 120

@Composable
private fun AskBubble(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(48.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Show the agent's question")
        }
    }
}

@Composable
private fun PreviewBounds(overlay: PreviewOverlayHandle, composeView: View, modifier: Modifier) {
    Box(
        modifier
            .onGloballyPositioned { coords ->
                val host = overlay.host ?: previewHostOf(composeView) ?: return@onGloballyPositioned
                val bounds = coords.boundsInWindow()
                overlay.layout(
                    host = host,
                    windowX = bounds.left.roundToInt(),
                    windowY = bounds.top.roundToInt(),
                    width = bounds.width.roundToInt().coerceAtLeast(0),
                    height = bounds.height.roundToInt().coerceAtLeast(0),
                )
            },
    )
}

private class PreviewLoadGate {
    var revision = -1
    var loaded = -1
    var slug = ""
    var url = ""

    fun loadIfReady(view: WebView) {
        if (view.width < 8 || view.height < 8 || loaded == revision) return
        loaded = revision
        MyAppDebugLog.clear(slug)
        view.loadUrl(url)
    }
}

private class PreviewOverlayHandle {
    var host: ViewGroup? = null
        private set
    var web: WebView? = null
        private set
    private var slug = ""
    private var frame: FrameLayout? = null
    private var loadGate: PreviewLoadGate? = null
    private var windowX = 0
    private var windowY = 0
    private var width = 0
    private var height = 0
    private var hasBounds = false

    /** The last bubble request (colors, tap action); kept so attach() re-adds the bubble to a new frame. */
    private var askBubble: Pair<ColorScheme, () -> Unit>? = null
    private var bubble: ComposeView? = null

    /**
     * The question-mark bubble over the page's top-right corner, or removed (both null). It is a child of [frame]
     * above the WebView, because the WebView overlay sits above all Compose content of the activity.
     */
    fun setAskBubble(colors: ColorScheme?, onClick: (() -> Unit)?) {
        askBubble = if (colors != null && onClick != null) colors to onClick else null
        applyAskBubble()
    }

    private fun applyAskBubble() {
        removeAskBubble()
        val (colors, onClick) = askBubble ?: return
        val container = frame ?: return
        val density = container.resources.displayMetrics.density
        val size = (48 * density).roundToInt()
        val margin = (12 * density).roundToInt()
        val view = ComposeView(container.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent { MaterialTheme(colorScheme = colors) { AskBubble(onClick) } }
        }
        container.addView(
            view,
            FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                setMargins(margin, margin, margin, margin)
            },
        )
        bubble = view
    }

    private fun removeAskBubble() {
        val view = bubble ?: return
        bubble = null
        (view.parent as? ViewGroup)?.removeView(view)
    }

    fun attach(
        host: ViewGroup,
        context: Context,
        installed: InstalledWebApp,
        slug: String,
        network: app.deft.webview.CspPolicy,
        bridge: DeftJsBridge,
        scope: kotlinx.coroutines.CoroutineScope,
        tokens: () -> app.deft.config.ColorTokens,
        loadGate: PreviewLoadGate,
    ) {
        detach()
        this.host = host
        this.slug = slug
        this.loadGate = loadGate
        loadGate.loaded = -1
        val webView = createPreviewWebView(
            context = context,
            installed = installed,
            slug = slug,
            network = network,
            bridge = bridge,
            scope = scope,
            tokens = tokens,
            loadGate = loadGate,
            onGone = { dead -> renderGone(dead, bridge) },
        )
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        web = webView
        frame = container
        val above = (0 until host.childCount).maxOfOrNull { host.getChildAt(it).elevation } ?: 0f
        container.elevation = above + 2f
        host.addView(container, overlayParams(0, 0, 1, 1))
        if (hasBounds) applyBounds()
        applyAskBubble()
    }

    fun layout(host: ViewGroup, windowX: Int, windowY: Int, width: Int, height: Int) {
        this.host = host
        this.windowX = windowX
        this.windowY = windowY
        this.width = width
        this.height = height
        hasBounds = true
        applyBounds()
        val gate = loadGate
        val view = web
        if (gate != null && view != null) {
            view.post { gate.loadIfReady(view) }
        }
    }

    /** The renderer died: the preview closes to blank, and only the dead view is removed and destroyed. */
    private fun renderGone(dead: WebView, bridge: DeftJsBridge) {
        if (web !== dead) return
        PreviewLink.detach(slug, dead)
        bridge.detach(dead)
        removeAskBubble()
        val container = frame
        frame = null
        web = null
        loadGate = null
        (container?.parent as? ViewGroup)?.removeView(container)
        WebViewHostRuntime.ownerPaused(dead, dead)
        RenderGone.dispose(dead)
    }

    fun detach() {
        removeAskBubble()
        val container = frame
        val webView = web
        frame = null
        web = null
        host = null
        loadGate = null
        if (container != null) {
            (container.parent as? ViewGroup)?.removeView(container)
        }
        if (webView != null) {
            WebViewHostRuntime.ownerPaused(webView, webView)
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    private fun applyBounds() {
        val container = frame ?: return
        val parent = (container.parent as? View) ?: host ?: return
        val loc = IntArray(2)
        parent.getLocationInWindow(loc)
        val x = windowX - loc[0]
        val y = windowY - loc[1]
        if (width <= 0 || height <= 0) {
            container.visibility = View.INVISIBLE
            return
        }
        container.visibility = View.VISIBLE
        val lp = container.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.leftMargin == x && lp.topMargin == y && lp.width == width && lp.height == height) return
        lp.leftMargin = x
        lp.topMargin = y
        lp.width = width
        lp.height = height
        container.layoutParams = lp
    }
}

private fun overlayParams(x: Int, y: Int, w: Int, h: Int) = FrameLayout.LayoutParams(w, h).apply {
    gravity = Gravity.TOP or Gravity.START
    leftMargin = x
    topMargin = y
}

private fun previewHostOf(view: View): ViewGroup? {
    var current: View? = view
    while (current != null) {
        if (current.id == android.R.id.content && current is ViewGroup) return current
        current = current.parent as? View
    }
    return view.context.findActivity()?.findViewById(android.R.id.content)
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@SuppressLint("SetJavaScriptEnabled")
private fun createPreviewWebView(
    context: Context,
    installed: InstalledWebApp,
    slug: String,
    network: app.deft.webview.CspPolicy,
    bridge: DeftJsBridge,
    scope: kotlinx.coroutines.CoroutineScope,
    tokens: () -> app.deft.config.ColorTokens,
    loadGate: PreviewLoadGate,
    onGone: (WebView) -> Unit,
): WebView = WebView(context).apply {
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    setBackgroundColor(Color.BLACK)
    isNestedScrollingEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    app.deft.webview.DeftWebViewConfig.apply(
        this,
        geolocation = true,
        profileName = app.deft.webview.DeftWebViewConfig.profileName(installed.originId, installed.origin),
    )
    settings.mediaPlaybackRequiresUserGesture = false
    setOnTouchListener { v, event -> app.deft.bridge.UserGesture.track(v, event, installed.id) }
    // PARITY H3: same merged tree and connect-src as the host.
    val tree = DeftApp.instance.myApps.tree(slug)
    val loader = LocalOriginPathHandler.loader(
        tree.base,
        LocalOriginPathHandler.hostOfOrigin(installed.origin),
        policy = network,
        overlay = tree.overlay,
        depStore = app.deft.workspace.DepStore.root(context),
    )
    webViewClient = previewClient(installed, slug, loader, embedsActive = network.embedOrigins.isNotEmpty(), onGone = onGone) {
        applyDeftTheme(tokens())
    }
    webChromeClient = previewChrome(context, installed, slug, scope)
    injectPreviewScripts(this, installed, network.embedOrigins)
    if (DeftJsBridge.webViewSupported()) {
        runCatching { bridge.attach(this) }
    }
    addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        val web = v as WebView
        loadGate.loadIfReady(web)
        if (web.width > 0 && web.height > 0 &&
            (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
        ) {
            web.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
        }
    }
}

private fun startUrl(installed: InstalledWebApp, revision: Int): String {
    val start = installed.startUrl
    return if (start.contains('?')) "$start&r=$revision" else "$start?r=$revision"
}

private fun injectPreviewScripts(view: WebView, installed: InstalledWebApp, embedOrigins: List<String>) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
    val js = runCatching { DeftJsBridge.clientJs(view.context) }.getOrNull() ?: return
    // PARITY G14: same Trusted Types default policy as the host for approved embed origins.
    val source = js + "\n" + WebViewFindings.HOOK_JS + app.deft.webview.EmbedScriptPolicy.js(embedOrigins)
    runCatching { WebViewCompat.addDocumentStartJavaScript(view, source, setOf(installed.origin)) }
}

private fun previewClient(
    installed: InstalledWebApp,
    slug: String,
    loader: WebViewAssetLoader,
    embedsActive: Boolean,
    onGone: (WebView) -> Unit,
    onPage: WebView.() -> Unit,
) = object : WebViewClient() {
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        RenderGone.log("AgentMyAppPreview", slug, detail)
        onGone(view)
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        loader.shouldInterceptRequest(request.url)?.let { response ->
            WebViewFindings.fromIntercept(
                request.isForMainFrame,
                request.url.toString(),
                response.statusCode,
            )?.let { MyAppDebugLog.append(slug, it) }
            return response
        }
        return null
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (app.deft.webview.DeftNavigation.allowSubframe(url, request.isForMainFrame, embedsActive)) return false
        return canonicalOrigin(url) != canonicalOrigin(installed.origin)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (WebViewFindings.shouldRecordLoad(request.isForMainFrame, error.description)) {
            MyAppDebugLog.append(slug, WebViewFindings.load(error.description, request.url.toString()))
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (WebViewFindings.shouldRecordHttp(request.isForMainFrame, request.url.toString())) {
            MyAppDebugLog.append(slug, WebViewFindings.http(errorResponse.statusCode, request.url.toString()))
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        PreviewLink.pageState(slug, view, finished = false)
        view.onPage()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        view.onPage()
        PreviewLink.pageState(slug, view, finished = true)
    }
}

private fun previewChrome(
    context: Context,
    installed: InstalledWebApp,
    slug: String,
    scope: kotlinx.coroutines.CoroutineScope,
) = object : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        val finding = consoleMessage?.let { WebViewFindings.fromConsole(it) } ?: return true
        MyAppDebugLog.append(slug, finding)
        PreviewLink.console(slug, finding.level, finding.text)
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request ?: return
        scope.launch {
            val repo = DeftApp.instance.repository
            val allow = mutableListOf<String>()
            for (r in request.resources) {
                val cap = app.deft.webview.WebPlatform.capabilityFor(r) ?: continue
                val grant = if (cap == null) {
                    GrantLevel.DENIED
                } else {
                    repo.grantLevel(installed.id, cap)
                }
                if (grant != GrantLevel.DENIED) {
                    allow += r
                }
            }
            withContext(Dispatchers.Main) {
                if (allow.isEmpty()) request.deny() else request.grant(allow.toTypedArray())
            }
        }
    }
}

private suspend fun gatesOf(installed: InstalledWebApp): GrantedPrefixes.Gates =
    GrantedPrefixes.gatesForApp(DeftApp.instance.repository, installed)
