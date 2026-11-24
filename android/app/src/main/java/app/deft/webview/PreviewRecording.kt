package app.deft.webview

import app.deft.bridge.BridgeTap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

/**
 * What the app did with the real bridge during one agent `apps.preview` run: calls with their outcome, subscriptions,
 * events (count, and per index of a numeric `values` array the range or the few distinct values) and console
 * errors/warnings. Kept small: counts and ranges, never payloads. The text is device and app data (untrusted).
 */
class PreviewRecording : BridgeTap {
    private val lock = Any()
    private val calls = LinkedHashMap<String, Int>()
    private val pending = HashMap<String, Int>()
    private val subs = LinkedHashMap<String, Int>()
    private val events = LinkedHashMap<String, EventStats>()
    private val console = ArrayList<String>()
    private var errors = 0
    private var warnings = 0

    private class EventStats {
        var count = 0
        val indexes = ArrayList<IndexStats>()
    }

    private class IndexStats {
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        var distinct: LinkedHashSet<Double>? = LinkedHashSet()

        fun add(v: Double) {
            if (v < min) min = v
            if (v > max) max = v
            val d = distinct ?: return
            d.add(v)
            if (d.size > MAX_DISTINCT) distinct = null
        }
    }

    override fun call(method: String) {
        synchronized(lock) { bump(pending, method.take(64)) }
    }

    override fun result(method: String, ok: Boolean, code: String?) {
        val m = method.take(64)
        synchronized(lock) {
            pending[m]?.let { if (it <= 1) pending.remove(m) else pending[m] = it - 1 }
            bump(calls, if (ok) "$m ok" else "$m error ${code.orEmpty().take(32)}")
        }
    }

    override fun subscribe(event: String) {
        synchronized(lock) { bump(subs, event.take(64)) }
    }

    override fun event(event: String, payloadJson: String) {
        val values = numericValues(payloadJson)
        synchronized(lock) {
            val stats = events[event.take(64)] ?: if (events.size < MAX_KEYS) EventStats().also { events[event.take(64)] = it } else return
            stats.count++
            values?.forEachIndexed { i, v ->
                if (i >= MAX_INDEXES) return@forEachIndexed
                while (stats.indexes.size <= i) stats.indexes.add(IndexStats())
                if (v != null) stats.indexes[i].add(v)
            }
        }
    }

    /** One console line of the page ([level] ERROR or WARN; uncaught errors arrive as `[uncaught] …` errors). */
    fun console(level: String, text: String) {
        synchronized(lock) {
            when (level) {
                "ERROR" -> errors++
                "WARN" -> warnings++
                else -> return
            }
            if (console.size < MAX_CONSOLE) console.add("$level ${WebViewFindings.clip(text).take(200)}")
        }
    }

    /** The recording section of the report, at most [MAX_TEXT] characters. */
    fun format(): String = synchronized(lock) {
        val out = StringBuilder()
        out.append("bridge calls: ")
        val callItems = calls.map { (k, n) -> if (n > 1) "$k ×$n" else k } + pending.map { (m, n) -> if (n > 1) "$m pending ×$n" else "$m pending" }
        out.append(if (callItems.isEmpty()) "none" else callItems.joinToString(", "))
        out.append("\nsubscriptions: ")
        out.append(if (subs.isEmpty()) "none" else subs.entries.joinToString(", ") { (k, n) -> if (n > 1) "$k ×$n" else k })
        out.append("\nevents: ")
        if (events.isEmpty()) out.append("none")
        out.append(
            events.entries.joinToString("; ") { (name, s) ->
                val idx = s.indexes.mapIndexedNotNull { i, x ->
                    if (x.min > x.max) return@mapIndexedNotNull null
                    val d = x.distinct
                    if (d != null) "[$i] {${d.joinToString(",") { num(it) }}}" else "[$i] ${num(x.min)}..${num(x.max)}"
                }
                "$name ×${s.count}" + if (idx.isEmpty()) "" else " values " + idx.joinToString(" ")
            },
        )
        out.append("\nconsole: ")
        if (errors == 0 && warnings == 0) {
            out.append("no errors or warnings")
        } else {
            out.append(errors).append(" errors, ").append(warnings).append(" warnings")
            console.forEach { out.append('\n').append(it) }
            val more = errors + warnings - console.size
            if (more > 0) out.append("\n… +").append(more).append(" more (apps.debug)")
        }
        val text = out.toString()
        if (text.length <= MAX_TEXT) text else text.take(MAX_TEXT - 1) + "…"
    }

    private fun bump(map: MutableMap<String, Int>, key: String) {
        val now = map[key]
        if (now == null && map.size >= MAX_KEYS) return
        map[key] = (now ?: 0) + 1
    }

    companion object {
        const val MAX_TEXT = 4 * 1024
        private const val MAX_DISTINCT = 8
        private const val MAX_INDEXES = 8
        private const val MAX_KEYS = 40
        private const val MAX_CONSOLE = 12
        private val json = Json { ignoreUnknownKeys = true }

        /** The `values` array of an event payload as numbers (null entries for non-numbers); null when there is none. */
        internal fun numericValues(payloadJson: String): List<Double?>? {
            if (!payloadJson.contains("\"values\"")) return null
            val obj = runCatching { json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull() ?: return null
            val arr = obj["values"] as? JsonArray ?: return null
            return arr.map { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.doubleOrNull?.takeIf { d -> d.isFinite() } }
        }

        /** Short number: whole numbers as is, others with up to 3 decimals. */
        internal fun num(v: Double): String {
            if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
            return String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
        }
    }
}
