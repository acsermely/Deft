package app.deft.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class AgentDictation(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var started = false
    private var cancelled = false

    var onTranscript: (String) -> Unit = {}
    var onListening: (Boolean) -> Unit = {}
    var onError: (String) -> Unit = {}

    val listening: Boolean get() = started

    fun available(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return true
        }
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun start() {
        if (started) return
        if (!available()) {
            emitError("Speech recognition is not available on this device.")
            return
        }
        cancelled = false
        val engine = recognizer ?: createEngine() ?: return
        recognizer = engine
        started = true
        emitListening(true)
        runCatching {
            engine.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                },
            )
        }.onFailure {
            started = false
            emitListening(false)
            emitError("Could not start speech recognition.")
        }
    }

    fun stop() {
        if (!started) return
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        cancelled = true
        started = false
        runCatching { recognizer?.cancel() }
        emitListening(false)
    }

    fun destroy() {
        cancelled = true
        started = false
        runCatching { recognizer?.destroy() }
        recognizer = null
        emitListening(false)
    }

    private fun createEngine(): SpeechRecognizer? {
        val engine = runCatching {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrElse {
            emitError("Could not start speech recognition.")
            return null
        }
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                val ignore = cancelled
                started = false
                emitListening(false)
                runCatching { recognizer?.destroy() }
                recognizer = null
                if (!ignore) messageFor(error)?.let(::emitError)
            }

            override fun onResults(results: Bundle?) {
                if (cancelled) return
                started = false
                results.firstText()?.let { emitTranscript(it) }
                emitListening(false)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (cancelled) return
                partialResults.firstText()?.let { emitTranscript(it) }
            }
        })
        return engine
    }

    private fun emitTranscript(text: String) = post { onTranscript(text) }
    private fun emitListening(value: Boolean) = post { onListening(value) }
    private fun emitError(message: String) = post { onError(message) }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    companion object {
        private fun Bundle?.firstText(): String? {
            return this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        private fun messageFor(error: Int): String? = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> null
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required to dictate."
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            -> "Speech recognition needs a network on this device."
            SpeechRecognizer.ERROR_AUDIO -> "Could not access the microphone."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy."
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER,
            -> "Speech recognition failed."
            else -> "Speech recognition failed."
        }
    }
}
