package app.deft.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.util.Log
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.deft.catalog.BridgeCatalog

object DeftWebViewConfig {
    const val MIN_WEBVIEW_MAJOR = BridgeCatalog.MIN_WEBVIEW_MAJOR

    fun webViewMajor(context: Context): Int {
        val pkg = WebViewCompat.getCurrentWebViewPackage(context) ?: return -1
        return pkg.versionName?.substringBefore('.')?.toIntOrNull() ?: -1
    }

    fun isTooOld(context: Context): Boolean {
        if (!DeftJsBridgeCompat.supported()) return true
        val major = webViewMajor(context)
        return major in 1 until MIN_WEBVIEW_MAJOR
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun apply(view: WebView, geolocation: Boolean, profileName: String? = null) {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.setSupportMultipleWindows(false)
        view.settings.javaScriptCanOpenWindowsAutomatically = false
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        view.settings.setGeolocationEnabled(geolocation)
        view.settings.mediaPlaybackRequiresUserGesture = true
        view.settings.textZoom = (view.resources.configuration.fontScale * 100).toInt().coerceAtLeast(1)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            androidx.webkit.WebSettingsCompat.setSafeBrowsingEnabled(view.settings, false)
        }
        view.setDownloadListener { _, _, _, _, _ -> }
        blockServiceWorkers(android.webkit.ServiceWorkerController.getInstance())
        applyProfile(view, profileName)
    }

    // DEVICE-UNCHECKED (Web Payment Request): built, but the developer has not checked it on
    // a real device yet. Check: a sample pays through a test payment app on the device.
    // Remove this note once checked.
    /** Payment Request reaches installed payment apps only with the payments.request grant. */
    fun applyPaymentRequest(view: WebView, granted: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PAYMENT_REQUEST)) return
        androidx.webkit.WebSettingsCompat.setPaymentRequestEnabled(view.settings, granted)
    }

    fun profileName(originId: String?, origin: String): String {
        val raw = originId?.takeIf { it.isNotBlank() }
            ?: origin.removePrefix("https://").substringBefore('.')
        return raw.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "app" }
    }

    /** Drops a deleted app's cookies, storage and cache. Fails quietly if a view still uses it. */
    fun deleteProfile(profileName: String) {
        if (profileName == androidx.webkit.Profile.DEFAULT_PROFILE_NAME) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { ProfileStore.getInstance().deleteProfile(profileName) }
                .onFailure { Log.w(TAG, "profile $profileName not deleted", it) }
        }
    }

    private fun applyProfile(view: WebView, profileName: String?) {
        val name = profileName?.takeIf { it.isNotBlank() } ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        runCatching {
            val profile = ProfileStore.getInstance().getOrCreateProfile(name)
            blockServiceWorkers(profile.serviceWorkerController)
            WebViewCompat.setProfile(view, name)
        }.onFailure { Log.w(TAG, "profile $name not applied", it) }
    }

    private fun blockServiceWorkers(controller: android.webkit.ServiceWorkerController) {
        runCatching {
            controller.setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                override fun shouldInterceptRequest(
                    request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse =
                    android.webkit.WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        403,
                        "Forbidden",
                        emptyMap(),
                        java.io.ByteArrayInputStream(ByteArray(0)),
                    )
            })
        }
    }

    fun blockingChrome(onDialog: ((String) -> Unit)? = null): WebChromeClient =
        object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                onDialog?.invoke("alert")
                result?.cancel()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                onDialog?.invoke("confirm")
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: android.webkit.JsPromptResult?,
            ): Boolean {
                onDialog?.invoke("prompt")
                result?.cancel()
                return true
            }
        }

    private const val TAG = "DeftWebView"
}

object DeftJsBridgeCompat {
    fun supported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
}
