package app.deft.ui.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import app.deft.bridge.AuthNonces
import app.deft.bridge.AuthSpec
import app.deft.bridge.BridgePicks
import app.deft.ui.host.WebViewHostActivity

/**
 * Receives the sign-in redirect `<scheme>:/cb/<nonce>` (PARITY E3). It has no screen: it hands the
 * full URL to the app that started the sign-in, brings that app back in front of the Custom Tab,
 * then finishes. An unknown, used or expired nonce
 * is dropped without a word. The URL carries the sign-in code, so it is never logged.
 */
class AuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data: Uri? = intent?.data
        if (data != null && data.scheme == AuthSpec.scheme(packageName)) {
            val nonce = AuthSpec.nonceFromPath(data.path)
            val url = data.toString()
            val webAppId = if (nonce != null && url.length <= AuthSpec.MAX_URL) AuthNonces.shared.complete(nonce) else null
            if (nonce != null && webAppId != null) {
                BridgePicks.completeAuth(nonce, url)
                // The Custom Tab would otherwise stay on top of the app.
                startActivity(WebViewHostActivity.intent(this, webAppId).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
        finish()
    }
}
