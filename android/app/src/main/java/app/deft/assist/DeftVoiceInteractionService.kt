package app.deft.assist

import android.service.voice.VoiceInteractionService

/**
 * PARITY G11: the launcher's assistant service, needed for Android's assistant role. It does
 * nothing on its own (no hotword, no listening); the work is in [DeftAssistSessionService].
 * Disabled in the manifest until a web app holds `role.assistant` (RoleComponents).
 */
class DeftVoiceInteractionService : VoiceInteractionService()
