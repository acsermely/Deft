package app.deft.webview

import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.modules.NetFetchClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The verify sandbox's `net.fetch`: the page's call is answered by the real [NetFetchClient], under
 * the app's own listed origins only (no network.open). The page reaches it through a WebView
 * message port that only the app's origin may use ([WebViewCompat.addWebMessageListener]); every
 * other bridge method stays a stub or answers from the agent's mocks ([stubJs]). Fetched content is
 * untrusted, so the verify output says so.
 */
class VerifyNet(
    private val listed: Collection<String>,
    private val onActivity: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicInteger(0)
    private val calls = AtomicInteger(0)

    /** Hosts the page fetched from during this sandbox run. */
    val hosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun inFlight(): Int = inFlight.get()

    /** False when the WebView has no web message listener; the page then sees the stub's "unavailable". */
    fun attach(view: WebView, origin: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        WebViewCompat.addWebMessageListener(view, CHANNEL, setOf(origin)) { _, message, _, _, reply ->
            onMessage(message, reply)
        }
        return true
    }

    fun close() {
        scope.cancel()
    }

    private fun onMessage(message: WebMessageCompat, reply: JavaScriptReplyProxy) {
        val msg = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull() ?: return
        val id = msg.optInt("id", 0)
        val method = msg.optString("method")
        val args = runCatching { Json.parseToJsonElement(msg.optString("args", "{}")) as? JsonObject }
            .getOrNull() ?: JsonObject(emptyMap())
        onActivity()
        if (method != METHOD) {
            scope.launch { send(reply, id, BridgeOutcome.Err("unsupported", "not available in the verify sandbox")) }
            return
        }
        if (calls.incrementAndGet() > MAX_CALLS) {
            scope.launch { send(reply, id, BridgeOutcome.Err("rate_limited", "at most $MAX_CALLS fetches per verify run")) }
            return
        }
        inFlight.incrementAndGet()
        scope.launch {
            try {
                val outcome = when (val parsed = NetFetchClient.parse(args)) {
                    is NetFetchClient.Parsed.Bad -> BridgeOutcome.Err("invalid_args", parsed.message)
                    is NetFetchClient.Parsed.Ok -> {
                        hosts.add(parsed.args.target.host)
                        NetFetchClient.fetch(
                            parsed.args.target,
                            parsed.args.method,
                            parsed.args.mode,
                            parsed.args.headers,
                            listed,
                            open = false,
                            local = false,
                        )
                    }
                }
                send(reply, id, outcome)
            } finally {
                inFlight.decrementAndGet()
                onActivity()
            }
        }
    }

    private suspend fun send(reply: JavaScriptReplyProxy, id: Int, outcome: BridgeOutcome) {
        val text = when (outcome) {
            is BridgeOutcome.Ok -> buildJsonObject {
                put("id", JsonPrimitive(id))
                put("ok", JsonPrimitive(true))
                put("value", outcome.value)
            }
            is BridgeOutcome.Err -> buildJsonObject {
                put("id", JsonPrimitive(id))
                put("ok", JsonPrimitive(false))
                put("code", JsonPrimitive(outcome.code))
                put("message", JsonPrimitive(outcome.message))
            }
        }
        withContext(Dispatchers.Main) {
            runCatching { reply.postMessage(text.toString()) }
        }
    }

    companion object {
        const val CHANNEL = "__deftVerifyNet"
        const val METHOD = "net.fetch"

        /** A looping page cannot hammer the origin during one sandbox run. */
        const val MAX_CALLS = 12

        /**
         * Document-start stub; [configJs] is [VerifyScript.bridgeConfigJs]. `allowed` mirrors a granted app, so
         * `has()` takes the granted path; mocked methods answer from the config, `net.fetch` goes through [CHANNEL],
         * the rest resolve {}. Listeners are kept per event and calls are logged in `window.__deftVerify`, so
         * scripted steps can emit events and the output can show which methods the page asked for.
         */
        fun stubJs(configJs: String): String = """
            (function(cfg){
              if (window.deftBridge) return;
              var seq = 0, waiting = {}, hooked = false, subs = Object.create(null), mocks = cfg.mocks || {};
              var V = {calls: [], more: 0,
                count: function (e) { return subs[e] ? subs[e].length : 0; },
                emit: function (e, data) {
                  (subs[e] || []).slice().forEach(function (fn) {
                    try { fn(JSON.parse(JSON.stringify(data))); }
                    catch (x) { if (self.reportError) self.reportError(x); else setTimeout(function () { throw x; }); }
                  });
                }};
              try { Object.defineProperty(window, '__deftVerify', {value: V}); } catch (e) {}
              function log(m, args) {
                if (V.calls.length >= 30) { V.more++; return; }
                var a; try { a = JSON.stringify(args === undefined ? {} : args); } catch (e) { a = '?'; }
                a = String(a); if (a.length > 80) a = a.slice(0, 80) + '…';
                V.calls.push(String(m).slice(0, 64) + ' ' + a);
              }
              function onReply(ev) {
                var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
                var w = waiting[m.id]; if (!w) return; delete waiting[m.id];
                if (m.ok) { w.resolve(m.value); }
                else { var err = new Error(m.message || 'failed'); err.code = m.code; w.reject(err); }
              }
              function call(method, args) {
                return new Promise(function (resolve, reject) {
                  var c = window.__deftVerifyNet;
                  if (!c) { reject(new Error('network is not available in the verify sandbox')); return; }
                  var id = ++seq; waiting[id] = {resolve: resolve, reject: reject};
                  if (!hooked) { c.onmessage = onReply; hooked = true; }
                  c.postMessage(JSON.stringify({id: id, method: method, args: args || {}}));
                });
              }
              window.deftBridge = {
                ready: Promise.resolve(), allowed: new Set(cfg.allowed || []),
                invoke: function (m, args) {
                  self.__deftCalls = (self.__deftCalls || 0) + 1;
                  log(m, args);
                  if (Object.prototype.hasOwnProperty.call(mocks, m)) {
                    var r = mocks[m];
                    if (r.error) { var err = new Error(r.error.message); err.code = r.error.code; return Promise.reject(err); }
                    return Promise.resolve(JSON.parse(JSON.stringify(r.value === undefined ? null : r.value)));
                  }
                  return m === 'net.fetch' ? call(m, args) : Promise.resolve({});
                },
                subscribe: function (event, fn) {
                  if (typeof fn !== 'function') return function () {};
                  var l = subs[event] || (subs[event] = []);
                  l.push(fn);
                  return function () { var i = l.indexOf(fn); if (i >= 0) l.splice(i, 1); };
                }
              };
            })($configJs);
        """
    }
}
