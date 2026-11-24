package app.deft.bridge

import android.content.Context
import app.deft.DeftApp
import app.deft.bridge.modules.AudioRoute
import app.deft.bridge.modules.ClipboardModule
import app.deft.bridge.modules.SensorsModule
import app.deft.bridge.modules.SpeechModule
import app.deft.bridge.modules.TorchState
import app.deft.catalog.Capability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Review: live listeners that run while the app is on screen end as soon as the grant they need
 * is gone, not only when the app leaves the screen. Called from every revoke path and on uninstall.
 */
object RevokeStops {
    enum class Live { CLIPBOARD_WATCH, SENSORS, SPEECH, TORCH, AUDIO_ROUTE }

    /** The listeners to stop for an app that now holds only [granted]. */
    fun toStop(granted: Set<Capability>): Set<Live> = buildSet {
        if (Capability.CLIPBOARD_WATCH !in granted) add(Live.CLIPBOARD_WATCH)
        if (Capability.SENSORS !in granted) add(Live.SENSORS)
        // Listening needs both the speech grant and the microphone grant.
        if (Capability.SPEECH_RECOGNIZE !in granted || Capability.MICROPHONE !in granted) add(Live.SPEECH)
        if (Capability.TORCH !in granted) add(Live.TORCH)
        if (Capability.AUDIO_CONTROL !in granted) add(Live.AUDIO_ROUTE)
    }

    /** Stops [live] for the app. Never fails the caller. */
    suspend fun stop(context: Context, webAppId: String, live: Set<Live>) {
        if (live.isEmpty()) return
        if (Live.TORCH in live) runCatching { TorchState.offFor(webAppId, context) }
        if (Live.AUDIO_ROUTE in live) runCatching { AudioRoute.resetFor(webAppId, context) }
        if (Live.SENSORS in live) {
            runCatching { SensorsModule.stopFor(webAppId) }
            // The shared step listener runs only while some app holds `sensors`.
            runCatching { StepStore.get(context).stopIfUnheld(DeftApp.instance.repository) }
        }
        // The clipboard listeners and the recognizers are main-thread only.
        runCatching {
            withContext(Dispatchers.Main) {
                if (Live.CLIPBOARD_WATCH in live) {
                    ClipboardModule.stopFor(webAppId)
                    ClipboardModule.stopIme(webAppId)
                }
                if (Live.SPEECH in live) SpeechModule.stopListeningFor(webAppId)
            }
        }
    }

    suspend fun recheck(webAppId: String) {
        val repo = DeftApp.instance.repository
        stop(DeftApp.instance, webAppId, toStop(repo.grantedCapabilities(webAppId).toSet()))
    }
}
