package app.deft.bridge.modules

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import app.deft.DeftApp
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.BridgeService
import app.deft.bridge.HostSurfaces
import app.deft.bridge.UserGesture
import app.deft.catalog.Capability
import app.deft.catalog.isGranted
import app.deft.ime.DeftInputMethodService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * `clipboard.*`. `readText` (PARITY A7, `navigator.clipboard.readText`) works only within a few
 * seconds of a genuine tap in the app, so a page cannot poll the clipboard in the background.
 * `watchStart` / `watchStop` (PARITY G12) report each new copy while the app is on screen.
 *
 * IME mode (PARITY G12 with G11 `ime`): Android 10+ also lets the phone's chosen keyboard read the
 * clipboard. When the app holds `ime` and the launcher's keyboard is the chosen one, its keyboard
 * page may call `watchStart` too: each new copy then goes to that page only, while it listens,
 * never queued, and only while `clipboard.watch` is still granted. It ends with `watchStop` from
 * the page, when the keyboard page is closed, or when the launcher's keyboard is no longer chosen.
 */
class ClipboardModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = when (request.method) {
        "clipboard.readText" -> readText(request)
        "clipboard.watchStart" -> watchStart(request)
        "clipboard.watchStop" -> {
            withContext(Dispatchers.Main) {
                if (request.runCapability == Capability.IME) stopIme(request.webAppId) else stopFor(request.webAppId)
            }
            BridgeOutcome.Ok()
        }
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
    }

    private suspend fun readText(request: BridgeRequest): BridgeOutcome {
        if (!UserGesture.recent(request.webAppId)) {
            return BridgeOutcome.Err(BridgeErrors.CANCELLED, "Clipboard reads need a tap in the app first")
        }
        val text = withContext(Dispatchers.Main) {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        }
        return BridgeOutcome.Ok(buildJsonObject { put("text", JsonPrimitive(text.orEmpty())) })
    }

    private suspend fun watchStart(request: BridgeRequest): BridgeOutcome {
        if (request.runCapability == Capability.IME) return watchStartIme(request.webAppId)
        HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        val webAppId = request.webAppId
        val started = withContext(Dispatchers.Main) {
            val cm = context.getSystemService(ClipboardManager::class.java)
                ?: return@withContext false
            synchronized(watchers) {
                if (watchers.containsKey(webAppId)) return@withContext true
                val listener = ClipboardManager.OnPrimaryClipChangedListener { onChanged(webAppId, cm) }
                cm.addPrimaryClipChangedListener(listener)
                watchers[webAppId] = Watcher(cm, listener)
            }
            true
        }
        return if (started) BridgeOutcome.Ok() else BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Clipboard is not available")
    }

    /** PARITY G12 IME mode: from the keyboard page, while the launcher's keyboard is the chosen one. */
    private suspend fun watchStartIme(webAppId: String): BridgeOutcome = withContext(Dispatchers.Main) {
        if (DeftInputMethodService.forPage(webAppId) == null) {
            return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The keyboard is not showing this app")
        }
        if (!DeftInputMethodService.isCurrent(context)) {
            return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Deft's keyboard is not the chosen keyboard")
        }
        val cm = context.getSystemService(ClipboardManager::class.java)
            ?: return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Clipboard is not available")
        synchronized(imeWatchers) {
            if (!imeWatchers.containsKey(webAppId)) {
                val listener = ClipboardManager.OnPrimaryClipChangedListener { onChangedIme(webAppId, cm) }
                cm.addPrimaryClipChangedListener(listener)
                imeWatchers[webAppId] = Watcher(cm, listener)
            }
        }
        BridgeOutcome.Ok()
    }

    private class Watcher(
        val manager: ClipboardManager,
        val listener: ClipboardManager.OnPrimaryClipChangedListener,
    )

    companion object {
        private val watchers = ConcurrentHashMap<String, Watcher>()

        /** PARITY G12 IME mode: watches started by the keyboard page, apart from the on-screen ones. */
        private val imeWatchers = ConcurrentHashMap<String, Watcher>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Runs on the main thread: the clipboard listener is called there. Text is never logged. */
        private fun onChanged(webAppId: String, cm: ClipboardManager) {
            val text = ClipboardWatchRules.textOf(cm.primaryClip)
            val payload = buildJsonObject { put("text", text?.let { JsonPrimitive(it) } ?: JsonNull) }.toString()
            scope.launch { BridgeService.instanceOrNull()?.emit(webAppId, "clipboard.changed", payload) }
        }

        /**
         * PARITY G12 IME mode: runs on the main thread. Only to the keyboard page (never queued), and
         * only while the launcher's keyboard is still the chosen one and `clipboard.watch` is granted.
         */
        private fun onChangedIme(webAppId: String, cm: ClipboardManager) {
            val context = DeftApp.instance
            if (DeftInputMethodService.forPage(webAppId) == null || !DeftInputMethodService.isCurrent(context)) {
                stopIme(webAppId)
                return
            }
            val text = ClipboardWatchRules.textOf(cm.primaryClip)
            val payload = buildJsonObject { put("text", text?.let { JsonPrimitive(it) } ?: JsonNull) }.toString()
            scope.launch {
                val granted = DeftApp.instance.repository.grantLevel(webAppId, Capability.CLIPBOARD_WATCH).isGranted()
                withContext(Dispatchers.Main) {
                    if (granted) DeftInputMethodService.forPage(webAppId)?.deliver(webAppId, "clipboard.changed", payload)
                    else stopIme(webAppId)
                }
            }
        }

        /** PARITY G12 IME mode: the keyboard page's watch ends (watchStop, page closed). Main thread. */
        fun stopIme(webAppId: String) {
            val watcher = synchronized(imeWatchers) { imeWatchers.remove(webAppId) } ?: return
            watcher.manager.removePrimaryClipChangedListener(watcher.listener)
        }

        /** PARITY G12: leaving the app's screen (or watchStop) removes its clipboard listener. Main thread. */
        fun stopFor(webAppId: String) {
            val watcher = synchronized(watchers) { watchers.remove(webAppId) } ?: return
            watcher.manager.removePrimaryClipChangedListener(watcher.listener)
        }
    }
}

/** Pure rules for clipboard text, kept free of Android types so they can be unit tested. */
object ClipboardWatchRules {
    const val MAX_BYTES = 64 * 1024

    /**
     * Extras that mark a clip as sensitive (a password or one-time code): `ClipDescription.EXTRA_IS_SENSITIVE`
     * (Android 13+) and the same key under the `android.content.extra` name some OEM keyboards and
     * password managers set on older versions.
     */
    val SENSITIVE_KEYS: List<String> = listOf("android.content.extra.IS_SENSITIVE")

    /** True when any [SENSITIVE_KEYS] extra is set; [flag] reads a boolean extra. */
    fun sensitive(flag: (String) -> Boolean): Boolean = SENSITIVE_KEYS.any { runCatching { flag(it) }.getOrDefault(false) }

    /**
     * The plain text of a clip, capped; null for a clip that is not text (images, files, empty)
     * and for a clip marked sensitive: the watcher then reports a change without its text.
     */
    fun textOf(clip: android.content.ClipData?): String? {
        if (clip == null || clip.itemCount == 0) return null
        val description: ClipDescription = clip.description ?: return null
        val extras = description.extras
        if (extras != null && sensitive { extras.getBoolean(it, false) }) return null
        val isText = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        if (!isText) return null
        val text = clip.getItemAt(0).text?.toString() ?: return null
        return cap(text)
    }

    /** Cuts [text] to at most [MAX_BYTES] of UTF-8, never inside a character. */
    fun cap(text: String): String {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val size = utf8Size(codePoint)
            if (bytes + size > MAX_BYTES) return text.substring(0, index)
            bytes += size
            index += Character.charCount(codePoint)
        }
        return text
    }

    private fun utf8Size(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}
