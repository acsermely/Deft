package app.deft.ime

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.ConnectivityManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import app.deft.DeftApp
import app.deft.background.BackgroundRunner
import app.deft.bridge.BridgeService
import app.deft.bridge.DeftJsBridge
import app.deft.bridge.modules.ClipboardModule
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
import java.lang.ref.WeakReference

/**
 * PARITY G11 `ime`: Deft's keyboard, showing the manifest `keyboard.page` of the one
 * web app holding the `ime` grant. Disabled in the manifest; [app.deft.roles.RoleComponents]
 * enables it only while an app holds the grant, so revoking it makes Android switch to another keyboard.
 *
 * The page is a WebView inside the keyboard's own window (no virtual display needed), as tall as
 * the page asks with `ime.setHeight` ([ImeSpec.DEFAULT_HEIGHT_DP] until then). Its bridge has the
 * holder's grants, flagged as background with `ime` as its kill switch: anything that would show a
 * screen or a prompt returns `needs_foreground`. Only this page may call `ime.*` (checked in
 * `BridgeService.invoke` and again in [ImeModule][app.deft.bridge.modules.ImeModule]).
 *
 * What the page learns: `ime.start {inputType, action, package}` when a field is focused and
 * `ime.finish {}` when it is left. Never the text in the field or around the cursor, never the
 * selection. **Password fields** (text, visible, web and number passwords) never reach the page:
 * the launcher shows its own small keyboard ([NativeKeyboard]) instead and sends no event, and
 * the page's `ime.*` calls fail until a normal field is focused. The same keyboard shows when no
 * app holds the grant or its page cannot be shown.
 */
class DeftInputMethodService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null

    private var root: FrameLayout? = null
    private var native: NativeKeyboard? = null

    private var holderId: String? = null
    private var webView: WebView? = null
    private var bridge: DeftJsBridge? = null
    private var script: ScriptHandler? = null
    private var shownInRuntime = false
    private var windowShown = false
    private var destroyed = false
    private var heightDp = ImeSpec.DEFAULT_HEIGHT_DP

    /** A normal (not password) field is being edited with the web keyboard; the page may type. */
    private var webSession = false

    /** `ime.start` waiting for the page to subscribe (the page was still loading). */
    private var pendingStart: String? = null

    /** The focused field's Enter action (`ImeSpec.actionName`) and whether it takes several lines. */
    private var action = "none"
    private var multiline = false

    override fun onCreate() {
        super.onCreate()
        active = WeakReference(this)
    }

    override fun onDestroy() {
        destroyed = true
        loadJob?.cancel()
        teardown()
        scope.cancel()
        if (active?.get() === this) active = null
        super.onDestroy()
    }

    // Never the full-screen "extract" editor: it would show the field's text in the keyboard window.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        root = frame
        // A new input view (configuration change): move the page over.
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            frame.addView(view, webParams())
        }
        native = null
        if (webView == null && loadJob == null) load()
        return frame
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        action = ImeSpec.actionName(info.imeOptions)
        multiline = ImeSpec.inputTypeName(info.inputType) == "multiline"
        if (ImeSpec.isPassword(info.inputType)) {
            // PARITY G11: the page sees nothing of a password field, not even that one is focused.
            webSession = false
            pendingStart = null
            showNative(NativeKeyboard.Reason.PASSWORD, ImeSpec.isNumberPassword(info.inputType))
            return
        }
        webSession = true
        val payload = ImeSpec.startPayload(info.inputType, info.imeOptions, info.packageName)
        if (webView != null && bridge != null) {
            showWeb()
            sendStart(payload)
        } else {
            pendingStart = payload
            showNative(NativeKeyboard.Reason.LOADING, numbers = false)
            if (loadJob == null) load()
        }
        recheckHolder()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        endSession()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        endSession()
        super.onFinishInput()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        windowShown = true
        resumePage()
    }

    /** The page runs like the live wallpaper's: resumed, with timers on, while the keyboard shows. */
    private fun resumePage() {
        val view = webView ?: return
        if (!shownInRuntime) {
            shownInRuntime = true
            WebViewHostRuntime.wallpaperShown(view)
        }
    }

    override fun onWindowHidden() {
        windowShown = false
        val view = webView
        if (view != null && shownInRuntime) {
            shownInRuntime = false
            WebViewHostRuntime.wallpaperHidden(view)
        }
        super.onWindowHidden()
    }

    private fun endSession() {
        val was = webSession
        webSession = false
        pendingStart = null
        if (!was) return
        val sink = bridge ?: return
        if (sink.hasSubscriber(ImeSpec.FINISH_EVENT)) sink.dispatchEvent(ImeSpec.FINISH_EVENT, ImeSpec.FINISH_PAYLOAD)
    }

    private fun sendStart(payload: String) {
        val sink = bridge ?: return
        if (sink.hasSubscriber(ImeSpec.START_EVENT)) {
            pendingStart = null
            sink.dispatchEvent(ImeSpec.START_EVENT, payload)
        } else {
            pendingStart = payload
        }
    }

    private fun webParams(): FrameLayout.LayoutParams {
        val dm = resources.displayMetrics
        return FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ImeSpec.heightPx(heightDp, dm.density, dm.heightPixels),
        )
    }

    private fun showWeb() {
        val frame = root ?: return
        val view = webView ?: return
        native?.let { frame.removeView(it.view) }
        native = null
        if (view.parent == null) frame.addView(view, webParams())
        view.visibility = View.VISIBLE
    }

    private fun showNative(reason: NativeKeyboard.Reason, numbers: Boolean) {
        val frame = root ?: return
        webView?.visibility = View.GONE
        val current = native
        if (current != null) {
            current.reset(reason, numbers)
            return
        }
        val keyboard = NativeKeyboard(
            this,
            onText = { text -> currentInputConnection?.commitText(text, 1) },
            onBackspace = { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) },
            onEnter = { pressEnter() },
            onSwitch = { switchKeyboard() },
        )
        keyboard.reset(reason, numbers)
        native = keyboard
        frame.addView(keyboard.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun pressEnter() {
        val id = ImeSpec.actionId(action)
        val ic = currentInputConnection ?: return
        if (id != null && !multiline) ic.performEditorAction(id) else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun switchKeyboard(): Boolean =
        runCatching { switchToPreviousInputMethod() || switchToNextInputMethod(false) }.getOrDefault(false)

    // --- Calls from the page (ImeModule), main thread ---

    /** Error message when the page may not type now, or null. */
    private fun typingRefused(): String? = if (!webSession || currentInputConnection == null) "No text field is open" else null

    fun commitText(text: String): String? = typingRefused() ?: run {
        currentInputConnection?.commitText(text, 1)
        null
    }

    fun deleteBefore(n: Int): String? = typingRefused() ?: run {
        val ic = currentInputConnection
        // With text selected, one delete removes the selection, as a backspace key would.
        val selected = ic?.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else ic?.deleteSurroundingTextInCodePoints(n, 0)
        null
    }

    fun sendKey(code: Int): String? = typingRefused() ?: run {
        if (code == KeyEvent.KEYCODE_ENTER) pressEnter() else sendDownUpKeyEvents(code)
        null
    }

    fun setComposing(text: String): String? = typingRefused() ?: run {
        currentInputConnection?.setComposingText(text, 1)
        null
    }

    fun finishComposing(): String? = typingRefused() ?: run {
        currentInputConnection?.finishComposingText()
        null
    }

    fun hide(): String? = typingRefused() ?: run {
        requestHideSelf(0)
        null
    }

    fun switchFromPage(): Boolean = switchKeyboard()

    fun setHeight(dp: Int) {
        heightDp = dp
        webView?.let { it.layoutParams = webParams() }
    }

    /** Whether [webAppId]'s keyboard page is the one shown here. */
    fun showsPageOf(webAppId: String): Boolean = holderId == webAppId && bridge != null

    /** PARITY G12: hands a clipboard event to the keyboard page, only while it listens (never queued). */
    fun deliver(webAppId: String, event: String, payloadJson: String) {
        val sink = bridge ?: return
        if (holderId != webAppId || !sink.hasSubscriber(event)) return
        sink.dispatchEvent(event, payloadJson)
    }

    // --- The page ---

    private fun recheckHolder() {
        scope.launch {
            val now = withContext(Dispatchers.IO) { currentHolder() }
            if (destroyed || loadJob != null || now == holderId) return@launch
            teardown()
            load()
        }
    }

    private suspend fun currentHolder(): String? =
        DeftApp.instance.repository.specialHolder(Capability.IME.exclusiveGroup!!)

    private fun load() {
        if (destroyed || loadJob != null) return
        loadJob = scope.launch {
            try {
                val target = withContext(Dispatchers.IO) { resolve() }
                if (destroyed) return@launch
                if (target == null || !build(target)) {
                    teardown()
                    if (webSession) showNative(NativeKeyboard.Reason.NO_APP, numbers = false)
                    return@launch
                }
                if (webSession) {
                    showWeb()
                    pendingStart?.let { sendStart(it) }
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
        if (Capability.IME !in app.requiredCapabilities) return null
        val page = repo.keyboardFor(app)?.page ?: return null
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
        if (!DeftJsBridge.webViewSupported() || DeftWebViewConfig.isTooOld(this)) return false
        val frame = root ?: return false
        val view = WebView(this)
        view.setBackgroundColor(Color.BLACK)
        DeftWebViewConfig.apply(
            view,
            geolocation = false,
            profileName = DeftWebViewConfig.profileName(t.app.originId, t.app.origin),
        )
        DeftWebViewConfig.applyPaymentRequest(view, false)
        val sink = DeftJsBridge(t.app, t.prefixes, scope, background = true, runCapability = Capability.IME)
        sink.service = BridgeService.instanceOrNull()
        sink.onSubscribe = { event -> if (event == ImeSpec.START_EVENT && webSession) pendingStart?.let { sendStart(it) } }
        sink.attach(view)
        val js = runCatching { DeftJsBridge.clientJs(this) }.getOrNull()
        val handler = js?.let {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(view, it + EmbedScriptPolicy.js(t.policy.embedOrigins), setOf(t.app.origin))
            }.onFailure { e -> Log.w(TAG, "bridge script not injected for ${t.app.id}", e) }.getOrNull()
        }
        if (handler == null) {
            sink.detach(view)
            view.destroy()
            return false
        }
        webView = view
        bridge = sink
        script = handler
        holderId = t.app.id
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                t.assets.shouldInterceptRequest(request.url)

            // The keyboard stays on its one page.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            // A crashed page must not take the keyboard down; the launcher's own keyboard shows until the next field.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                view.post {
                    if (webView === view) {
                        teardown()
                        if (webSession) showNative(NativeKeyboard.Reason.NO_APP, numbers = false)
                    }
                }
                return true
            }
        }
        view.webChromeClient = DeftWebViewConfig.blockingChrome()
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        val online = getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
        view.setNetworkAvailable(online)
        view.visibility = View.GONE
        frame.addView(view, webParams())
        view.loadUrl(t.app.origin.trimEnd('/') + t.page)
        if (windowShown) resumePage()
        return true
    }

    /** Destroys the page (idempotent). Main thread. */
    private fun teardown() {
        val view = webView
        val id = holderId
        webView = null
        holderId = null
        bridge?.detach(view)
        bridge = null
        runCatching { script?.remove() }
        script = null
        // PARITY G12: the keyboard page's clipboard watch ends with the page.
        if (id != null) ClipboardModule.stopIme(id)
        if (view != null) {
            if (shownInRuntime) {
                shownInRuntime = false
                WebViewHostRuntime.wallpaperHidden(view)
            }
            WebViewHostRuntime.wallpaperGone(view)
            (view.parent as? ViewGroup)?.removeView(view)
            view.webChromeClient = null
            view.webViewClient = WebViewClient()
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
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
        private const val TAG = "DeftInputMethod"

        @Volatile private var active: WeakReference<DeftInputMethodService>? = null

        /** The running keyboard if it shows [webAppId]'s keyboard page. Main thread. */
        fun forPage(webAppId: String): DeftInputMethodService? = active?.get()?.takeIf { it.showsPageOf(webAppId) }

        fun component(context: Context): ComponentName = ComponentName(context, DeftInputMethodService::class.java)

        /** Whether Deft's keyboard is turned on in Android's keyboard settings. */
        fun isEnabled(context: Context): Boolean = runCatching {
            val me = component(context)
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList
                ?.any { it.component == me } == true
        }.getOrDefault(false)

        /**
         * Whether Deft's keyboard is the phone's keyboard now (turned on and chosen).
         * Android 10+ lets this keyboard read the clipboard then (PARITY G12 IME mode).
         */
        fun isCurrent(context: Context): Boolean = runCatching {
            val setting = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ImeSpec.isImeId(setting, context.packageName, DeftInputMethodService::class.java.name) && isEnabled(context)
        }.getOrDefault(false)
    }
}
