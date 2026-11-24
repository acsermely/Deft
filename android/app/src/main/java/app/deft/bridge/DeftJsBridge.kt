package app.deft.bridge

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.deft.catalog.BridgeCatalog
import app.deft.catalog.Capability
import app.deft.data.InstalledWebApp
import app.deft.data.canonicalOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class DeftJsBridge(
    private val app: InstalledWebApp,
    var grantedPrefixes: Set<String>,
    private val scope: CoroutineScope,
    private val listenerOrigin: String = app.origin,
    /** PARITY D1: this bridge belongs to a background run; every call is flagged as such. */
    private val background: Boolean = false,
    /**
     * PARITY G11: the grant a [background] page needs on every call: `background` for a run, `wallpaper`
     * for the live wallpaper's page (same `needs_foreground` rules).
     */
    private val runCapability: Capability = Capability.BACKGROUND,
    /** Widget frame or agent tool run: set only by the code that builds the bridge (see [RunContext]). */
    private val context: RunContext = RunContext.RUN,
    /**
     * W2: this bridge serves one `<deft-widget>` frame of the home canvas. It runs with [background] semantics
     * (`needs_foreground` for anything that shows a screen or asks), needs no run grant, answers only a sub-frame
     * (see [onWebMessage]) and is fed by the canvas router, never by its own listener.
     */
    private val widget: Boolean = false,
) : BridgeEventSink {
    /**
     * The gates a foreground call is checked against (see [GrantedPrefixes.Gates]). Null keeps the strict set
     * [grantedPrefixes] for every call, as a background run does.
     */
    var gates: GrantedPrefixes.Gates? = null

    /** Sets [gates] and the strict [grantedPrefixes] from the same read, so the two never disagree. */
    fun applyGates(gates: GrantedPrefixes.Gates) {
        this.gates = gates
        grantedPrefixes = gates.granted
    }

    override val webAppId: String = app.id
    @Volatile var service: BridgeService? = null
        set(value) {
            field = value
            if (value != null) {
                val pending = synchronized(serviceWaiters) {
                    serviceWaiters.toList().also { serviceWaiters.clear() }
                }
                pending.forEach { it.complete(value) }
            }
        }
    @Volatile var holdReady: Boolean = false

    /** Agent `apps.preview`: sees calls, results, subscriptions and events while a run records; null otherwise. */
    @Volatile var tap: BridgeTap? = null
        set(value) {
            field = value
            if (value == null) tapMethods.clear()
        }

    /** Request id to method, for calls made while [tap] was set (their result names the method). */
    private val tapMethods = ConcurrentHashMap<String, String>()

    /** PARITY G11: called on the main thread when the page subscribes to an event (the wallpaper sends its current offsets). */
    @Volatile var onSubscribe: ((String) -> Unit)? = null
    @Volatile private var detached = false
    @Volatile private var greeted = false
    private var reply: JavaScriptReplyProxy? = null
    private val lockedOrigin = canonicalOrigin(app.origin)
    private val subscribers = ConcurrentHashMap<String, MutableSet<String>>()
    private val pendingEvents = ArrayDeque<Pair<String, String>>()
    private val serviceWaiters = ArrayDeque<CompletableDeferred<BridgeService?>>()

    private val listener = WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, replyProxy ->
        onWebMessage(message.data, sourceOrigin.toString(), isMainFrame, replyProxy)
    }

    /**
     * One page-to-native message. A normal bridge answers only its main frame; a [widget] bridge only a sub-frame.
     * Both answer only [lockedOrigin], and reply only to [replyProxy], the frame that sent the message.
     */
    internal fun onWebMessage(raw: String?, sourceOrigin: String, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
        if (detached || isMainFrame == widget) return
        val origin = canonicalOrigin(sourceOrigin)
        if (origin != lockedOrigin) return
        reply = replyProxy
        if (raw == null) return
        val msg = runCatching { BridgeJson.parseToJsonElement(raw).jsonObject }.getOrElse { return }
        when (msg.string("type")) {
            "hello" -> {
                greeted = true
                subscribers.clear()
                sendReady()
                drainEvents()
            }
            "ping" -> complete(msg.string("id"), ok = true, value = JsonPrimitive(true))
            "invoke" -> scope.launch {
                tap?.let { t ->
                    val id = msg.string("id")
                    val method = msg.string("method")
                    if (id.isNotBlank() && method.isNotBlank()) {
                        tapMethods[id] = method
                        t.call(method)
                    }
                }
                try {
                    onInvoke(msg, origin)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    complete(
                        msg.string("id"),
                        ok = false,
                        code = BridgeErrors.UNSUPPORTED,
                        message = t.message?.takeIf { it.isNotBlank() } ?: "bridge error",
                    )
                }
            }
            "subscribe" -> {
                val event = msg.string("event")
                val id = msg.string("id")
                if (event.isNotBlank() && id.isNotBlank()) {
                    subscribers.getOrPut(event) { mutableSetOf() }.add(id)
                    tap?.subscribe(event)
                    drainEvents()
                    onSubscribe?.invoke(event)
                }
            }
            "unsubscribe" -> {
                val event = msg.string("event")
                subscribers[event]?.remove(msg.string("id"))
            }
        }
    }

    @Volatile private var lastHapticAt = 0L

    /** The page the haptic call vibrates; set while attached. */
    @Volatile private var hapticView: WebView? = null

    /** W2: a [widget] bridge has no listener of its own; the canvas router feeds [onWebMessage]. */
    fun attachRouted(webView: WebView) {
        hapticView = webView
    }

    /**
     * W2 `deft.open()`: called on the main thread with the optional same-origin path; returns null when the app was
     * opened, else a message. Set by the canvas router, which knows the visibility and rate limit.
     */
    @Volatile var onOpen: ((String?) -> String?)? = null

    fun attach(webView: WebView) {
        hapticView = webView
        WebViewCompat.addWebMessageListener(
            webView,
            PORT_NAME,
            setOf(listenerOrigin),
            listener,
        )
    }

    fun detach(webView: WebView?) {
        detached = true
        hapticView = null
        val pending = synchronized(serviceWaiters) {
            serviceWaiters.toList().also { serviceWaiters.clear() }
        }
        pending.forEach { it.complete(null) }
        service = null
        reply = null
        subscribers.clear()
        synchronized(pendingEvents) { pendingEvents.clear() }
        if (webView != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, PORT_NAME) }
        }
    }

    fun sendGrants() {
        post(
            buildJsonObject {
                put("type", "grants")
                put("v", PROTOCOL)
                put("allowed", allowedArray())
                put("askable", askableArray())
                put("caps", capsArray())
            },
        )
    }

    fun releaseReady() {
        holdReady = false
        if (greeted) sendReady()
    }

    /** PARITY G11: whether the page listens for [event] now; events it does not listen for are not queued by the caller. */
    fun hasSubscriber(event: String): Boolean = !detached && reply != null && !subscribers[event].isNullOrEmpty()

    override val isWidget: Boolean get() = widget

    override fun listensFor(event: String): Boolean = !detached && reply != null && !subscribers[event].isNullOrEmpty()

    override fun dispatchEvent(event: String, payloadJson: String) {
        if (detached) return
        // A widget frame only gets events it subscribed to; nothing piles up for a frame that is not listening.
        if (widget && subscribers[event].isNullOrEmpty()) return
        if (reply == null || subscribers[event].isNullOrEmpty()) {
            synchronized(pendingEvents) { pendingEvents.addLast(event to payloadJson) }
            return
        }
        emit(event, payloadJson)
    }

    private suspend fun onInvoke(msg: JsonObject, origin: String) {
        val id = msg.string("id")
        val method = msg.string("method")
        if (id.isBlank() || method.isBlank()) {
            complete(id, ok = false, code = BridgeErrors.INVALID_ARGS, message = "method required")
            return
        }
        val args = when (val rawArgs = msg["args"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> rawArgs
            else -> {
                complete(id, ok = false, code = BridgeErrors.INVALID_ARGS, message = "args must be a JSON object")
                return
            }
        }
        if (method == WIDGET_OPEN) {
            val open = onOpen
            if (!widget || open == null) {
                complete(id, ok = false, code = BridgeErrors.UNSUPPORTED, message = "Only a home widget can open its app")
                return
            }
            val path = (args["path"] as? JsonPrimitive)?.contentOrNull
            val err = withContext(Dispatchers.Main) { open(path) }
            if (err == null) complete(id, ok = true) else complete(id, ok = false, code = BridgeErrors.INVALID_ARGS, message = err)
            return
        }
        if (method == HapticSpec.METHOD && !background) {
            playHaptic(id, args)
            return
        }
        val cap = BridgeCatalog.capabilityFor(method)
        // net.fetch has no grant of its own: BridgeService checks the origin policy on every call.
        val ungated = method == NetFetchSpec.METHOD
        // The page's own caps stay strict. A foreground call may reach BridgeService when its capability is askable
        // (granted, never decided, or not a user denial): BridgeService grants it or asks at the point of use.
        // A background run keeps the strict set: it never asks.
        val reachable = if (background) grantedPrefixes else gates?.askable ?: grantedPrefixes
        if (!ungated && (cap == null || cap.id !in reachable)) {
            Log.i("DeftBridge", "js-gate app=${app.id} capability=${cap?.id ?: "unknown"} reason=${jsGateReason(cap)}")
            complete(id, ok = false, code = BridgeErrors.NOT_GRANTED, message = "Not granted")
            return
        }
        val svc = waitForService()
        if (svc == null || detached) {
            complete(id, ok = false, code = BridgeErrors.BRIDGE_NOT_READY, message = "Bridge not ready")
            return
        }
        val result = withContext(Dispatchers.IO) { svc.invoke(app, method, args, origin, background, runCapability, context) }
        when (result) {
            is BridgeOutcome.Ok -> complete(id, ok = true, value = result.value)
            is BridgeOutcome.Err -> complete(id, ok = false, code = result.code, message = result.message)
        }
    }

    /** `haptics.play`: no grant, so it is answered before the capability check. */
    private fun playHaptic(id: String, args: JsonObject) {
        val constant = HapticSpec.constant(args.string("kind"))
            ?: return complete(id, ok = false, code = BridgeErrors.INVALID_ARGS, message = "kind must be light, medium, success, warning or selection")
        val target = hapticView
        if (target == null || detached) return complete(id, ok = false, code = BridgeErrors.BRIDGE_NOT_READY, message = "Bridge not ready")
        // Native rate limit: a page cannot buzz faster than its own 40 ms client gate, whatever it sends.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHapticAt >= HAPTIC_GAP_MS) {
            lastHapticAt = now
            target.post { target.performHapticFeedback(constant) }
        }
        complete(id, ok = true)
    }

    private fun jsGateReason(cap: Capability?): String {
        val g = gates
        return when {
            cap == null -> "unknown method"
            background || g == null -> "not granted"
            cap.id !in g.declared -> "undeclared"
            else -> "denied by user"
        }
    }

    private fun sendReady() {
        if (detached || holdReady || reply == null) return
        post(
            buildJsonObject {
                put("type", "ready")
                put("v", PROTOCOL)
                put("appId", app.id)
                put("allowed", allowedArray())
                put("askable", askableArray())
                put("caps", capsArray())
            },
        )
    }

    private fun drainEvents() {
        val leftover = ArrayDeque<Pair<String, String>>()
        val batch = synchronized(pendingEvents) {
            pendingEvents.toList().also { pendingEvents.clear() }
        }
        for ((event, payload) in batch) {
            if (reply == null || subscribers[event].isNullOrEmpty()) leftover.addLast(event to payload)
            else emit(event, payload)
        }
        if (leftover.isNotEmpty()) {
            synchronized(pendingEvents) {
                leftover.reversed().forEach { pendingEvents.addFirst(it) }
            }
        }
    }

    private fun emit(event: String, payloadJson: String) {
        tap?.event(event, payloadJson)
        val payload = runCatching {
            BridgeJson.parseToJsonElement(payloadJson.ifBlank { "{}" })
        }.getOrDefault(JsonObject(emptyMap()))
        post(
            buildJsonObject {
                put("type", "event")
                put("v", PROTOCOL)
                put("name", event)
                put("payload", payload)
            },
        )
    }

    private fun complete(
        id: String,
        ok: Boolean,
        value: JsonElement? = null,
        code: String? = null,
        message: String? = null,
    ) {
        tapMethods.remove(id)?.let { method -> tap?.result(method, ok, if (ok) null else code ?: BridgeErrors.UNSUPPORTED) }
        val body = buildJsonObject {
            put("type", "result")
            put("v", PROTOCOL)
            put("id", id)
            put("ok", ok)
            if (ok) put("value", value ?: JsonNull)
            else {
                put(
                    "error",
                    buildJsonObject {
                        put("code", code ?: BridgeErrors.UNSUPPORTED)
                        put("message", message ?: "bridge error")
                    },
                )
            }
        }
        post(body)
    }

    private fun post(obj: JsonObject) {
        if (detached) return
        val proxy = reply ?: return
        runCatching { proxy.postMessage(obj.toString()) }
    }

    /** Granted capability ids, so the page's platform shims can gate standard APIs (vibrate). */
    private fun capsArray(): JsonArray = JsonArray(grantedPrefixes.sorted().map { JsonPrimitive(it) })

    /** Methods the page sees as granted now. Apps may use this for feature detection, so it stays strict. */
    private fun allowedArray(): JsonArray = JsonArray(
        methodsJson(grantedPrefixes) + JsonPrimitive(HapticSpec.METHOD) + JsonPrimitive(NetFetchSpec.METHOD) +
            (if (widget) listOf(JsonPrimitive(WIDGET_OPEN)) else emptyList()),
    )

    /** Methods a foreground call may send so BridgeService can ask at the point of use. Empty for a background run. */
    private fun askableArray(): JsonArray = methodsJson(askableFor(background, gates))

    private suspend fun waitForService(): BridgeService? {
        service?.let { return it }
        if (detached) return null
        val waiter = CompletableDeferred<BridgeService?>()
        synchronized(serviceWaiters) {
            service?.let { return it }
            if (detached) return null
            serviceWaiters.addLast(waiter)
        }
        return try {
            withTimeoutOrNull(SERVICE_WAIT_MS) { waiter.await() } ?: service
        } finally {
            synchronized(serviceWaiters) { serviceWaiters.remove(waiter) }
        }
    }

    companion object {
        private const val HAPTIC_GAP_MS = 40L
        const val PORT_NAME = "jsBridge"

        /** W2: the one method only a home-widget frame may call; no grant, always its own app. */
        const val WIDGET_OPEN = "widget.open"
        const val PROTOCOL = 1
        const val CLIENT_ASSET = "bridge/deft-bridge.js"
        private const val SERVICE_WAIT_MS = 2_000L

        fun webViewSupported(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        fun clientJs(context: Context): String =
            context.assets.open(CLIENT_ASSET).bufferedReader().use { it.readText() }

        /** The capability ids a page may ask for at the point of use. A background run never asks, so it gets none. */
        internal fun askableFor(background: Boolean, gates: GrantedPrefixes.Gates?): Set<String> =
            if (background) emptySet() else gates?.askable.orEmpty()

        /** The JS method names the given capability ids open; an id that is not a catalog capability passes through. */
        internal fun methodsJson(ids: Collection<String>): JsonArray {
            val methods = ids.flatMap { id ->
                val cap = Capability.fromId(id)
                if (cap != null) BridgeCatalog.methodsFor(cap) else listOf(id)
            }.distinct()
            return JsonArray(methods.map { JsonPrimitive(it) })
        }
    }
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
