package app.deft.ui.home

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.deft.bridge.BridgeService
import app.deft.bridge.DeftJsBridge
import app.deft.bridge.GrantedPrefixes
import app.deft.bridge.RunContext
import app.deft.data.InstalledWebApp
import app.deft.ui.host.WebViewHostActivity
import app.deft.webview.MyAppDebugLog
import app.deft.webview.WebViewFinding
import app.deft.webview.WebViewFindings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI

/**
 * W2: the routing decisions of the canvas bridge router, pure so every case is unit tested.
 *
 * The canvas WebView has one `jsBridge` listener whose origin rule is [BROAD_RULE]. An origin rule cannot be
 * changed or removed per origin once the listener is added, so the exact set (the widget apps the canvas places)
 * is enforced here, per message.
 */
object WidgetRouting {
    /** Every my-app and bundled app origin. The canvas's own origin matches it too, which [decide] refuses. */
    const val BROAD_RULE = "https://*.app.deft.internal"

    const val REASON_OPAQUE = "opaque origin"
    const val REASON_MAIN_FRAME = "main frame"
    const val REASON_CANVAS = "canvas origin"
    const val REASON_NOT_PLACED = "origin is not a placed widget app"

    sealed interface Decision {
        /** Deliver to the bridge of the app at [origin] (canonical). */
        data class Route(val origin: String) : Decision

        data class Reject(val reason: String) : Decision
    }

    /** `scheme://host[:port]` of an https origin or URL, lowercase, default port dropped; null for anything else ("null", other schemes, user info). */
    fun canonical(originOrUrl: String): String? {
        val uri = try {
            URI(originOrUrl.trim())
        } catch (_: Exception) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme?.lowercase() != "https" || uri.userInfo != null) return null
        val port = uri.port
        return if (port == -1 || port == 443) "https://$host" else "https://$host:$port"
    }

    /**
     * Who may talk to the bridge: only a sub-frame whose origin is one of the [placed] widget origins (canonical),
     * never the canvas page itself (it keeps no bridge) and never a main frame.
     */
    fun decide(sourceOrigin: String, isMainFrame: Boolean, canvasOrigin: String, placed: Set<String>): Decision {
        val origin = canonical(sourceOrigin) ?: return Decision.Reject(REASON_OPAQUE)
        if (isMainFrame) return Decision.Reject(REASON_MAIN_FRAME)
        if (origin == canonical(canvasOrigin)) return Decision.Reject(REASON_CANVAS)
        if (origin !in placed) return Decision.Reject(REASON_NOT_PLACED)
        return Decision.Route(origin)
    }

    /**
     * The URL `deft.open(path)` opens: "" for no path (the app's start page), the origin plus [path] for a same-origin
     * path, null for anything else (no scheme, no `//`, no backslash or control character, at most 1024 characters).
     */
    fun openUrl(origin: String, path: String?): String? {
        if (path.isNullOrBlank()) return ""
        if (path.length > MAX_PATH || path[0] != '/' || path.startsWith("//")) return null
        if (path.any { it == '\\' || it.code < 0x20 || it.code == 0x7F }) return null
        val url = origin.trimEnd('/') + path
        return url.takeIf { canonical(it) == canonical(origin) }
    }

    /** A console line from a widget frame: the widget origin and the text without the frame hook's prefix. */
    data class Attribution(val origin: String, val text: String)

    private fun framePrefix(nonce: String) = Regex("^\\[deft-widget ${Regex.escape(nonce)} (\\S+)] ")

    /**
     * Which placed widget a console line belongs to: by the origin of its source URL; failing that, by the
     * `[deft-widget <nonce> <origin>] ` prefix the frame hook adds (injected scripts and inline errors have no useful
     * source). The nonce is random per canvas WebView and lives only in the hook, so the canvas page cannot forge a
     * line into a widget app's log; a prefix without it is plain text (and the line is then the canvas's own).
     */
    fun attribute(sourceId: String, text: String, placed: Set<String>, nonce: String): Attribution? {
        val prefix = if (nonce.isEmpty()) null else framePrefix(nonce).find(text)
        val clean = if (prefix != null) text.substring(prefix.range.last + 1) else text
        val bySource = canonical(sourceId)?.takeIf { it in placed }
        val byPrefix = prefix?.groupValues?.get(1)?.let { canonical(it) }?.takeIf { it in placed }
        val origin = bySource ?: byPrefix ?: return null
        return Attribution(origin, clean)
    }

    /** A new unguessable nonce for the console prefix hook. */
    fun newNonce(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    /** `deft.open()` needs a real touch this recently (the launcher sees them on the canvas, the page cannot fake one). */
    const val GESTURE_WINDOW_MS = 2_000L

    fun gestureFresh(now: Long, lastTouchAt: Long): Boolean = lastTouchAt > 0 && now - lastTouchAt in 0..GESTURE_WINDOW_MS

    /** The reserved URL a "set up" placeholder in the canvas navigates to: `deft://setup/<encoded app id>`. */
    const val SETUP_PREFIX = "deft://setup/"

    /** The pre-rename form of [SETUP_PREFIX], still accepted. */
    const val LEGACY_SETUP_PREFIX = "fl://setup/"

    /** True when [url] starts with [SETUP_PREFIX] or [LEGACY_SETUP_PREFIX]. */
    fun isSetupUrl(url: String): Boolean = url.startsWith(SETUP_PREFIX) || url.startsWith(LEGACY_SETUP_PREFIX)

    /**
     * The app id a tap on a setup placeholder may set up, or null. Accepted only for a navigation the page's own
     * user gesture started ([hasGesture]), within a real-touch window the launcher saw ([touchFresh]), and only for
     * an app in [waiting] (placed widget apps whose widget needs setup). Anything else is ignored.
     */
    fun setupTarget(url: String, hasGesture: Boolean, touchFresh: Boolean, waiting: Set<String>): String? {
        if (!hasGesture || !touchFresh || !isSetupUrl(url)) return null
        val id = try {
            java.net.URLDecoder.decode(url.substringAfter("://setup/"), "UTF-8")
        } catch (_: Exception) {
            return null
        }
        return id.takeIf { it in waiting }
    }

    /** At most [limit] messages per [windowMs] per frame; the rest are dropped. */
    class RateGate(private val limit: Int = 200, private val windowMs: Long = 1_000L) {
        private var windowStart = Long.MIN_VALUE
        private var count = 0

        fun allow(now: Long): Boolean {
            if (windowStart == Long.MIN_VALUE || now - windowStart >= windowMs || now < windowStart) {
                windowStart = now
                count = 0
            }
            return ++count <= limit
        }
    }

    /** Console prefix hook: tags error and warn lines with the frame's origin. */
    private fun prefixHook(nonce: String) =
        "(function(){['error','warn'].forEach(function(k){var f=console[k];console[k]=function(){" +
            "var a=[].slice.call(arguments);a[0]='[deft-widget $nonce '+location.origin+'] '+String(a[0]);return f.apply(console,a)}})})();"

    /** What a widget frame sends on `pagehide` (a reload or the canvas going away): its bridge slot is disposed. */
    const val BYE = "{\"type\":\"deft:bye\"}"

    /** True for the frame's [BYE] message, which the router handles itself (it never reaches the app's bridge). */
    fun isBye(raw: String?): Boolean = raw == BYE

    /**
     * Frame side of the canvas's `deft:theme` (reload theme.css, as themeCssJs does for a top document; apps made
     * before the rename link /_fl/v1/theme.css, which is reloaded too) and
     * `deft:reload` (the widget app's files changed) messages. Only the parent window is believed. On `pagehide`
     * the frame says [BYE], so a reloaded frame does not leave its old bridge (and its subscriptions) running.
     */
    const val FRAME_MESSAGES_JS =
        "addEventListener('message',function(e){if(e.source!==parent)return;var m=e.data;" +
            "if(m==='deft:theme'){var r=Date.now();['/_deft/v1/theme.css','/_fl/v1/theme.css'].forEach(function(p){" +
            "document.querySelectorAll('link[href*=\"'+p+'\"]').forEach(function(l){l.href=p+'?r='+r})});" +
            "try{dispatchEvent(new Event('deft:theme'));dispatchEvent(new Event('fl:theme'))}catch(x){}}" +
            "else if(m==='deft:reload'){location.reload()}});" +
            "(function(){var p=self.jsBridge;addEventListener('pagehide',function(){try{p&&p.postMessage('$BYE')}catch(x){}})})();"

    /**
     * The document-start script of a widget frame: the bridge client and the debug hook. Never in the canvas's own
     * document, and never in a document of the canvas origin inside a frame (about:blank, srcdoc, blob: children
     * inherit it), so the canvas cannot get the client or read the nonce out of a frame it creates.
     * `fl-in-widget` is for apps on the pre-rename /_fl/v1 runtime, whose CSS keeps the old class name.
     */
    fun frameScript(client: String, canvasOrigin: String, nonce: String): String {
        val canvas = canonical(canvasOrigin) ?: "null"
        return "if(self!==top&&location.origin!=='$canvas'&&location.origin!=='null'){document.documentElement.classList.add('deft-in-widget','fl-in-widget');$FRAME_MESSAGES_JS${prefixHook(nonce)}\n$client\n${WebViewFindings.HOOK_JS}\n}"
    }

    private const val MAX_PATH = 1024
}

/**
 * W2: the bridge of the home canvas WebView. The canvas page has no bridge; each `<deft-widget>` frame talks to a
 * [DeftJsBridge] of its own app, in widget context (the app's grants, `needs_foreground` for anything that shows
 * a screen or asks, audit as usual). One router per canvas WebView.
 *
 * - One `jsBridge` listener, origin rule [WidgetRouting.BROAD_RULE], registered once. Which origins may use it is
 *   decided in [onMessage] ([WidgetRouting.decide]) against [apps], the installed apps the canvas places.
 * - A bridge belongs to one frame (identified by its [JavaScriptReplyProxy], one per frame document), so replies
 *   and events reach only the frame that asked, also when one app is placed twice.
 * - [setApps] and [refreshGrants] keep it current; [detach] ends everything (view destroyed or renderer gone).
 * All of it runs on the main thread.
 */
class WidgetBridgeRouter(
    private val context: Context,
    private val canvasOrigin: String,
    /** The installed apps the canvas places and may frame (see [CanvasWidgetServer.widgetApps]). */
    private val resolve: suspend () -> List<InstalledWebApp>,
    private val loadGates: suspend (InstalledWebApp) -> GrantedPrefixes.Gates,
) {
    private class Slot(val origin: String, val proxy: JavaScriptReplyProxy) {
        val gate = WidgetRouting.RateGate()
        var lastActive = SystemClock.elapsedRealtime()
        var bridge: DeftJsBridge? = null
        var starting = false
        val queue = ArrayList<String?>()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val slots = ArrayList<Slot>()
    @Volatile private var apps: Map<String, InstalledWebApp> = emptyMap()
    private var view: WebView? = null
    private var script: ScriptHandler? = null
    private var service: BridgeService? = null
    private var waitingForService = false
    private var resolving = false
    private var lastResolveAt = 0L
    private var lastOpenAt = 0L
    private var detached = false
    private val nonce = WidgetRouting.newNonce()
    @Volatile private var lastTouchAt = 0L

    /** The canvas saw a real finger down or up: `deft.open()` may work for the next [WidgetRouting.GESTURE_WINDOW_MS]. */
    fun noteTouch() {
        lastTouchAt = SystemClock.elapsedRealtime()
    }

    /** A real touch happened recently and Home is showing (the same rule as `deft.open()`). */
    fun touchFresh(): Boolean = !detached && visible && WidgetRouting.gestureFresh(SystemClock.elapsedRealtime(), lastTouchAt)

    /** Set by the canvas: Home is resumed, settled and not covered. `deft.open()` only works while true. */
    @Volatile var visible = false

    /** Adds the listener and the frame script to [webView]. False (nothing added) when this WebView cannot host the bridge. */
    fun attach(webView: WebView): Boolean {
        if (!DeftJsBridge.webViewSupported()) return false
        val client = runCatching { DeftJsBridge.clientJs(context) }.getOrNull() ?: return false
        val rules = setOf(WidgetRouting.BROAD_RULE)
        script = runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, WidgetRouting.frameScript(client, canvasOrigin, nonce), rules)
        }.onFailure { Log.w(TAG, "frame script not injected", it) }.getOrNull() ?: return false
        val added = runCatching {
            WebViewCompat.addWebMessageListener(webView, DeftJsBridge.PORT_NAME, rules) { _, message, origin, isMainFrame, reply ->
                onMessage(message.data, origin.toString(), isMainFrame, reply)
            }
        }.onFailure { Log.w(TAG, "widget bridge listener not added", it) }.isSuccess
        if (!added) {
            runCatching { script?.remove() }
            script = null
            return false
        }
        view = webView
        ensureService()
        return true
    }

    /** The widget apps the canvas places now. Frames of an app that is no longer placed lose their bridge. */
    fun setApps(list: List<InstalledWebApp>) {
        if (detached) return
        apps = list.mapNotNull { app -> WidgetRouting.canonical(app.origin)?.let { it to app } }.toMap()
        slots.filter { it.origin !in apps }.forEach { dispose(it) }
    }

    /** Re-reads every frame's grants and tells the frame (Home resumed; a grant may have changed). */
    fun refreshGrants() {
        if (detached) return
        for (slot in slots.toList()) {
            val bridge = slot.bridge ?: continue
            val app = apps[slot.origin] ?: continue
            scope.launch {
                val gates = loadGates(app)
                if (slot in slots) {
                    bridge.applyGates(gates)
                    bridge.sendGrants()
                }
            }
        }
    }

    /**
     * A console line of the canvas WebView: true when it came from a placed widget frame. It then goes to that app's
     * debug log (my-apps) and, as a summary line, to the canvas debug log.
     */
    fun logFrame(finding: WebViewFinding): Boolean {
        val hit = WidgetRouting.attribute(finding.source, finding.text, apps.keys, nonce) ?: return false
        val app = apps[hit.origin] ?: return false
        app.workspaceSlug?.let {
            MyAppDebugLog.append(it, WebViewFindings.finding(finding.level, finding.kind, hit.text, finding.source, finding.line))
        }
        MyAppDebugLog.append(
            HomeCanvasStore.SLUG,
            WebViewFindings.finding(finding.level, "widget", "[${app.id}] ${hit.text}", finding.source, finding.line),
        )
        return true
    }

    /** Ends every frame bridge and the listener (idempotent). The view is destroyed or dead. */
    fun detach() {
        if (detached) return
        detached = true
        slots.toList().forEach { dispose(it) }
        runCatching { script?.remove() }
        script = null
        view?.let { v ->
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                runCatching { WebViewCompat.removeWebMessageListener(v, DeftJsBridge.PORT_NAME) }
            }
        }
        view = null
        scope.cancel()
    }

    private fun onMessage(raw: String?, sourceOrigin: String, isMainFrame: Boolean, proxy: JavaScriptReplyProxy) {
        if (detached) return
        if (WidgetRouting.isBye(raw)) {
            // The frame document is going away (reload, navigation): its proxy will never be used again.
            slots.firstOrNull { it.proxy === proxy }?.let { dispose(it) }
            return
        }
        when (val d = WidgetRouting.decide(sourceOrigin, isMainFrame, canvasOrigin, apps.keys)) {
            is WidgetRouting.Decision.Route -> deliver(d.origin, raw, proxy)
            is WidgetRouting.Decision.Reject -> {
                if (d.reason != WidgetRouting.REASON_NOT_PLACED) return
                // The page may have loaded before the app set did: look once more (at most once a second), then decide again.
                val now = SystemClock.elapsedRealtime()
                if (resolving || now - lastResolveAt < RESOLVE_GAP_MS) return
                resolving = true
                lastResolveAt = now
                scope.launch {
                    try {
                        setApps(resolve())
                    } finally {
                        resolving = false
                    }
                    val again = WidgetRouting.decide(sourceOrigin, isMainFrame, canvasOrigin, apps.keys)
                    if (again is WidgetRouting.Decision.Route) deliver(again.origin, raw, proxy)
                }
            }
        }
    }

    private fun deliver(origin: String, raw: String?, proxy: JavaScriptReplyProxy) {
        var slot = slots.firstOrNull { it.proxy === proxy }
        if (slot != null && slot.origin != origin) {
            dispose(slot)
            slot = null
        }
        if (slot == null) {
            // A reloaded frame has a new proxy and leaves its old slot behind: keep the most recently active ones.
            while (slots.count { it.origin == origin } >= CanvasWidgets.CAP) disposeLeastActive(slots.filter { it.origin == origin })
            while (slots.size >= MAX_FRAMES) disposeLeastActive(slots)
            slot = Slot(origin, proxy).also { slots.add(it) }
        }
        val now = SystemClock.elapsedRealtime()
        if (!slot.gate.allow(now)) return
        slot.lastActive = now
        val bridge = slot.bridge
        if (bridge != null) {
            bridge.onWebMessage(raw, origin, false, proxy)
            return
        }
        // The first message of a frame ("hello"): read its grants first, so the page's first calls see them.
        slot.queue.add(raw)
        if (slot.starting) return
        slot.starting = true
        val app = apps[origin] ?: return dispose(slot)
        val target = slot
        scope.launch {
            val gates = runCatching { loadGates(app) }.getOrNull()
            if (gates == null || target !in slots) return@launch dispose(target)
            val made = DeftJsBridge(
                app, gates.granted, scope,
                background = true,
                context = RunContext.WIDGET,
                widget = true,
            )
            made.applyGates(gates)
            view?.let { made.attachRouted(it) }
            made.onOpen = { path -> open(app, path) }
            made.service = service
            service?.registerSink(made)
            target.bridge = made
            val pending = target.queue.toList()
            target.queue.clear()
            pending.forEach { made.onWebMessage(it, origin, false, proxy) }
        }
    }

    private fun disposeLeastActive(among: List<Slot>) {
        dispose(among.minByOrNull { it.lastActive } ?: return)
    }

    private fun dispose(slot: Slot) {
        slots.remove(slot)
        val bridge = slot.bridge ?: return
        slot.bridge = null
        runCatching { service?.unregisterSink(bridge) }
        bridge.detach(null)
    }

    /** `deft.open()`: the app's own start page or a same-origin path. Null when it opened, else why not. */
    private fun open(app: InstalledWebApp, path: String?): String? {
        if (detached || !visible) return "Home is not showing"
        if (!WidgetRouting.gestureFresh(SystemClock.elapsedRealtime(), lastTouchAt)) return "Opening the app needs a tap"
        val url = WidgetRouting.openUrl(app.origin, path) ?: return "path must be a path of the app, starting with /"
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return "Already opening"
        lastOpenAt = now
        return try {
            val host = view?.context ?: context
            host.startActivity(WebViewHostActivity.intent(host, app.id, url.ifEmpty { null }))
            null
        } catch (_: Exception) {
            "Could not open the app"
        }
    }

    /** The bridge service may still be starting when Home is: pick it up as soon as it exists. */
    private fun ensureService() {
        service = BridgeService.instanceOrNull()
        if (service != null || waitingForService) return
        waitingForService = true
        BridgeService.start(context)
        scope.launch {
            repeat(SERVICE_POLLS) {
                delay(SERVICE_POLL_MS)
                val found = BridgeService.instanceOrNull()
                if (found != null) {
                    service = found
                    slots.forEach { slot ->
                        slot.bridge?.let {
                            it.service = found
                            found.registerSink(it)
                        }
                    }
                    waitingForService = false
                    return@launch
                }
            }
            waitingForService = false
        }
    }

    private companion object {
        const val TAG = "WidgetRouter"
        const val MAX_FRAMES = 16
        const val RESOLVE_GAP_MS = 1_000L
        const val OPEN_GAP_MS = 1_500L
        const val SERVICE_POLLS = 20
        const val SERVICE_POLL_MS = 250L
    }
}
