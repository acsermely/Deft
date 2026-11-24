package app.deft.assist

import android.content.Context
import android.widget.Toast
import app.deft.DeftApp
import app.deft.catalog.Capability
import app.deft.roles.RoleSpec
import app.deft.share.InboxDelivery
import app.deft.ui.host.WebViewHostActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PARITY G11: what the assistant does when the user asks for it (long-press Home, the assist
 * gesture or button): open the one app holding `role.assistant` and tell it `assist.invoked
 * {source}`. The app gets no screen content and no voice. Shared by the voice interaction session
 * (`assist-gesture`, only the system can start it) and the exported ACTION_ASSIST entry (`intent`:
 * an activity cannot tell who started it, so any app could).
 */
object AssistLaunch {
    /** False (with a short message) when no app holds the role any more. */
    suspend fun open(context: Context, source: String): Boolean {
        val cap = Capability.ROLE_ASSISTANT
        val repo = DeftApp.instance.repository
        val holder = withContext(Dispatchers.IO) {
            cap.exclusiveGroup?.let { repo.specialHolder(it) }
                ?.also { repo.log(it, "inbound", cap.id, RoleSpec.ASSIST_EVENT, "ok") }
        }
        if (holder == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "No app here is set up as the assistant.", Toast.LENGTH_SHORT).show()
            }
            return false
        }
        InboxDelivery.emit(holder, RoleSpec.ASSIST_EVENT, RoleSpec.assistPayload(source))
        withContext(Dispatchers.Main) {
            runCatching { context.startActivity(WebViewHostActivity.intent(context, holder)) }
        }
        return true
    }
}
