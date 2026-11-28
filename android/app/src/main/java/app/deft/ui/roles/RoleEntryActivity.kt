package app.deft.ui.roles

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.deft.DeftApp
import app.deft.assist.AssistLaunch
import app.deft.catalog.Capability
import app.deft.roles.RoleComponent
import app.deft.roles.RoleSpec
import app.deft.share.InboxDelivery
import app.deft.ui.host.WebViewHostActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// DEVICE-UNCHECKED (extra roles (browser, assistant, IME, wallpaper, device admin)): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: each role can be granted to one origin and reverted in system settings.
// Remove this note once checked.
/**
 * Entry point for the role components. Has no screen; each [RoleComponent] alias in
 * AndroidManifest.xml points here and is enabled only while a web app holds the role's grant.
 *
 * Browser role: a tapped http/https link goes to the one app holding `role.browser`, as data:
 * event `intents.viewReceived {type: "url", url, items: []}` (same delivery as "Open with", ),
 * then the app opens on its own page. The app's WebView is never navigated to the link; the
 * same-origin rule stays.
 *
 * Assistant role: on phones that start an activity for the assistant (ACTION_ASSIST) instead of
 * the voice interaction session, this opens the `role.assistant` holder the same way ([AssistLaunch]).
 */
class RoleEntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val component = RoleComponent.forClassName(intent.component?.className)
        when (component) {
            RoleComponent.BROWSER_VIEW -> openLink(intent.dataString)
            RoleComponent.ASSIST_ENTRY -> lifecycleScope.launch {
                AssistLaunch.open(this@RoleEntryActivity, RoleSpec.ASSIST_SOURCE_INTENT)
                finish()
            }
            else -> finish()
        }
    }

    private fun openLink(url: String?) {
        if (!RoleSpec.validBrowserUrl(url)) {
            Toast.makeText(this, "This link can't be opened here.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val cap = Capability.ROLE_BROWSER
        lifecycleScope.launch {
            val repo = DeftApp.instance.repository
            val holder = withContext(Dispatchers.IO) { cap.exclusiveGroup?.let { repo.specialHolder(it) } }
            if (holder == null) {
                Toast.makeText(this@RoleEntryActivity, "No app here opens web links.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            withContext(Dispatchers.IO) {
                // The link itself is not logged (user content); the row keeps the grant from looking unused.
                repo.log(holder, "inbound", cap.id, "intents.viewReceived", "ok")
            }
            InboxDelivery.emit(holder, "intents.viewReceived", RoleSpec.browserPayload(url!!))
            startActivity(WebViewHostActivity.intent(this@RoleEntryActivity, holder))
            finish()
        }
    }
}
