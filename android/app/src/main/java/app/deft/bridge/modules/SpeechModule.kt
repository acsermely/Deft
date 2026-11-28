package app.deft.bridge.modules

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import app.deft.DeftApp
import app.deft.bridge.OsPermissions
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.BridgeService
import app.deft.bridge.HostSurfaces
import app.deft.bridge.SpeechSpec
import app.deft.bridge.UserGesture
import app.deft.bridge.jsonArg
import app.deft.catalog.Capability
import app.deft.catalog.isGranted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// DEVICE-UNCHECKED (speech recognition and TTS): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: voice to text works (offline where supported) and tts.speak is audible.
// Remove this note once checked.
/** One utterance: the app's own id for it, and the app it belongs to. */
private class Utterance(val webAppId: String, val id: String)

private class Engine(val tts: TextToSpeech, val ready: CompletableDeferred<Boolean>)

/** One recognition in progress for an app. Its result completes when the recognizer ends. */
private class Listening(val recognizer: SpeechRecognizer, val result: CompletableDeferred<BridgeOutcome>)

/**
 * `speech.*`: text to speech with the phone's voice, and one-shot speech to text.
 * Listening needs the app on screen and a genuine tap in it, and stops when the app leaves the screen.
 */
class SpeechModule(private val context: Context) : BridgeModule {
    // The engine and the recognizers are only touched on the main thread.
    private var engine: Engine? = null
    private val utterances = ConcurrentHashMap<String, Utterance>()

    /** The utterance being spoken now, from the engine's progress reports. */
    @Volatile
    private var current: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = when (request.method) {
        "speech.speak" -> speak(request)
        "speech.stop" -> stop(request.webAppId)
        "speech.voices" -> voices()
        "speech.recognize" -> recognize(request)
        "speech.cancel" -> cancel(request.webAppId)
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
    }

    private suspend fun speak(request: BridgeRequest): BridgeOutcome {
        val args = request.args
        val text = jsonArg(args, "text")?.takeIf { it.isNotBlank() }
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "text must not be empty")
        val lang = jsonArg(args, "lang")
        if (lang != null && !SpeechSpec.isLangTag(lang)) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "lang must look like en-US")
        }
        val rate = SpeechSpec.rateOrPitch(jsonArg(args, "rate"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "rate must be a number")
        val pitch = SpeechSpec.rateOrPitch(jsonArg(args, "pitch"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "pitch must be a number")
        val id = jsonArg(args, "id") ?: UUID.randomUUID().toString()
        val tts = engine() ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, TTS_MISSING)
        val utteranceId = UUID.randomUUID().toString()
        return withContext(Dispatchers.Main) {
            val status = tts.setLanguage(lang?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault())
            if (lang != null && status < TextToSpeech.LANG_AVAILABLE) {
                return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The $lang voice is not installed on this phone")
            }
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)
            // Review: another app's speech is never cut off; the caller's own is replaced quietly.
            val flush = SpeechSpec.flushes(request.webAppId, utterances.values.map { it.webAppId })
            if (flush) dropOwn(request.webAppId)
            utterances[utteranceId] = Utterance(request.webAppId, id)
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(text, mode, null, utteranceId) != TextToSpeech.SUCCESS) {
                utterances.remove(utteranceId)
                return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Speech could not start")
            }
            BridgeOutcome.Ok(buildJsonObject { put("id", JsonPrimitive(id)) })
        }
    }

    private suspend fun stop(webAppId: String): BridgeOutcome {
        withContext(Dispatchers.Main) {
            val owner = current?.let { utterances[it]?.webAppId }
            if (SpeechSpec.mayStop(webAppId, owner, utterances.values.map { it.webAppId })) {
                // The caller's own utterances end quietly; another app's queued ones hear `interrupted`.
                dropOwn(webAppId)
                engine?.tts?.stop()
            }
        }
        return BridgeOutcome.Ok()
    }

    private fun dropOwn(webAppId: String) {
        utterances.entries.removeIf { it.value.webAppId == webAppId }
    }

    private suspend fun voices(): BridgeOutcome {
        val tts = engine() ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, TTS_MISSING)
        val items: List<JsonObject> = withContext(Dispatchers.Main) {
            tts.voices.orEmpty().sortedBy { it.name }.map { voice ->
                buildJsonObject {
                    put("name", JsonPrimitive(voice.name))
                    put("lang", JsonPrimitive(voice.locale.toLanguageTag()))
                    put("offline", JsonPrimitive(!voice.isNetworkConnectionRequired))
                }
            }
        }
        return BridgeOutcome.Ok(JsonArray(items))
    }

    private suspend fun recognize(request: BridgeRequest): BridgeOutcome {
        val webAppId = request.webAppId
        HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        val lang = jsonArg(request.args, "lang")
        if (lang != null && !SpeechSpec.isLangTag(lang)) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "lang must look like en-US")
        }
        val offline = jsonArg(request.args, "offline") == "true"
        if (!DeftApp.instance.repository.grantLevel(webAppId, Capability.MICROPHONE).isGranted()) {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "microphone")
        }
        // The tap comes first: the microphone dialog may only follow a tap, and must not run out the tap's window.
        if (!UserGesture.recent(webAppId)) {
            return BridgeOutcome.Err(BridgeErrors.CANCELLED, "Speech to text needs a tap in the app first")
        }
        OsPermissions.ensure(context, webAppId, request.background, listOf(Manifest.permission.RECORD_AUDIO), "Allow the microphone")?.let { return it }
        val onDevice = offline && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Speech to text is not available on this phone")
        }
        val entry = withContext(Dispatchers.Main) {
            if (listening.containsKey(webAppId)) return@withContext null
            val recognizer = if (onDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            val entry = Listening(recognizer, CompletableDeferred())
            listening[webAppId] = entry
            recognizer.setRecognitionListener(listener(webAppId, entry))
            recognizer.startListening(recognizerIntent(lang, offline))
            entry
        } ?: return BridgeOutcome.Err(BridgeErrors.IN_USE, "Already listening")
        try {
            // Review: a recognizer that never calls back must not hold the call forever.
            return withTimeoutOrNull(RECOGNIZE_TIMEOUT_MS) { entry.result.await() } ?: run {
                withContext(Dispatchers.Main) { runCatching { entry.recognizer.cancel() } }
                BridgeOutcome.Err(BridgeErrors.CANCELLED, "No speech result")
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                if (listening[webAppId] === entry) listening.remove(webAppId)
                runCatching { entry.recognizer.destroy() }
            }
        }
    }

    private suspend fun cancel(webAppId: String): BridgeOutcome {
        withContext(Dispatchers.Main) {
            listening.remove(webAppId)?.let { entry ->
                entry.recognizer.cancel()
                entry.result.complete(BridgeOutcome.Err(BridgeErrors.CANCELLED, "Listening was cancelled"))
            }
        }
        return BridgeOutcome.Ok()
    }

    /** The shared engine, started on first use. Null when the phone has no text to speech. */
    private suspend fun engine(): TextToSpeech? {
        val current = withContext(Dispatchers.Main) {
            engine ?: run {
                val ready = CompletableDeferred<Boolean>()
                val tts = TextToSpeech(context.applicationContext) { status ->
                    ready.complete(status == TextToSpeech.SUCCESS)
                }
                tts.setOnUtteranceProgressListener(progress)
                Engine(tts, ready).also { engine = it }
            }
        }
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { current.ready.await() } == true) return current.tts
        withContext(Dispatchers.Main) {
            if (engine === current) engine = null
            runCatching { current.tts.shutdown() }
        }
        return null
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            current = utteranceId
        }

        override fun onDone(utteranceId: String?) = finish(utteranceId, null)
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) = finish(utteranceId, "error")
        override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, "error$errorCode")
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // Still listed means it was not the caller's own: another app stopped the voice.
            finish(utteranceId, "interrupted")
        }
    }

    private fun finish(utteranceId: String?, error: String?) {
        if (utteranceId != null && current == utteranceId) current = null
        val done = utterances.remove(utteranceId ?: return) ?: return
        val payload = buildJsonObject {
            put("id", JsonPrimitive(done.id))
            if (error != null) put("code", JsonPrimitive(error))
        }.toString()
        val event = if (error == null) "speech.done" else "speech.error"
        scope.launch { BridgeService.instanceOrNull()?.emit(done.webAppId, event, payload) }
    }

    private fun listener(webAppId: String, entry: Listening) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults) ?: return
            val payload = buildJsonObject { put("text", JsonPrimitive(text)) }.toString()
            scope.launch { BridgeService.instanceOrNull()?.emit(webAppId, "speech.partial", payload) }
        }

        override fun onResults(results: Bundle?) {
            val alternatives = SpeechSpec.alternatives(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            entry.result.complete(BridgeOutcome.Ok(resultJson(firstResult(results).orEmpty(), alternatives)))
        }

        override fun onError(error: Int) {
            entry.result.complete(errorOutcome(error))
        }
    }

    private fun recognizerIntent(lang: String?, offline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang ?: Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
        }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun resultJson(text: String, alternatives: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("text", JsonPrimitive(text))
        put("alternatives", buildJsonArray { alternatives.forEach { add(JsonPrimitive(it)) } })
    }

    private fun errorOutcome(code: Int): BridgeOutcome = when (code) {
        // Silence is not a failure: the app gets an empty result, as with a quiet microphone.
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> BridgeOutcome.Ok(resultJson(""))
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Allow the microphone")
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> BridgeOutcome.Err(BridgeErrors.IN_USE, "Speech to text is busy")
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Speech to text failed (error $code)")
    }

    /** Called from [BridgeService.onDestroy]: stop speaking, end listening and release the engine. */
    fun shutdown() {
        utterances.clear()
        for (entry in listening.values) {
            entry.recognizer.cancel()
            entry.result.complete(BridgeOutcome.Err(BridgeErrors.CANCELLED, "The launcher stopped listening"))
        }
        listening.clear()
        engine?.let { runCatching { it.tts.shutdown() } }
        engine = null
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 10_000L
        private const val RECOGNIZE_TIMEOUT_MS = 60_000L
        private const val TTS_MISSING = "Text to speech is not available on this phone"

        // Main thread only.
        private val listening = HashMap<String, Listening>()

        /** Leaving the app's screen ends its listening. Called from the host activity's onStop. */
        fun stopListeningFor(webAppId: String) {
            val entry = listening.remove(webAppId) ?: return
            entry.recognizer.cancel()
            entry.result.complete(BridgeOutcome.Err(BridgeErrors.CANCELLED, "Listening stopped when the app left the screen"))
        }
    }
}
