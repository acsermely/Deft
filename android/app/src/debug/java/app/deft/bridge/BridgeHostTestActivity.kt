package app.deft.bridge

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import app.deft.catalog.Capability
import app.deft.data.InstalledWebApp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BridgeHostTestActivity : ComponentActivity() {
    lateinit var webView: WebView
        private set
    var bridge: DeftJsBridge? = null
        private set
    private var documentStart: androidx.webkit.ScriptHandler? = null
    private var bound = false
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridge?.service = (binder as BridgeService.LocalBinder).service()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bridge?.service = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeftJsBridge.webViewSupported()) {
            finish()
            return
        }
        val origin = intent.getStringExtra(EXTRA_ORIGIN) ?: DEFAULT_ORIGIN
        val listenerOrigin = intent.getStringExtra(EXTRA_LISTENER_ORIGIN) ?: origin
        val allowed = intent.getStringExtra(EXTRA_ALLOWED)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: setOf(Capability.CONTACTS_READ.id)
        val delayMs = intent.getLongExtra(EXTRA_DELAY_READY_MS, 0L)
        val host = Uri.parse(origin).host ?: "bridge-test.local"
        val dummy = InstalledWebApp(
            id = "bridge-test",
            name = "bridge-test",
            startUrl = "$origin/",
            origin = origin,
            manifestUrl = "$origin/manifest.json",
            requiredCapabilities = listOf(Capability.CONTACTS_READ),
            filesAllAccess = false,
            filesTreeUri = null,
            confirmBiometric = false,
            workspaceSlug = null,
        )
        val view = WebView(this)
        webView = view
        setContentView(view)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        val loader = WebViewAssetLoader.Builder()
            .setDomain(host)
            .setHttpAllowed(origin.startsWith("http://"))
            .addPathHandler(
                "/",
                WebViewAssetLoader.PathHandler { path ->
                    val rel = if (path.isEmpty() || path.endsWith("/")) "index.html" else path.trimStart('/')
                    try {
                        val mime = if (rel.endsWith(".js")) "text/javascript" else "text/html"
                        WebResourceResponse(mime, "utf-8", assets.open("bridge-test/$rel"))
                    } catch (_: Exception) {
                        null
                    }
                },
            )
            .build()
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url)
            }
        }
        val b = DeftJsBridge(dummy, allowed, lifecycleScope, listenerOrigin)
        if (delayMs > 0) b.holdReady = true
        bridge = b
        if (intent.getBooleanExtra(EXTRA_BIND_SERVICE, false)) {
            BridgeService.start(this)
            bound = bindService(Intent(this, BridgeService::class.java), conn, BIND_AUTO_CREATE)
        }
        b.attach(view)
        val js = DeftJsBridge.clientJs(this)
        documentStart = WebViewCompat.addDocumentStartJavaScript(view, js, setOf(listenerOrigin))
        if (delayMs > 0) {
            // Background sleep so Espresso does not drain the delay during ActivityScenario.launch.
            Thread {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                view.post { b.releaseReady() }
            }.apply { isDaemon = true; start() }
        }
        view.loadUrl("$origin/")
    }

    fun evalJs(script: String, timeoutMs: Long = 3_000): String {
        val latch = CountDownLatch(1)
        var out = "null"
        webView.post {
            webView.evaluateJavascript(script) { value ->
                out = value ?: "null"
                latch.countDown()
            }
        }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "evaluateJavascript timed out" }
        return out
    }

    override fun onDestroy() {
        if (bound) runCatching { unbindService(conn) }
        bound = false
        runCatching { documentStart?.remove() }
        bridge?.detach(if (::webView.isInitialized) webView else null)
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_LISTENER_ORIGIN = "listenerOrigin"
        const val EXTRA_ALLOWED = "allowed"
        const val EXTRA_DELAY_READY_MS = "delayReadyMs"
        const val EXTRA_BIND_SERVICE = "bindService"
        const val DEFAULT_ORIGIN = "https://bridge-test.local"
    }
}
