package app.deft.webview

import app.deft.workspace.MergedAppTree

import android.annotation.SuppressLint
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.webkit.WebView
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import app.deft.workspace.DeftPaths
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

object DeftLintEngine {
    private const val TIMEOUT_MS = 5_000L
    private const val IDLE_MS = 60_000L
    private const val ORIGIN = "https://lint.app.deft.internal/"
    private val TEXT = setOf("js", "html", "htm", "css", "json", "svg", "md", "txt")
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var catalogJson: String? = null
    private var view: WebView? = null
    private var ready: CompletableDeferred<Unit>? = null
    /** The in-flight [eval] call (main thread); resumed with null when the renderer dies. */
    private var pending: CancellableContinuation<String?>? = null
    private var usedAt = 0L

    fun collect(root: File): Map<String, String> = collect(MergedAppTree(root, null))

    /** Lint sees the merged app: overlay files win, deleted paths are absent. */
    fun collect(tree: MergedAppTree): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((rel, file) in tree.files()) {
            // A binary the lint rules need by path only (WebAssembly) is listed with empty text.
            if (DeftPaths.isWasmFileName(file.name)) {
                out[rel] = ""
                continue
            }
            if (file.extension.lowercase() !in TEXT) continue
            if (file.length() > DeftPaths.active.maxTextBytes) continue
            out[rel] = runCatching { file.readText() }.getOrDefault("")
        }
        return out
    }

    fun collectOne(root: File, relative: String): Map<String, String> = collectOne(MergedAppTree(root, null), relative)

    fun collectOne(tree: MergedAppTree, relative: String): Map<String, String> {
        val rel = relative.replace('\\', '/').trimStart('/')
        if (rel.isEmpty() || rel.contains("..")) return emptyMap()
        val file = tree.resolve(rel) ?: return emptyMap()
        if (DeftPaths.isWasmFileName(file.name)) return mapOf(rel to "")
        if (file.extension.lowercase() !in TEXT) return emptyMap()
        if (file.length() > DeftPaths.active.maxTextBytes) return emptyMap()
        return mapOf(rel to runCatching { file.readText() }.getOrDefault(""))
    }

    /** [packageBytes] is the package cap for this input; passes the larger cap for a granted app. */
    suspend fun run(
        context: Context,
        files: Map<String, String>,
        profile: String,
        packageBytes: Long = DeftPaths.active.maxPackageBytes,
    ): String =
        lock.withLock {
            val app = context.applicationContext
            val payload = JSONObject()
            payload.put("profile", profile)
            payload.put("files", JSONObject(files))
            catalog(app)?.let { payload.put("catalog", JSONObject(it)) }
            val limits = DeftPaths.active
            payload.put(
                "limits",
                JSONObject()
                    .put("textBytes", limits.maxTextBytes)
                    .put("maxFiles", limits.maxFiles)
                    .put("packageBytes", packageBytes),
            )
            val call = "(function(){try{return JSON.stringify(globalThis.deftLint.run($payload));}catch(e){return JSON.stringify([{id:'FL999',severity:'warning',path:'',line:1,col:1,message:String(e)}]);}})()"
            val raw = withContext(Dispatchers.Main) {
                // Timers are process-wide; the gate keeps them on while the page works.
                val page = ensure(app)
                if (page == null) return@withContext null
                val token = Any()
                try {
                    WebViewHostRuntime.ownerResumed(token, page)
                    usedAt = System.currentTimeMillis()
                    eval(page, call)
                } finally {
                    releaseOwner(token, page)
                }
            }
            unwrap(raw) ?: """[{"id":"FL999","severity":"warning","path":"","line":1,"col":1,"message":"lint timeout"}]"""
        }

    private fun catalog(context: Context): String? {
        catalogJson?.let { return it }
        val text = runCatching {
            context.assets.open("agent/bridge-catalog.json").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (text != null) catalogJson = text
        return text
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensure(app: Context): WebView? {
        val now = System.currentTimeMillis()
        val current = view
        if (current != null && now - usedAt > IDLE_MS) {
            current.destroy()
            view = null
            ready = null
        }
        view?.let { return it }
        val themed = ContextThemeWrapper(app, android.R.style.Theme)
        val script = app.assets.open("lint/deft-lint.js").bufferedReader().use { it.readText() }
        val html = "<!doctype html><meta charset=\"utf-8\"><script>$script</script>"
        val page = WebView(themed)
        page.settings.javaScriptEnabled = true
        page.settings.blockNetworkLoads = true
        page.settings.allowFileAccess = false
        page.settings.allowContentAccess = false
        page.onResume()
        page.layout(0, 0, 1, 1)
        page.measure(
            View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY),
        )
        val done = CompletableDeferred<Unit>()
        ready = done
        page.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (!done.isCompleted) done.complete(Unit)
            }

            // Drops the page so the next run builds a fresh one; any waiting call fails.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                RenderGone.log("DeftLintEngine", "lint page", detail)
                val current = this@DeftLintEngine.view === page
                if (current) {
                    this@DeftLintEngine.view = null
                    ready = null
                }
                // A stale page must not fail another page's eval.
                if (current) {
                    pending?.let { if (it.isActive) it.resume(null) }
                    pending = null
                    if (!done.isCompleted) done.complete(Unit)
                }
                RenderGone.dispose(page)
                return true
            }
        }
        view = page
        val token = Any()
        WebViewHostRuntime.ownerResumed(token, page)
        page.loadDataWithBaseURL(ORIGIN, html, "text/html", "utf-8", null)
        val loaded = try {
            withTimeoutOrNull(TIMEOUT_MS) { done.await() } != null
        } finally {
            releaseOwner(token, page)
        }
        // A renderer that died while loading already dropped the page (see onRenderProcessGone).
        if (view !== page) return null
        if (!loaded) {
            page.destroy()
            view = null
            ready = null
            return null
        }
        return page
    }

    /** Releases the gate token; the view may already be destroyed, so a failing timer call is ignored. */
    private fun releaseOwner(token: Any, page: WebView) {
        runCatching { WebViewHostRuntime.ownerPaused(token, page) }
    }

    private suspend fun eval(page: WebView, source: String): String? =
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                pending = cont
                cont.invokeOnCancellation { if (pending === cont) pending = null }
                page.evaluateJavascript(source) { raw ->
                    if (cont.isActive) cont.resume(raw)
                }
            }
        }

    private fun unwrap(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        return if (element is JsonPrimitive) element.content else element.toString()
    }

    suspend fun close() {
        lock.withLock {
            withContext(NonCancellable + Dispatchers.Main) {
                view?.destroy()
                view = null
                ready = null
            }
        }
    }
}
