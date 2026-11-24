package app.deft.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * apps.verify behaviour tests: the agent's `steps` drive the loaded page and its `bridge` mocks answer the
 * page's bridge calls, so a sensor or bridge app can be checked without hardware. Both are untrusted agent
 * text: they are validated here, capped, and reach the page only as JSON data (`JSON.parse` of a string
 * literal), never as code.
 */
object VerifyScript {
    const val MAX_STEPS = 40
    const val MAX_JSON = 32 * 1024
    const val SCRIPT_MS = 12_000L
    const val RETRY_MS = 1_500
    const val MAX_WAIT_MS = 3_000
    const val MAX_EMIT_MS = 1_000
    const val DEFAULT_EMIT_MS = 20
    const val MAX_ASK = 200
    private const val MAX_MOCKS = 64
    private const val MAX_SELECTOR = 200
    private const val MAX_CALLS_SHOWN = 20

    private val OPS = listOf("click", "scroll", "type", "emit", "wait", "assert", "ask")
    private val CHECKS = listOf("text", "contains", "visible", "exists", "value", "attr")
    private val METHOD = Regex("[A-Za-z][A-Za-z0-9_.]{0,63}")
    private val EVENT = Regex("[A-Za-z][A-Za-z0-9_.:-]{0,63}")
    private const val COMMON =
        "{\"click\":sel} {\"scroll\":sel} {\"type\":sel,\"text\":s} " +
            "{\"wait\":ms} {\"assert\":sel, one of \"text\"|\"contains\"|\"visible\"|\"exists\"|\"value\"|\"attr\"}"
    private const val SHAPES = "a step is one of $COMMON {\"emit\":event,\"data\":obj|[obj],\"ms\":n}"
    private const val PREVIEW_SHAPES =
        "a step is one of $COMMON {\"ask\":\"what the user should do\"}, optionally with " +
            "\"options\":[2..6 choices] and \"expect\":one of them, or \"input\":\"text\"|\"number\""

    /** Labels the launcher adds itself (Skip); compared by [escapeKey]. */
    private val ESCAPES = setOf("skip", "cant tell", "cannot tell", "none of these", "n/a", "na", "not applicable", "doesnt fit", "dont know")

    /** Which tool parses: apps.verify (fake bridge, `emit`) or apps.preview (real bridge, `ask`). */
    enum class Mode { VERIFY, PREVIEW }

    /**
     * A validated apps.preview question. [options] is empty unless a choice; [input] is "text" or "number" or null;
     * [expect] is set only with [options] and is one of them.
     */
    data class AskSpec(val text: String, val options: List<String> = emptyList(), val input: String? = null, val expect: String? = null)

    /** A validated step: [json] is the normalized form the runner reads, [label] the output line. */
    data class Step(val label: String, val json: JsonObject) {
        /** apps.preview: the spec of an `ask` step (answered in the Preview banner, not in the page); else null. */
        val ask: AskSpec?
            get() {
                if ((json["op"] as? JsonPrimitive)?.contentOrNull != "ask") return null
                return AskSpec(
                    text = text(json["text"]).orEmpty(),
                    options = (json["options"] as? JsonArray).orEmpty().mapNotNull { text(it) },
                    input = text(json["input"]),
                    expect = text(json["expect"]),
                )
            }
    }

    /** How far one runner pass got: [done] steps passed; [fail] why the next one failed (null = no failure). */
    data class Progress(val done: Int, val fail: String?)

    data class Report(val text: String, val failedStep: Int?)

    /** Null when absent or empty; throws [IllegalArgumentException] naming the bad step. */
    fun parseSteps(raw: JsonElement?, mode: Mode = Mode.VERIFY): List<Step>? {
        val arr = decode(raw, "steps") ?: return null
        if (arr !is JsonArray) throw IllegalArgumentException("steps must be a JSON array of step objects; ${shapes(mode)}")
        if (arr.isEmpty()) return null
        if (arr.size > MAX_STEPS) throw IllegalArgumentException("at most $MAX_STEPS steps")
        return arr.mapIndexed { i, el -> step(i + 1, el as? JsonObject ?: bad(i + 1, "must be an object"), mode) }
    }

    private fun shapes(mode: Mode) = if (mode == Mode.PREVIEW) PREVIEW_SHAPES else SHAPES

    /** Fake replies by method name, normalized to `{"value":v}` or `{"error":{code,message}}`; null when absent. */
    fun parseBridge(raw: JsonElement?): JsonObject? {
        val obj = decode(raw, "bridge") ?: return null
        if (obj !is JsonObject) throw IllegalArgumentException("bridge must be a JSON object of fake replies by method name, e.g. {\"sensors.list\":[...]}")
        if (obj.size > MAX_MOCKS) throw IllegalArgumentException("bridge: at most $MAX_MOCKS methods")
        return JsonObject(
            obj.mapValues { (method, value) ->
                if (!METHOD.matches(method)) throw IllegalArgumentException("bridge: \"${clip(method)}\" is not a method name (e.g. sensors.list)")
                val err = (value as? JsonObject)?.takeIf { it.keys == setOf("error") }?.get("error")
                if (err == null) {
                    JsonObject(mapOf("value" to value))
                } else {
                    val code = ((err as? JsonObject)?.get("code") ?: err).let { (it as? JsonPrimitive)?.contentOrNull }
                    if (code.isNullOrBlank()) {
                        throw IllegalArgumentException("bridge.$method: error needs a code, e.g. {\"error\":{\"code\":\"unsupported\",\"message\":\"...\"}}")
                    }
                    val message = ((err as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: code
                    buildJsonObject {
                        put("error", buildJsonObject { put("code", code.take(64)); put("message", message.take(300)) })
                    }
                }
            },
        )
    }

    /** The verify stub's config: `allowed` like a granted app, plus the mocks. A JS expression. */
    fun bridgeConfigJs(allowed: Collection<String>, mocks: JsonObject?): String =
        literal(
            buildJsonObject {
                put("allowed", JsonArray(allowed.map { JsonPrimitive(it) }))
                put("mocks", mocks ?: JsonObject(emptyMap()))
            },
        )

    /**
     * The runner applied to [steps] (no `ask` step: those are answered natively); progress lands in `window.__deftRun`,
     * read with [POLL_JS]. The whole pass stops after [scriptMs].
     */
    fun runJs(steps: List<Step>, scriptMs: Long = SCRIPT_MS): String =
        "($RUN_JS)(${literal(JsonArray(steps.map { it.json }))},$scriptMs)"

    /** How far the pass of [size] steps in [raw] (a [POLL_JS] result) got; [lost] says why it never reported its end. */
    fun progress(size: Int, raw: String, lost: String? = null): Progress {
        val r = run(raw)
        val done = (r?.get("done") as? JsonPrimitive)?.intOrNull?.coerceIn(0, size) ?: 0
        val ended = (r?.get("end") as? JsonPrimitive)?.booleanOrNull == true
        val fail = (r?.get("fail") as? JsonPrimitive)?.contentOrNull
        val reason = if (ended) fail else lost ?: "did not finish"
        return Progress(done, if (done < size) reason else null)
    }

    /**
     * The `steps:` lines: `ok N label` for the [done] first [labels], then `FAIL N label: reason` for a [fail], or a
     * `skipped` line with [stopped] as the reason (the run ended without blaming a step), then `skipped` for the rest.
     * Returns the text (ending in a newline) and the failed step number.
     */
    fun stepLines(labels: List<String>, done: Int, fail: String?, stopped: String? = null): Pair<String, Int?> {
        val out = StringBuilder("steps:")
        val n = labels.size
        val d = done.coerceIn(0, n)
        for (i in 0 until d) out.append("\nok ").append(i + 1).append(' ').append(labels[i])
        var failed: Int? = null
        if (d < n && fail != null) {
            failed = d + 1
            out.append("\nFAIL ").append(failed).append(' ').append(labels[d]).append(": ").append(WebViewFindings.clip(fail))
            if (failed < n) out.append("\nskipped ").append(range(failed + 1, n))
        } else if (d < n && stopped != null) {
            out.append("\nskipped ").append(range(d + 1, n)).append(": ").append(WebViewFindings.clip(stopped))
        }
        out.append('\n')
        return out.toString() to failed
    }

    private fun range(from: Int, to: Int) = if (from < to) "$from..$to" else "$from"

    /** True once the runner reported its end in [raw] (a [POLL_JS] result). */
    fun ended(raw: String): Boolean = (run(raw)?.get("end") as? JsonPrimitive)?.booleanOrNull == true

    fun hasRun(raw: String): Boolean = run(raw) != null

    /**
     * The `steps` section: one line per step, then the bridge calls in order. [lost] says why the runner
     * never reported its end (hang, renderer death); the step it was on fails with that reason.
     */
    fun report(steps: List<Step>?, raw: String, lost: String? = null): Report {
        val obj = parse(raw)
        val out = StringBuilder()
        var failed: Int? = null
        if (steps != null) {
            val p = progress(steps.size, raw, lost)
            val (text, f) = stepLines(steps.map { it.label }, p.done, p.fail)
            out.append(text)
            failed = f
        }
        val calls = (obj?.get("c") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val more = (obj?.get("more") as? JsonPrimitive)?.intOrNull ?: 0
        out.append("bridge calls: ")
        if (calls.isEmpty()) out.append("none")
        out.append(calls.take(MAX_CALLS_SHOWN).joinToString(", ") { WebViewFindings.clip(it).take(100) })
        val hidden = (calls.size - MAX_CALLS_SHOWN).coerceAtLeast(0) + more
        if (hidden > 0) out.append(", … +").append(hidden).append(" more")
        return Report(out.toString(), failed)
    }

    private fun step(n: Int, o: JsonObject, mode: Mode): Step {
        val shapes = shapes(mode)
        val modeOps = OPS.filter { it != (if (mode == Mode.PREVIEW) "emit" else "ask") }
        val ops = o.keys.filter { it in OPS }
        if (ops.size != 1) bad(n, "needs exactly one of ${modeOps.joinToString()}; $shapes")
        val op = ops[0]
        if (mode == Mode.PREVIEW && op == "emit") {
            bad(n, "emit fakes bridge events and works only in apps.verify; apps.preview uses the real bridge: use an ask step to have the user cause the real event")
        }
        if (mode == Mode.VERIFY && op == "ask") {
            bad(n, "ask works only in apps.preview (the real bridge in the visible Preview); apps.verify fakes events with emit")
        }
        fun allow(vararg keys: String) {
            o.keys.firstOrNull { it != op && it !in keys }?.let { bad(n, "unknown key \"${clip(it)}\" for $op; $shapes") }
        }
        fun sel(): String {
            val s = text(o[op]) ?: bad(n, "$op needs a CSS selector string")
            if (s.isBlank() || s.length > MAX_SELECTOR) bad(n, "selector must be 1..$MAX_SELECTOR characters")
            return s
        }
        return when (op) {
            "click", "scroll" -> {
                allow()
                val s = sel()
                Step("$op ${clip(s)}", obj("op" to op, "sel" to s))
            }
            "type" -> {
                allow("text")
                val s = sel()
                val t = text(o["text"]) ?: bad(n, "type needs \"text\"")
                Step("type ${clip(s)} ${quote(t)}", obj("op" to op, "sel" to s, "text" to t))
            }
            "ask" -> {
                allow("options", "input", "expect")
                val t = text(o[op])?.trim()?.replace(Regex("\\s+"), " ") ?: bad(n, "ask needs the text to show the user")
                if (t.isEmpty() || t.length > MAX_ASK) bad(n, "ask text must be 1..$MAX_ASK characters")
                val options = o["options"]?.let { askOptions(n, it) }
                val input = o["input"]?.let { v -> text(v)?.takeIf { it == "text" || it == "number" } ?: bad(n, "input must be \"text\" or \"number\"") }
                if (input != null && options != null) bad(n, "input cannot be combined with options")
                val expect = o["expect"]?.let { v -> (text(v) ?: bad(n, "expect must be a string")).trim().replace(Regex("\\s+"), " ") }
                if (expect != null) {
                    val list = options ?: bad(n, "expect needs options, e.g. \"options\":[\"Yes\",\"No\"]")
                    if (expect !in list) bad(n, "expect \"${clip(expect)}\" is not one of the options")
                }
                val json = buildJsonObject {
                    put("op", op)
                    put("text", t)
                    if (options != null) put("options", JsonArray(options.map { JsonPrimitive(it) }))
                    if (input != null) put("input", input)
                    if (expect != null) put("expect", expect)
                }
                Step("ask", json)
            }
            "wait" -> {
                allow()
                val ms = num(o[op], 0, MAX_WAIT_MS) ?: bad(n, "wait needs milliseconds 0..$MAX_WAIT_MS")
                Step("wait $ms", obj("op" to op, "ms" to ms))
            }
            "emit" -> {
                allow("data", "ms")
                val event = text(o[op])?.takeIf { EVENT.matches(it) } ?: bad(n, "emit needs an event name, e.g. sensors.reading")
                val data = o["data"] ?: JsonObject(emptyMap())
                if (data !is JsonObject && data !is JsonArray) bad(n, "emit data must be an object or an array of objects")
                if (data is JsonArray && data.isEmpty()) bad(n, "emit data array is empty")
                val ms = if (o["ms"] == null) DEFAULT_EMIT_MS else num(o["ms"], 0, MAX_EMIT_MS) ?: bad(n, "emit ms must be 0..$MAX_EMIT_MS")
                val count = if (data is JsonArray) " ×${data.size}" else ""
                Step("emit $event$count", JsonObject(mapOf("op" to JsonPrimitive(op), "event" to JsonPrimitive(event), "data" to data, "ms" to JsonPrimitive(ms))))
            }
            else -> {
                val checks = o.keys.filter { it != op }
                checks.firstOrNull { it !in CHECKS }?.let { bad(n, "unknown key \"${clip(it)}\" for assert; checks: ${CHECKS.joinToString()}") }
                if (checks.size != 1) bad(n, "assert needs exactly one of ${CHECKS.joinToString()}")
                val s = sel()
                val check = checks[0]
                val v = o[check]
                when (check) {
                    "visible", "exists" -> {
                        val b = (v as? JsonPrimitive)?.booleanOrNull ?: bad(n, "$check must be true or false")
                        val word = if (check == "visible") (if (b) "visible" else "hidden") else (if (b) "exists" else "absent")
                        Step("assert ${clip(s)} $word", JsonObject(mapOf("op" to JsonPrimitive(op), "sel" to JsonPrimitive(s), "check" to JsonPrimitive(check), "want" to JsonPrimitive(b))))
                    }
                    "attr" -> {
                        val a = (v as? JsonObject)?.takeIf { it.size == 1 } ?: bad(n, "attr must be one {\"name\":\"value\"} pair (value null = absent)")
                        val (name, want) = a.entries.first()
                        if (name.isBlank() || name.length > 64) bad(n, "attr name must be 1..64 characters")
                        val w = if (want is JsonNull) null else text(want) ?: bad(n, "attr value must be a string or null")
                        Step(
                            "assert ${clip(s)} attr ${clip(name)}=${w?.let { quote(it) } ?: "absent"}",
                            JsonObject(mapOf("op" to JsonPrimitive(op), "sel" to JsonPrimitive(s), "check" to JsonPrimitive(check), "name" to JsonPrimitive(name), "want" to JsonPrimitive(w))),
                        )
                    }
                    else -> {
                        val w = text(v) ?: bad(n, "$check must be a string")
                        Step("assert ${clip(s)} $check ${quote(w)}", obj("op" to op, "sel" to s, "check" to check, "want" to w))
                    }
                }
            }
        }
    }

    /** Accepts the value itself or a JSON string of it (models send both); caps its size. */
    private fun decode(raw: JsonElement?, name: String): JsonElement? {
        if (raw == null || raw is JsonNull) return null
        val el = if (raw is JsonPrimitive && raw.isString) {
            if (raw.content.isBlank()) return null
            if (raw.content.length > MAX_JSON) throw IllegalArgumentException("$name is too large (max ${MAX_JSON / 1024} KB)")
            runCatching { Json.parseToJsonElement(raw.content) }.getOrElse { throw IllegalArgumentException("$name is not valid JSON: ${clip(it.message.orEmpty())}") }
        } else raw
        if (el.toString().length > MAX_JSON) throw IllegalArgumentException("$name is too large (max ${MAX_JSON / 1024} KB)")
        return el
    }

    /** A JS expression yielding [json]: JSON.parse of a JSON-escaped string, so `__proto__` keys stay plain data. */
    private fun literal(json: JsonElement): String =
        "JSON.parse(" + JsonPrimitive(json.toString()).toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")"

    private fun parse(raw: String): JsonObject? =
        if (raw.isBlank()) null else runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    private fun run(raw: String): JsonObject? = parse(raw)?.get("r") as? JsonObject

    private fun text(v: JsonElement?): String? = (v as? JsonPrimitive)?.contentOrNull

    /** 2..6 choices of 1..40 characters, unique ignoring case, none of them a launcher-owned escape label. */
    private fun askOptions(n: Int, v: JsonElement): List<String> {
        val arr = v as? JsonArray ?: bad(n, "options must be an array of 2..6 strings")
        if (arr.size !in 2..6) bad(n, "options: give 2..6 choices")
        val out = arr.map { el ->
            val s = (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.replace(Regex("\\s+"), " ")
                ?: bad(n, "options must be strings")
            if (s.isEmpty() || s.length > 40) bad(n, "options: each choice is 1..40 characters")
            if (escapeKey(s) in ESCAPES) bad(n, "options: \"${clip(s)}\" is not needed, the launcher always adds a Skip button")
            s
        }
        val seen = HashSet<String>()
        for (s in out) if (!seen.add(s.lowercase())) bad(n, "options: \"${clip(s)}\" is listed twice")
        return out
    }

    /** Lower case, typographic apostrophes and apostrophes dropped, trailing punctuation dropped. */
    private fun escapeKey(s: String): String =
        s.trim().lowercase().replace('’', '\'').replace("'", "").trimEnd { it in ".,!?;:…" }.trim()

    private fun num(v: JsonElement?, min: Int, max: Int): Int? {
        val d = (v as? JsonPrimitive)?.doubleOrNull ?: return null
        return if (d.isFinite() && d >= min && d <= max) d.toInt() else null
    }

    private fun obj(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
        pairs.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) },
    )

    private fun clip(s: String): String = s.replace(Regex("\\s+"), " ").let { if (it.length > 60) it.take(60) + "…" else it }

    private fun quote(s: String): String = JsonPrimitive(clip(s)).toString()

    private fun bad(n: Int, msg: String): Nothing = throw IllegalArgumentException("step $n: $msg")

    /** Reads the runner's progress and the stub's call log in one round trip; marks the progress as seen. */
    const val POLL_JS =
        "(function(){var V=window.__deftVerify,R=window.__deftRun;if(R)R.seen=R.done;" +
            "return JSON.stringify({r:R||null,c:V?V.calls:[],more:V?V.more:0})})()"

    /**
     * Runs the steps in order. Element steps and asserts retry every 50 ms for up to [RETRY_MS] so async renders
     * and view transitions settle; the whole run stops after `scriptMs` ([SCRIPT_MS] for apps.verify). Handler errors reach the console ring
     * through the document-start hook (listener errors are reported, never thrown into click()).
     */
    private val RUN_JS =
        """
        function (steps, scriptMs) {
          var V = window.__deftVerify, run = {done: 0, fail: null, end: false};
          window.__deftRun = run;
          var deadline = Date.now() + scriptMs, i = 0, held = -1, holdUntil = 0;
          function norm(s) { return String(s == null ? '' : s).replace(/\s+/g, ' ').trim(); }
          function clip(s) { s = String(s); return s.length > 60 ? s.slice(0, 60) + '…' : s; }
          function visible(el) {
            if (!el || !el.isConnected) return false;
            var r = el.getBoundingClientRect();
            if (r.width <= 0 || r.height <= 0) return false;
            var cs = getComputedStyle(el);
            return cs.visibility !== 'hidden' && cs.display !== 'none';
          }
          function finish(msg) { if (msg != null) run.fail = String(msg); run.end = true; }
          function pass() { run.done++; setTimeout(next, 0); }
          // check() returns null once the step holds, else why not; retried until RETRY_MS or the deadline.
          function retry(check, ok) {
            var until = Math.min(Date.now() + $RETRY_MS, deadline), last;
            (function tick() {
              try { last = check(); } catch (e) { finish(e && e.name === 'SyntaxError' ? 'invalid CSS selector' : String(e)); return; }
              if (last == null) { try { ok(); } catch (e) { finish(String(e)); } return; }
              if (Date.now() >= until) { finish(Date.now() >= deadline ? 'script time limit reached; ' + last : last); return; }
              setTimeout(tick, 50);
            })();
          }
          function q(sel) { return document.querySelector(sel); }
          function shown(sel) { var el = q(sel); return !el ? 'no element matches' : visible(el) ? null : 'element is not visible'; }
          function check(s) {
            var el = q(s.sel), t;
            if (s.check === 'exists') return !!el === s.want ? null : (el ? 'an element matches' : 'no element matches');
            if (s.check === 'visible') { var v = visible(el); return v === s.want ? null : (!el ? 'no element matches' : v ? 'got visible' : 'got hidden'); }
            if (!el) return 'no element matches';
            if (s.check === 'text') { t = norm(el.innerText); return t === norm(s.want) ? null : 'got "' + clip(t) + '"'; }
            if (s.check === 'contains') { t = norm(el.innerText); return t.indexOf(norm(s.want)) >= 0 ? null : 'got "' + clip(t) + '"'; }
            if (s.check === 'value') { t = el.value == null ? '' : String(el.value); return t === s.want ? null : 'got "' + clip(t) + '"'; }
            var a = el.getAttribute(s.name);
            return a === s.want ? null : (a == null ? 'got no ' + s.name : 'got ' + s.name + '="' + clip(a) + '"');
          }
          function next() {
            if (i >= steps.length) { finish(null); return; }
            if (Date.now() >= deadline) { finish('script time limit reached'); return; }
            // A step that runs page code first waits (briefly) for the poller to see the progress so far, so a
            // handler that blocks the page is blamed on its own step, not on an earlier one.
            var op = steps[i].op;
            if (op === 'click' || op === 'type' || op === 'emit') {
              if (held !== i) { held = i; holdUntil = Date.now() + 400; }
              if (run.seen !== run.done && Date.now() < holdUntil) { setTimeout(next, 10); return; }
            }
            var s = steps[i++];
            if (s.op === 'click') retry(function () { return shown(s.sel); }, function () {
              var el = q(s.sel); el.scrollIntoView({block: 'center'}); el.click(); pass();
            });
            else if (s.op === 'scroll') retry(function () { return q(s.sel) ? null : 'no element matches'; }, function () {
              q(s.sel).scrollIntoView({block: 'center'}); pass();
            });
            else if (s.op === 'type') retry(function () {
              var w = shown(s.sel); return w || ('value' in q(s.sel) ? null : 'element has no value (not an input)');
            }, function () {
              var el = q(s.sel);
              if (el.focus) el.focus();
              el.value = s.text;
              el.dispatchEvent(new Event('input', {bubbles: true}));
              el.dispatchEvent(new Event('change', {bubbles: true}));
              pass();
            });
            else if (s.op === 'wait') setTimeout(pass, Math.max(0, Math.min(s.ms, deadline - Date.now())));
            else if (s.op === 'emit') retry(function () {
              return !V ? 'no bridge in this page' : V.count(s.event) ? null : 'nothing listens for ' + s.event + ' (no on() subscription yet)';
            }, function () {
              var items = Array.isArray(s.data) ? s.data : [s.data], k = 0;
              (function feed() {
                if (k >= items.length) { pass(); return; }
                if (Date.now() >= deadline) { finish('script time limit reached after ' + k + ' items'); return; }
                V.emit(s.event, items[k++]);
                if (k >= items.length) pass(); else setTimeout(feed, s.ms);
              })();
            });
            else retry(function () { return check(s); }, pass);
          }
          next();
        }
        """.trimIndent()
}
