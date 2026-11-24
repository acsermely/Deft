package app.deft.webview

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records four M0 assumptions on a real device. Results go in docs/spec/DECISIONS.md.
 *
 * 1. Host-injected scripts still run under CSP `script-src 'self'`.
 * 2. `https://x.app.deft.internal` is a secure context and interception sees fetch,
 *    module imports, workers and images.
 * 3. Whether `WebViewFeature.MULTI_PROFILE` is supported.
 * 4. Installed WebView major version.
 */
@RunWith(AndroidJUnit4::class)
class SpikeTest {
    @Test
    fun recordAssumptions() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pkg = WebViewCompat.getCurrentWebViewPackage(ctx)
        val versionName = pkg?.versionName ?: "unknown"
        val major = versionName.substringBefore('.').toIntOrNull() ?: -1
        val multiProfile = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        val webMessage = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

        val origin = "https://spike.app.deft.internal"
        val host = "spike.app.deft.internal"
        val seen = mutableSetOf<String>()
        val pageReady = CountDownLatch(1)
        val probe = AtomicReference("")

        val html = """
            <!doctype html>
            <html>
            <head><meta charset="utf-8"><title>spike</title></head>
            <body>
            <img src="./pixel.png" alt="">
            <script type="module" src="./mod.js"></script>
            </body>
            </html>
        """.trimIndent()

        val handler = object : WebViewAssetLoader.PathHandler {
            override fun handle(path: String): WebResourceResponse {
                val rel = path.trimStart('/')
                seen += rel.ifEmpty { "/" }
                val csp = mapOf(
                    "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; worker-src 'self';",
                    "X-Content-Type-Options" to "nosniff",
                )
                return when {
                    rel.isEmpty() || rel == "index.html" || rel == "/" ->
                        response("text/html", html, csp)
                    rel == "mod.js" ->
                        response(
                            "text/javascript",
                            """
                            window.__deftMod = true;
                            fetch('./data.json').catch(function(){});
                            try { new Worker('./worker.js'); } catch (e) { window.__deftWorkerErr = String(e && e.name); }
                            """.trimIndent(),
                            csp,
                        )
                    rel == "worker.js" ->
                        response("text/javascript", "self.postMessage('ok');", csp)
                    rel == "data.json" ->
                        response("application/json", "{}", csp)
                    rel == "pixel.png" ->
                        WebResourceResponse(
                            "image/png",
                            null,
                            200,
                            "OK",
                            csp,
                            ByteArrayInputStream(PNG_1X1),
                        )
                    else ->
                        WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            404,
                            "Not Found",
                            csp,
                            ByteArrayInputStream(ByteArray(0)),
                        )
                }
            }
        }
        val loader = WebViewAssetLoader.Builder()
            .setDomain(host)
            .setHttpAllowed(false)
            .addPathHandler("/", handler)
            .build()

        val main = Handler(Looper.getMainLooper())
        val webViewBox = AtomicReference<WebView>()
        val created = CountDownLatch(1)
        main.post {
            val wv = WebView(ctx)
            webViewBox.set(wv)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            if (docStart) {
                WebViewCompat.addDocumentStartJavaScript(
                    wv,
                    "window.__deftDocStart = true;",
                    setOf(origin),
                )
            }
            if (webMessage) {
                WebViewCompat.addWebMessageListener(
                    wv,
                    "jsBridge",
                    setOf(origin),
                    { _, _, _, _, _ -> },
                )
            }
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if (url.startsWith(origin)) {
                        seen += "req:" + (request.url.path ?: "/")
                    }
                    return loader.shouldInterceptRequest(request.url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    pageReady.countDown()
                }
            }
            created.countDown()
        }
        assertTrue("WebView create", created.await(10, TimeUnit.SECONDS))

        main.post { webViewBox.get().loadUrl("$origin/") }
        assertTrue("page load", pageReady.await(15, TimeUnit.SECONDS))
        Thread.sleep(1_500)

        val evalDone = CountDownLatch(1)
        main.post {
            webViewBox.get().evaluateJavascript(
                """
                (function(){
                  return JSON.stringify({
                    docStart: !!window.__deftDocStart,
                    evalJs: true,
                    isSecureContext: !!window.isSecureContext,
                    hasJsBridge: typeof jsBridge !== 'undefined',
                    mod: !!window.__deftMod,
                    workerErr: window.__deftWorkerErr || ''
                  });
                })()
                """.trimIndent(),
            ) { value ->
                probe.set(value ?: "")
                evalDone.countDown()
            }
        }
        assertTrue("evaluateJavascript", evalDone.await(10, TimeUnit.SECONDS))
        main.post { webViewBox.get().destroy() }

        val parsed = decodeProbe(probe.get())
        val cspOk = parsed.optBoolean("docStart") &&
            parsed.optBoolean("evalJs") &&
            parsed.optBoolean("hasJsBridge")
        val secure = parsed.optBoolean("isSecureContext")
        val sawFetch = seen.any { it.contains("data.json") }
        val sawMod = seen.any { it.contains("mod.js") }
        val sawWorker = seen.any { it.contains("worker.js") }
        val sawImg = seen.any { it.contains("pixel.png") }

        val report = buildString {
            appendLine("SPIKE WebView versionName=$versionName major=$major")
            appendLine("SPIKE MULTI_PROFILE=$multiProfile DOCUMENT_START_SCRIPT=$docStart WEB_MESSAGE_LISTENER=$webMessage")
            appendLine("SPIKE assumption1_csp_inject=${if (cspOk) "PASS" else "FAIL"} probe=${probe.get()}")
            appendLine("SPIKE assumption2_secure=$secure fetch=$sawFetch module=$sawMod worker=$sawWorker image=$sawImg seen=$seen")
        }
        android.util.Log.i(TAG, report)
        println(report)

        assertTrue("WebView major should parse, got $versionName", major > 0)
        assertTrue("DOCUMENT_START_SCRIPT and WEB_MESSAGE_LISTENER are required", docStart && webMessage)
        // Assumptions 1–3 are recorded, not hard-gated: a FAIL here is written to DECISIONS.md
        // and changes PR5/PR6, it must not block the rest of the suite.
    }

    private fun decodeProbe(raw: String): JSONObject {
        val first = runCatching { JSONTokener(raw).nextValue() }.getOrNull() ?: return JSONObject()
        val second = if (first is String) {
            runCatching { JSONTokener(first).nextValue() }.getOrNull() ?: first
        } else {
            first
        }
        return second as? JSONObject ?: JSONObject()
    }

    private fun response(mime: String, body: String, headers: Map<String, String>): WebResourceResponse =
        WebResourceResponse(
            mime,
            "utf-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
        )

    companion object {
        private const val TAG = "DeftSpike"
        private val PNG_1X1 = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
            0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00, 0x00, 0x00, 0x00,
            0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
