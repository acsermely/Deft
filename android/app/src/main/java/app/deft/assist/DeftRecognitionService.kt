package app.deft.assist

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import app.deft.roles.RoleSpec

/**
 * PARITY G11: Android requires an assistant to name a speech recognizer, and while the launcher is
 * the assistant it makes that recognizer the phone's default. So that speech to text keeps working
 * for every app, this one only hands each request to another installed recognizer
 * ([RoleSpec.pickRecognizer]) and passes its results back. It never keeps or reads the audio or text.
 * Disabled in the manifest until a web app holds `role.assistant` (RoleComponents).
 */
class DeftRecognitionService : RecognitionService() {
    private var inner: SpeechRecognizer? = null

    private fun target(): ComponentName? {
        val found = runCatching {
            packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo }
                .map { ComponentName(it.packageName, it.name).flattenToString() }
        }.getOrDefault(emptyList())
        return RoleSpec.pickRecognizer(found, packageName)?.let { ComponentName.unflattenFromString(it) }
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        release()
        val target = target()
        if (target == null) {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(this, target) }.getOrNull()
        if (recognizer == null) {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        inner = recognizer
        recognizer.setRecognitionListener(Forward(listener))
        recognizer.startListening(recognizerIntent)
    }

    override fun onStopListening(listener: Callback) {
        inner?.stopListening()
    }

    override fun onCancel(listener: Callback) {
        release()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        inner?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        inner = null
    }

    /** Passes every result back; a caller that went away (RemoteException) is ignored. */
    private class Forward(private val to: Callback) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { runCatching { to.readyForSpeech(params ?: Bundle()) } }
        override fun onBeginningOfSpeech() { runCatching { to.beginningOfSpeech() } }
        override fun onRmsChanged(rmsdB: Float) { runCatching { to.rmsChanged(rmsdB) } }
        override fun onBufferReceived(buffer: ByteArray?) { runCatching { to.bufferReceived(buffer ?: ByteArray(0)) } }
        override fun onEndOfSpeech() { runCatching { to.endOfSpeech() } }
        override fun onError(error: Int) { runCatching { to.error(error) } }
        override fun onResults(results: Bundle?) { runCatching { to.results(results ?: Bundle()) } }
        override fun onPartialResults(partialResults: Bundle?) { runCatching { to.partialResults(partialResults ?: Bundle()) } }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
