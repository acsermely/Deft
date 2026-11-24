package app.deft.assist

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import app.deft.roles.RoleSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * PARITY G11: Android starts a session when the user asks for the assistant. The session shows
 * nothing: it opens the app holding `role.assistant` with `assist.invoked {source: "assist-gesture"}` and hides. The
 * assist data Android may offer (the screen in front) is never read or passed on.
 */
class DeftAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = DeftAssistSession(this)
}

private class DeftAssistSession(context: Context) : VoiceInteractionSession(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        scope.launch {
            // Started while the session is showing: Android lets the bound assistant open an activity.
            runCatching { AssistLaunch.open(context, RoleSpec.ASSIST_SOURCE_GESTURE) }
            hide()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
