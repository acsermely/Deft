package app.deft.assist

import android.service.voice.VoiceInteractionService

// DEVICE-UNCHECKED (extra roles (browser, assistant, IME, wallpaper, device admin)): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: each role can be granted to one origin and reverted in system settings.
// Remove this note once checked.
/**
 * The launcher's assistant service, needed for Android's assistant role. It does
 * nothing on its own (no hotword, no listening); the work is in [DeftAssistSessionService].
 * Disabled in the manifest until a web app holds `role.assistant` (RoleComponents).
 */
class DeftVoiceInteractionService : VoiceInteractionService()
