package app.deft.ui.agent

import android.webkit.WebView
import app.deft.bridge.DeftJsBridge
import app.deft.webview.VerifyScript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** How the user answered an `ask` in the Preview banner. [Skipped] means the ask did not apply, with an optional note. */
sealed interface AskAnswer {
    data object Done : AskAnswer
    data class Answered(val value: String) : AskAnswer
    data class Skipped(val note: String?) : AskAnswer
}

/**
 * Agent `apps.preview` and the visible Preview: which Preview WebView is live for a tab's my-app (by slug), the
 * request to open Preview, the one open `ask` banner, and the console of a recording run. Only the Preview's own
 * composition registers here, so a run never reaches a hidden WebView. WebView access is main thread only.
 */
object PreviewLink {
    /** One shown Preview page. [ready] once the current page finished loading. */
    class Live internal constructor(val slug: String, val web: WebView, val bridge: DeftJsBridge) {
        @Volatile var ready: Boolean = false
            internal set
    }

    /** A question the user answers in the Preview banner. */
    class Ask internal constructor(val id: Long, val slug: String, val spec: VerifyScript.AskSpec) {
        val text: String get() = spec.text
        val answer = CompletableDeferred<AskAnswer>()
    }

    private val live = ConcurrentHashMap<String, Live>()
    private val blockers = ConcurrentHashMap<String, String>()
    private val consoles = ConcurrentHashMap<String, (String, String) -> Unit>()
    private val ids = AtomicLong(0)

    /** The agent panel's tab slug while the panel is composed with a my-app tab; null otherwise. */
    @Volatile var panelSlug: String? = null
        private set

    private val _open = MutableStateFlow<String?>(null)

    /** A slug whose Preview the agent asked to open; the panel showing that tab opens it and clears the request. */
    val openRequest: StateFlow<String?> = _open.asStateFlow()

    private val _ask = MutableStateFlow<Ask?>(null)
    val ask: StateFlow<Ask?> = _ask.asStateFlow()

    fun live(slug: String): Live? = live[slug]

    internal fun attach(slug: String, web: WebView, bridge: DeftJsBridge) {
        live[slug] = Live(slug, web, bridge)
    }

    internal fun detach(slug: String, web: WebView?) {
        live.computeIfPresent(slug) { _, l -> if (web == null || l.web === web) null else l }
    }

    internal fun pageState(slug: String, web: WebView, finished: Boolean) {
        live[slug]?.takeIf { it.web === web }?.ready = finished
    }

    /** Why the Preview of [slug] shows no app page (a consent step, an error); null when it can. */
    fun blocker(slug: String): String? = blockers[slug]

    internal fun setBlocker(slug: String, text: String?) {
        if (text == null) blockers.remove(slug) else blockers[slug] = text
    }

    internal fun panelShows(slug: String?) {
        panelSlug = slug
    }

    internal fun panelGone(slug: String?) {
        if (panelSlug == slug) panelSlug = null
    }

    fun requestOpen(slug: String) {
        _open.value = slug
    }

    fun cancelOpen(slug: String) {
        _open.compareAndSet(slug, null)
    }

    /** Shows [spec] in the Preview banner of [slug]; null while another ask is open. */
    fun startAsk(slug: String, spec: VerifyScript.AskSpec): Ask? {
        val ask = Ask(ids.incrementAndGet(), slug, spec)
        return if (_ask.compareAndSet(null, ask)) ask else null
    }

    /** Removes [ask] from the banner (answered, timed out, run cancelled). */
    fun endAsk(ask: Ask) {
        ask.answer.cancel()
        _ask.compareAndSet(ask, null)
    }

    /** Console lines of [slug]'s Preview go to [sink] (level, text) while set. */
    fun setConsole(slug: String, sink: ((String, String) -> Unit)?) {
        if (sink == null) consoles.remove(slug) else consoles[slug] = sink
    }

    internal fun console(slug: String, level: String, text: String) {
        consoles[slug]?.invoke(level, text)
    }
}
