package app.deft.background

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import app.deft.bridge.BridgeService
import app.deft.bridge.DeftJsBridge
import app.deft.data.InstalledWebApp
import app.deft.webview.CspPolicy
import app.deft.webview.EmbedScriptPolicy
import app.deft.webview.DeftWebViewConfig
import app.deft.webview.LocalOriginPathHandler
import app.deft.webview.MyAppDebugLog
import app.deft.webview.RenderGone
import app.deft.webview.WebViewFindings
import app.deft.webview.WebViewHostRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * PARITY D1: one background run. An off-screen WebView with the app's WebView config, profile,
 * served files and CSP loads `/_deft/v1/background.html` (which loads the manifest module), gets a
 * bridge like the host's (every call flagged `background`) and the trigger as the event
 * `background.trigger`. It is destroyed when the module calls `background.done`, when the
 * runner cancels it, or after [budgetMs] from the start of the load, whichever comes first.
 *
 * Every method runs on the main thread. [onEnd] is called exactly once, with the outcome.
 */
class BackgroundSession(
    private val context: Context,
    private val app: InstalledWebApp,
    private val assets: WebViewAssetLoader,
    private val policy: CspPolicy,
    private val grantedPrefixes: Set<String>,
    private val service: BridgeService?,
    private val budgetMs: Long,
    /** Workspace apps: console errors go to the app's debug log, as in the host. */
    private val debugSlug: String?,
    /** A parked page of this same app (same profile) to reuse instead of creating a WebView; see [BackgroundRunner]. */
    private val reuse: WebView? = null,
    /**
     * Offered the page after a run that ended cleanly (`ok`); true means the owner keeps it for the next run of this
     * app, so it is not destroyed. Any other ending destroys the page.
     */
    private val park: ((WebView) -> Boolean)? = null,
    private val onEnd: (BackgroundSession, String) -> Unit,
) {
    /** [SystemClock.elapsedRealtime] when the page started loading; 0 before [start]. */
    var startedAt: Long = 0L
        private set
    var ended: Boolean = false
        private set

    private var webView: WebView? = null
    private var bridge: DeftJsBridge? = null
    private var script: ScriptHandler? = null
    private var budgetJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Creates the page and starts the clock. Returns false, having created nothing and without
     * calling [onEnd], when this device's WebView cannot host the bridge.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun start(trigger: BackgroundTrigger): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "main thread only" }
        if (ended || webView != null) return false
        if (!DeftJsBridge.webViewSupported() || DeftWebViewConfig.isTooOld(context)) return false
        val view = reuse ?: WebView(context)
        webView = view
        if (reuse == null) {
            DeftWebViewConfig.apply(
                view,
                // PARITY G5 decides background location; a run gets none for now.
                geolocation = false,
                profileName = DeftWebViewConfig.profileName(app.originId, app.origin),
            )
            DeftWebViewConfig.applyPaymentRequest(view, false)
        }
        val sink = DeftJsBridge(app, grantedPrefixes, scope, background = true)
        sink.service = service
        sink.attach(view)
        bridge = sink
        val js = runCatching { DeftJsBridge.clientJs(context) }.getOrNull()
        script = js?.let {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(view, it + EmbedScriptPolicy.js(policy.embedOrigins), setOf(app.origin))
            }.getOrNull()
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)

            // A run stays on its one page: no navigation, no frames to other pages.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            // A dead renderer ends the run; the process must survive it.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                RenderGone.log(TAG, app.id, detail)
                endGone(view)
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean {
                result?.cancel()
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val slug = debugSlug ?: return false
                val finding = consoleMessage?.let { WebViewFindings.fromConsole(it) } ?: return false
                MyAppDebugLog.append(slug, finding)
                return false
            }
            // Camera, microphone and other permission requests keep the default: denied.
        }
        val online = context.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
        view.setNetworkAvailable(online)
        // Queued in the bridge until the module subscribes.
        sink.dispatchEvent(TRIGGER_EVENT, triggerPayload(trigger))
        WebViewHostRuntime.backgroundStarted(view)
        startedAt = SystemClock.elapsedRealtime()
        if (script == null) {
            Log.w(TAG, "bridge script not injected for ${app.id}")
            end(BackgroundRunner.OUTCOME_ERROR)
            return true
        }
        view.loadUrl(pageUrl(app.origin))
        // The budget is wall-clock time (elapsedRealtime counts deep sleep; delay alone does
        // not), checked in short steps so a device that slept anyway ends the run on waking.
        val deadline = startedAt + budgetMs
        budgetJob = scope.launch {
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                delay(left.coerceAtMost(BUDGET_CHECK_MS))
            }
            end(BackgroundRunner.OUTCOME_TIMEOUT)
        }
        return true
    }

    /**
     * PARITY F7b: hands an urgent [trigger] to this run while it goes on, as one more
     * `background.trigger` event. False when the run has ended or not started.
     */
    fun deliver(trigger: BackgroundTrigger): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "main thread only" }
        if (ended) return false
        val sink = bridge ?: return false
        if (startedAt > 0 && SystemClock.elapsedRealtime() - startedAt >= budgetMs) {
            end(BackgroundRunner.OUTCOME_TIMEOUT)
            return false
        }
        sink.dispatchEvent(TRIGGER_EVENT, triggerPayload(trigger))
        return true
    }

    /** Destroys the page (idempotent) and reports [outcome] once. */
    fun end(outcome: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "main thread only" }
        if (ended) return
        ended = true
        budgetJob?.cancel()
        budgetJob = null
        val view = webView
        webView = null
        bridge?.detach(view)
        bridge = null
        runCatching { script?.remove() }
        script = null
        if (view != null) {
            WebViewHostRuntime.backgroundEnded(view)
            view.webChromeClient = null
            view.webViewClient = WebViewClient()
            view.stopLoading()
            view.loadUrl("about:blank")
            view.clearHistory()
            // A clean run leaves its page, blank, for the app's next queued run: one renderer start per batch.
            val kept = outcome == BackgroundRunner.OUTCOME_OK && park?.invoke(view) == true
            if (!kept) view.destroy()
        }
        scope.cancel()
        onEnd(this, outcome)
    }

    /** [end] for a page whose renderer died: only removes and destroys the dead view. */
    private fun endGone(dead: WebView) {
        if (ended || webView !== dead) return
        ended = true
        budgetJob?.cancel()
        budgetJob = null
        webView = null
        bridge?.detach(dead)
        bridge = null
        script = null
        WebViewHostRuntime.backgroundEnded(dead)
        RenderGone.dispose(dead)
        scope.cancel()
        onEnd(this, BackgroundRunner.OUTCOME_ERROR)
    }

    /** For instrumented tests: evaluates [js] in the run's page; null once the page is gone. */
    fun evaluate(js: String, callback: (String?) -> Unit) {
        val view = webView ?: return callback(null)
        view.evaluateJavascript(js) { callback(it) }
    }

    companion object {
        private const val TAG = "BackgroundSession"
        const val TRIGGER_EVENT = "background.trigger"

        /** How often the budget timer looks at the clock. */
        private const val BUDGET_CHECK_MS = 1_000L

        fun pageUrl(origin: String): String = origin.trimEnd('/') + "/" + LocalOriginPathHandler.BACKGROUND_PAGE

        /** `{trigger, payload}`; a payload that is not a JSON object arrives as `{}`. */
        fun triggerPayload(trigger: BackgroundTrigger): String {
            val payload = runCatching { Json.parseToJsonElement(trigger.payloadJson) as? JsonObject }.getOrNull()
                ?: JsonObject(emptyMap())
            return buildJsonObject {
                put("trigger", JsonPrimitive(trigger.name))
                put("payload", payload)
            }.toString()
        }
    }
}
