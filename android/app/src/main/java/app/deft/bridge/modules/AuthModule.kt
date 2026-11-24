package app.deft.bridge.modules

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import app.deft.bridge.AuthNonces
import app.deft.bridge.AuthSpec
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgePicks
import app.deft.bridge.BridgeRequest
import app.deft.bridge.HostSurfaces
import app.deft.bridge.UserGesture
import app.deft.bridge.jsonArg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `auth.prepare` / `auth.start` (PARITY E3): OAuth sign-in in a Custom Tab. The app asks for a
 * nonce, builds its sign-in URL with the redirect `<scheme>:/cb/<nonce>`, then calls `auth.start`
 * while on screen. The redirect comes back through [app.deft.ui.auth.AuthRedirectActivity].
 */
class AuthModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = when (request.method) {
        "auth.prepare" -> prepare(request)
        "auth.start" -> start(request)
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
    }

    private fun prepare(request: BridgeRequest): BridgeOutcome {
        val nonce = AuthNonces.shared.issue(request.webAppId)
        val redirect = AuthSpec.redirectUri(AuthSpec.scheme(context.packageName), nonce)
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("nonce", JsonPrimitive(nonce))
                put("redirectUri", JsonPrimitive(redirect))
            },
        )
    }

    private suspend fun start(request: BridgeRequest): BridgeOutcome {
        val url = jsonArg(request.args, "url")
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "url required")
        val nonce = jsonArg(request.args, "nonce")
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "nonce required")
        AuthSpec.startUrlProblem(url)?.let { return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, it) }
        if (!AuthSpec.isNonce(nonce)) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "nonce is not valid")
        HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        if (!UserGesture.recent(request.webAppId)) {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "Sign-in needs a tap in the app first")
        }
        if (!AuthNonces.shared.start(nonce, request.webAppId)) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "Sign-in was not prepared, or it already ran or expired")
        }
        // Registered before the tab opens, so a fast redirect is not lost.
        val result = BridgePicks.registerAuth(nonce)
        try {
            val opened = withContext(Dispatchers.Main) { openTab(url) }
            if (!opened) return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "No browser can open the sign-in page")
            val redirect = withTimeoutOrNull(AuthNonces.TTL_MS) { result.await() }
                ?: return BridgeOutcome.Err(BridgeErrors.CANCELLED, "Sign-in was closed or timed out")
            return BridgeOutcome.Ok(buildJsonObject { put("url", JsonPrimitive(redirect)) })
        } finally {
            AuthNonces.shared.cancel(nonce)
            BridgePicks.completeAuth(nonce, null)
        }
    }

    private fun openTab(url: String): Boolean = try {
        val uri = Uri.parse(url)
        // Never the launcher itself: while it holds the browser role, an unpinned Custom Tab would
        // open in the role alias and hand app A's sign-in page to the browser holder (PARITY G11).
        val browser = browserPackage(uri)
        if (browser == null) {
            false
        } else {
            val intent: Intent = CustomTabsIntent.Builder().setShowTitle(true).build().intent
            intent.setData(uri)
            intent.setPackage(browser)
            // Started from the service, not an activity, so the tab needs its own task.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun browserPackage(uri: Uri): String? = runCatching {
        val pm = context.packageManager
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val default = pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val all = pm.queryIntentActivities(view, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        val tabs = (listOfNotNull(default) + all).distinct().filter { pkg ->
            pm.resolveService(Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).setPackage(pkg), 0) != null
        }.toSet()
        pickBrowser(default, all, tabs, context.packageName)
    }.getOrNull()

    companion object {
        /**
         * The browser a sign-in tab is pinned to: the default browser if it is a real one, else the
         * first browser with Custom Tabs, else any browser. Never [own] (the launcher's browser-role
         * alias) and never `android` (the "choose an app" screen when no default is set).
         */
        fun pickBrowser(default: String?, all: List<String>, customTabs: Set<String>, own: String): String? {
            val real = (listOfNotNull(default) + all).distinct().filter { it != own && it != "android" }
            return real.firstOrNull { it == default && it in customTabs }
                ?: real.firstOrNull { it in customTabs }
                ?: real.firstOrNull()
        }

        private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Gives a sign-in page this long to finish before the user's return counts as closing it. */
        private const val RETURN_GRACE_MS = 1_500L

        /**
         * Called when the launcher's own screen resumes. A redirect ends the sign-in in its own
         * activity and never resumes this one, so a resume while a sign-in is open means the user
         * came back without finishing it (closed the tab). Those sign-ins end as cancelled, after a
         * short grace so a redirect that is still being handed over is not lost. Only sign-ins of
         * [webAppId] that were started before this call are affected; other apps are not touched.
         */
        fun hostResumed(webAppId: String) {
            val open = AuthNonces.shared.startedNonces(webAppId)
            if (open.isEmpty()) return
            watchScope.launch {
                delay(RETURN_GRACE_MS)
                for (nonce in open) {
                    if (AuthNonces.shared.abandon(nonce)) BridgePicks.completeAuth(nonce, null)
                }
            }
        }
    }
}
