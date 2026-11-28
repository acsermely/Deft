package app.deft.webview

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.deft.data.canonicalOrigin

object DeftNavigation {
    sealed class Decision {
        data object Stay : Decision()
        data class OtherApp(val origin: String) : Decision()
        data class Chooser(val url: String) : Decision()
        data class ExternalHttps(val url: String) : Decision()
        data object Drop : Decision()
    }

    fun decide(url: String, appOrigin: String): Decision {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase().orEmpty()
        val origin = canonicalOrigin(url)
        if (origin == canonicalOrigin(appOrigin)) return Decision.Stay
        return when (scheme) {
            "tel", "mailto", "sms", "geo" -> Decision.Chooser(url)
            "https" -> if (isAppHost(uri.host)) Decision.OtherApp(origin) else Decision.ExternalHttps(url)
            else -> Decision.Drop
        }
    }

    /**
     * In an app with approved embed origins, an `https:` navigation inside a frame
     * (a payment form, its 3-D Secure challenge) is left to the CSP: the app document's
     * `frame-src` lists only approved origins, and frames nested deeper follow their own site's
     * policy. Top-level navigations never pass here and keep [decide].
     */
    fun allowSubframe(url: String, isForMainFrame: Boolean, embedsActive: Boolean): Boolean =
        !isForMainFrame && embedsActive && url.startsWith("https://", ignoreCase = true)

    /** Launcher-served app origins never resolve on the network, so they must not open in a browser. */
    private fun isAppHost(host: String?): Boolean =
        host != null && host.lowercase().endsWith(APP_HOST_SUFFIX)

    private const val APP_HOST_SUFFIX = ".app.deft.internal"

    fun openChooser(context: Context, url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chooser = Intent.createChooser(view, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Never back into the launcher's own browser-role entry.
        context.startActivity(app.deft.roles.RoleComponents.excludeSelf(context, chooser))
    }

    fun openHttps(context: Context, url: String) {
        val uri = Uri.parse(url)
        // While the launcher is the default browser (role.browser), a Custom Tab or a
        // plain VIEW would come straight back to the holder app; let the user pick a real browser.
        if (app.deft.roles.RoleComponents.resolvesToSelf(context, Intent(Intent.ACTION_VIEW, uri))) {
            openChooser(context, url)
            return
        }
        val launched = runCatching {
            val builderClass = Class.forName("androidx.browser.customtabs.CustomTabsIntent\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            val intentObj = builderClass.getMethod("build").invoke(builder)
            intentObj.javaClass.getMethod("launchUrl", Context::class.java, Uri::class.java)
                .invoke(intentObj, context, uri)
            true
        }.getOrDefault(false)
        if (!launched) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
