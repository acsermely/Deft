package app.deft.agent

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import app.deft.DeftApp
import app.deft.background.BackgroundRunner
import app.deft.bridge.BridgeConfirms
import app.deft.bridge.BridgeService
import app.deft.bridge.DeftJsBridge
import app.deft.bridge.RunContext
import app.deft.data.InstalledWebApp
import app.deft.registry.DeftTool
import app.deft.webview.EmbedScriptPolicy
import app.deft.webview.DeftWebViewConfig
import app.deft.webview.LocalOriginPathHandler
import app.deft.webview.MyAppDebugLog
import app.deft.webview.RenderGone
import app.deft.webview.WebViewFindings
import app.deft.webview.WebViewHostRuntime
import app.deft.workspace.DepStore
import app.deft.workspace.MergedAppTree
import app.deft.workspace.OverlayStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A custom tool call that did not produce a result; [message] is what the model reads (after "error: "). */
class ToolFailure(message: String) : Exception(message)

/**
 * W5: runs custom tool handlers for one agent session. Calls are serialized.
 *
 * **Page handlers** run in one warm hidden WebView on the app's origin, built like a background run's page
 * (same profile, served files, CSP and grants; see [app.deft.background.BackgroundSession]) but flagged
 * [RunContext.TOOL]: confirm-each actions still show their dialog because the agent screen is in front,
 * every other screen or picker is `needs_foreground`. The page is reused by calls to the same app and replaced when
 * another app's tool is called (profile, bridge and file loader belong to one app), when the app's files changed
 * since it loaded, after a timeout, and when its renderer dies. It closes 60 s after the last call and with the session.
 *
 * **Isolate handlers** run in `androidx.javascriptengine`: no bridge, no network, 4 MB heap, a fresh isolate per call
 * inside one warm sandbox that closes with the page.
 */
class ToolRunner(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var page: Page? = null
    private var sandbox: JavaScriptSandbox? = null
    private var seq = 0
    private val idleClose = Runnable { closeNow() }

    private class Page(
        val app: InstalledWebApp,
        val stamp: Long,
        val view: WebView,
        val bridge: DeftJsBridge,
        var script: ScriptHandler?,
    ) {
        val ready = CompletableDeferred<Unit>()
        val pending = HashMap<Int, CompletableDeferred<JsonObject>>()
        var dead = false
    }

    /** Runs a page handler; the raw JSON text of its return value. Throws [ToolFailure]. */
    suspend fun callPage(app: InstalledWebApp, tool: DeftTool, paramsJson: String): String = mutex.withLock {
        withContext(Dispatchers.Main) { main.removeCallbacks(idleClose) }
        try {
            val dir = BackgroundRunner.servingDir(app) ?: throw ToolFailure("this app has no files to run")
            val stamp = withContext(Dispatchers.IO) { filesStamp(app, dir) }
            val started = SystemClock.elapsedRealtime()
            val p = ensurePage(app, dir, stamp)
            val id = ++seq
            val answer = CompletableDeferred<JsonObject>()
            withContext(Dispatchers.Main) {
                if (p.dead) throw ToolFailure("the tool page died; try again")
                p.pending[id] = answer
                val script = "self.__deftToolRun($id,${quote(tool.handlerPath)},${quote(tool.fn)},${quote(paramsJson)})"
                p.view.evaluateJavascript(script, null)
            }
            val reply = awaitActive(answer, started, app.id) { p.dead }
            withContext(Dispatchers.Main) { p.pending.remove(id) }
            if (reply == null) {
                withContext(Dispatchers.Main) { if (page === p) destroyPage() }
                throw ToolFailure(
                    if (p.dead) "the tool page died (its renderer was killed); try again"
                    else "tool did not finish within ${CustomTools.CALL_TIMEOUT_MS / 1000} s",
                )
            }
            if (reply["ok"]?.let { (it as? JsonPrimitive)?.booleanOrNull } != true) {
                throw ToolFailure((reply["error"] as? JsonPrimitive)?.contentOrNull ?: "tool failed")
            }
            (reply["json"] as? JsonPrimitive)?.contentOrNull ?: "null"
        } finally {
            // NonCancellable: a cancelled call (the user tapped Stop) must still schedule the idle close.
            withContext(NonCancellable + Dispatchers.Main) { main.postDelayed(idleClose, IDLE_MS) }
        }
    }

    /** Runs an isolate handler: [source] is the handler file text (a plain script). Throws [ToolFailure]. */
    suspend fun callIsolate(source: String, tool: DeftTool, paramsJson: String): String = mutex.withLock {
        withContext(Dispatchers.Main) { main.removeCallbacks(idleClose) }
        try {
            if (!isolateSupported()) throw ToolFailure(ISOLATE_UNSUPPORTED)
            val code = IsolateScript.build(source, tool.fn, paramsJson)
            val box = sandbox ?: createSandbox().also { fresh ->
                if (!fresh.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                    runCatching { fresh.close() }
                    throw ToolFailure(ISOLATE_UNSUPPORTED)
                }
                sandbox = fresh
            }
            val params = IsolateStartupParameters().apply {
                maxHeapSizeBytes = IsolateScript.MAX_HEAP_BYTES
                maxEvaluationReturnSizeBytes = IsolateScript.MAX_RESULT_BYTES
            }
            val isolate = try {
                box.createIsolate(params)
            } catch (t: Throwable) {
                // A sandbox whose process died stays unusable: drop it so the next call starts a new one.
                runCatching { box.close() }
                sandbox = null
                throw ToolFailure("the isolate engine is not available: ${t.message ?: t.javaClass.simpleName}")
            }
            try {
                val result = withTimeoutOrNull(IsolateScript.EVAL_TIMEOUT_MS) { isolate.evaluateJavaScriptAsync(code).await() }
                    ?: throw ToolFailure("isolate handler used more than its ${IsolateScript.EVAL_TIMEOUT_MS} ms of time")
                result
            } catch (e: ToolFailure) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                throw ToolFailure(isolateMessage(t))
            } finally {
                runCatching { isolate.close() }
            }
        } finally {
            // NonCancellable: a cancelled call (the user tapped Stop) must still schedule the idle close.
            withContext(NonCancellable + Dispatchers.Main) { main.postDelayed(idleClose, IDLE_MS) }
        }
    }

    /** Session end: closes the page and the sandbox now. Safe from any thread. */
    fun close() {
        val end = Runnable {
            main.removeCallbacks(idleClose)
            closeNow()
            scope.cancel()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) end.run() else main.post(end)
    }

    /** Main thread. */
    private fun closeNow() {
        destroyPage()
        sandbox?.let { runCatching { it.close() } }
        sandbox = null
    }

    private suspend fun ensurePage(app: InstalledWebApp, dir: File, stamp: Long): Page {
        val current = withContext(Dispatchers.Main) { page }
        if (current != null && !current.dead && current.app.id == app.id && current.stamp == stamp) {
            return current
        }
        val repo = DeftApp.instance.repository
        val policy = repo.cspPolicyFor(app)
        val prefixes = BackgroundRunner.grantedPrefixesOf(repo, app)
        val assets = LocalOriginPathHandler.loader(
            dir,
            LocalOriginPathHandler.hostOfOrigin(app.origin),
            policy = policy,
            overlay = OverlayStore.dir(context, app.id),
            depStore = DepStore.root(context),
        )
        val service = awaitService()
        val fresh = withContext(Dispatchers.Main) {
            destroyPage()
            createPage(app, stamp, assets, policy, prefixes, service)
        } ?: throw ToolFailure("this device's WebView cannot run app tools")
        val ok = withTimeoutOrNull(CustomTools.CALL_TIMEOUT_MS) { fresh.ready.await() }
        if (ok == null) {
            withContext(Dispatchers.Main) { if (page === fresh) destroyPage() }
            throw ToolFailure("the tool page did not start")
        }
        return fresh
    }

    private suspend fun awaitService(): BridgeService? {
        BridgeService.instanceOrNull()?.let { return it }
        BridgeService.start(context)
        repeat(40) {
            BridgeService.instanceOrNull()?.let { return it }
            delay(50)
        }
        return BridgeService.instanceOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPage(
        app: InstalledWebApp,
        stamp: Long,
        assets: WebViewAssetLoader,
        policy: app.deft.webview.CspPolicy,
        prefixes: Set<String>,
        service: BridgeService?,
    ): Page? {
        if (!DeftJsBridge.webViewSupported() || DeftWebViewConfig.isTooOld(context)) return null
        val view = WebView(context)
        DeftWebViewConfig.apply(view, geolocation = false, profileName = DeftWebViewConfig.profileName(app.originId, app.origin))
        DeftWebViewConfig.applyPaymentRequest(view, false)
        val bridge = DeftJsBridge(app, prefixes, scope, background = true, context = RunContext.TOOL)
        bridge.service = service
        bridge.attach(view)
        val js = runCatching { DeftJsBridge.clientJs(context) }.getOrNull()
        val handler = js?.let {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(view, it + EmbedScriptPolicy.js(policy.embedOrigins), setOf(app.origin))
            }.getOrNull()
        }
        val created = Page(app, stamp, view, bridge, handler)
        WebViewCompat.addWebMessageListener(view, PORT, setOf(app.origin)) { _, message, _, isMainFrame, _ ->
            if (isMainFrame) onMessage(created, message.data)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)

            // The page stays on its one URL: no navigation, no frames to other pages.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                RenderGone.log(TAG, app.id, detail)
                if (page === created) {
                    page = null
                    failPending(created, "the tool page died (its renderer was killed); try again")
                    created.dead = true
                    runCatching { created.bridge.detach(view) }
                    WebViewHostRuntime.backgroundEnded(view)
                    RenderGone.dispose(view)
                }
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

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                result?.cancel()
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val slug = app.workspaceSlug ?: return false
                val finding = consoleMessage?.let { WebViewFindings.fromConsole(it) } ?: return false
                MyAppDebugLog.append(slug, finding)
                return false
            }
        }
        view.setNetworkAvailable(context.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null)
        WebViewHostRuntime.backgroundStarted(view)
        page = created
        if (handler == null) {
            Log.w(TAG, "bridge script not injected for ${app.id}")
        }
        view.loadUrl(LocalOriginPathHandler.TOOL_PAGE.let { app.origin.trimEnd('/') + "/" + it })
        return created
    }

    private fun onMessage(p: Page, raw: String?) {
        val obj = runCatching { Json.parseToJsonElement(raw ?: return) as? JsonObject }.getOrNull() ?: return
        if (obj["ready"] != null) {
            p.ready.complete(Unit)
            return
        }
        val id = (obj["id"] as? JsonPrimitive)?.intOrNull ?: return
        p.pending.remove(id)?.complete(obj)
    }

    private fun failPending(p: Page, message: String) {
        val err = JsonObject(mapOf("ok" to JsonPrimitive(false), "error" to JsonPrimitive(message)))
        for (d in p.pending.values) d.complete(err)
        p.pending.clear()
    }

    /** Main thread. */
    private fun destroyPage() {
        val p = page ?: return
        page = null
        p.dead = true
        failPending(p, "the tool page was closed")
        runCatching { p.script?.remove() }
        p.script = null
        val view = p.view
        runCatching { p.bridge.detach(view) }
        WebViewHostRuntime.backgroundEnded(view)
        runCatching { WebViewCompat.removeWebMessageListener(view, PORT) }
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.stopLoading()
        view.loadUrl("about:blank")
        view.destroy()
    }

    /**
     * Waits for [answer], counting only active time against [CustomTools.CALL_TIMEOUT_MS]: while a confirm dialog of this tool's own app
     * waits for the user the clock stands still (at most [MAX_WAIT_MS] in all). Null on timeout or a dead page.
     */
    private suspend fun awaitActive(answer: CompletableDeferred<JsonObject>, started: Long, appId: String, dead: () -> Boolean): JsonObject? {
        var active = SystemClock.elapsedRealtime() - started
        var last = SystemClock.elapsedRealtime()
        val begin = last
        while (!answer.isCompleted) {
            if (dead() || SystemClock.elapsedRealtime() - begin > MAX_WAIT_MS) return null
            delay(POLL_MS)
            val now = SystemClock.elapsedRealtime()
            if (!BridgeConfirms.pendingFor(appId)) active += now - last
            last = now
            if (active >= CustomTools.CALL_TIMEOUT_MS) return null
        }
        return answer.await()
    }

    private fun filesStamp(app: InstalledWebApp, dir: File): Long {
        val tree = MergedAppTree(dir, OverlayStore.dir(context, app.id))
        var stamp = 0L
        for ((rel, file) in tree.files()) stamp = stamp * 31 + rel.hashCode() + file.lastModified() + file.length()
        return stamp
    }

    private suspend fun createSandbox(): JavaScriptSandbox =
        withContext(Dispatchers.Main) { JavaScriptSandbox.createConnectedInstanceAsync(context).await() }

    private fun isolateMessage(t: Throwable): String {
        val text = (t.message ?: t.javaClass.simpleName).replace('\n', ' ').take(300)
        return when (t.javaClass.simpleName) {
            "MemoryLimitExceededException" -> "isolate handler used more than 4 MB of memory"
            "EvaluationResultSizeLimitExceededException" -> "isolate handler returned more than ${IsolateScript.MAX_RESULT_BYTES / 1024} KB"
            else -> text
        }
    }

    companion object {
        private const val TAG = "ToolRunner"
        const val PORT = "deftTool"
        const val IDLE_MS = 60_000L
        private const val MAX_WAIT_MS = 150_000L
        private const val POLL_MS = 100L
        const val ISOLATE_UNSUPPORTED =
            "isolate tools are not supported on this device (no JavaScript isolate with a heap limit); the app's tool cannot run here"

        /** The isolate engine with a heap limit is present on this device. */
        fun isolateSupported(): Boolean = runCatching {
            JavaScriptSandbox.isSupported()
        }.getOrDefault(false)

        /** A JS string literal of [text] (JSON string syntax is valid JavaScript). */
        fun quote(text: String): String = JsonPrimitive(text).toString()
    }
}

/** Awaits a [com.google.common.util.concurrent.ListenableFuture] without blocking. */
private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (e: ExecutionException) {
                    cont.resumeWithException(e.cause ?: e)
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                }
            },
            Runnable::run,
        )
        cont.invokeOnCancellation { cancel(false) }
    }
