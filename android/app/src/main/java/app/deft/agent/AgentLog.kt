package app.deft.agent

import android.content.Context
import app.deft.DeftApp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

class AgentLog(
    val sessionId: String,
    val dir: File,
) {
    private val events = File(dir, "events.jsonl")
    private val countersFile = File(dir, "counters.json")
    private val lock = Any()
    private var lintErrors = 0
    private var lintWarnings = 0
    private var verifyFails = 0
    private var verifyRounds = 0
    private var roundsToFirstGreenVerify: Int? = null
    private var suppressions = 0
    private val lintErrorsPerRound = mutableListOf<Int>()
    private val lintWarningsPerRound = mutableListOf<Int>()
    private val seenSuppressions = HashSet<String>()

    fun event(type: String, extra: JsonObject = JsonObject(emptyMap())) {
        val obj = buildJsonObject {
            put("ts", JsonPrimitive(System.currentTimeMillis()))
            put("type", JsonPrimitive(type))
            extra.forEach { (k, v) -> put(k, v) }
        }
        val line = json.encodeToString(JsonObject.serializer(), obj) + "\n"
        synchronized(lock) {
            dir.mkdirs()
            if (events.isFile && events.length() > MAX_BYTES) {
                val prev = File(dir, "events.prev.jsonl")
                if (prev.exists()) prev.delete()
                events.renameTo(prev)
            }
            events.appendText(line)
        }
    }

    fun addLint(errors: Int, warnings: Int) {
        synchronized(lock) {
            lintErrors += errors.coerceAtLeast(0)
            lintWarnings += warnings.coerceAtLeast(0)
            lintErrorsPerRound += errors.coerceAtLeast(0)
            lintWarningsPerRound += warnings.coerceAtLeast(0)
            persistCounters()
        }
    }

    fun addSuppressions(keys: Collection<String>) {
        synchronized(lock) {
            var added = 0
            for (key in keys) {
                if (key.isNotBlank() && seenSuppressions.add(key)) added++
            }
            if (added == 0) return
            suppressions += added
            persistCounters()
        }
    }

    fun noteVerify(green: Boolean) {
        synchronized(lock) {
            verifyRounds += 1
            if (!green) verifyFails += 1
            else if (roundsToFirstGreenVerify == null) roundsToFirstGreenVerify = verifyRounds
            persistCounters()
        }
    }

    private fun persistCounters() {
        val body = buildJsonObject {
            put("kind", "agent-log")
            put("lintErrors", lintErrors)
            put("lintWarnings", lintWarnings)
            put("verifyFails", verifyFails)
            put("verifyRounds", verifyRounds)
            roundsToFirstGreenVerify?.let { put("roundsToFirstGreenVerify", it) }
            put("suppressions", suppressions)
            put(
                "lintErrorsPerRound",
                buildJsonArray {
                    for (count in lintErrorsPerRound) add(JsonPrimitive(count))
                },
            )
            put(
                "lintWarningsPerRound",
                buildJsonArray {
                    for (count in lintWarningsPerRound) add(JsonPrimitive(count))
                },
            )
        }
        dir.mkdirs()
        countersFile.writeText(json.encodeToString(JsonObject.serializer(), body) + "\n")
    }

    fun writeToolOut(callId: String, body: String): File {
        val folder = File(dir, "tool-out")
        folder.mkdirs()
        val file = File(folder, "${AgentContext.safeId(callId)}.txt")
        file.writeText(body)
        return file
    }

    fun error(err: AgentError) {
        event(
            "error",
            buildJsonObject {
                put("code", err.code)
                put("message", err.message.take(500))
                err.http?.let { put("http", it) }
                err.finishReason?.let { put("finishReason", it) }
                err.contextBytes?.let { put("contextBytes", it) }
            },
        )
    }

    companion object {
        private const val MAX_BYTES = 256L * 1024
        private const val ERROR_NOTE_MAX = 200
        private const val FINDINGS_NOTE_MAX = 400
        private val VERIFY_TOOLS = setOf("apps.verify", "canvas.verify", "apps.debug", "canvas.debug")
        private val FINDING_LINE = Regex("^(ERROR|WARN)\\b")

        /**
         * The short `note` of a tool event in events.jsonl. Privacy rule: results are otherwise logged as byte counts
         * only. The two exceptions are (1) an error result: its error text, at most 200 characters, and (2) the
         * verify/debug tools: only their ERROR/WARN lines (lint-style findings), at most 400 characters. Never file
         * contents, fetched web content, user messages or the params of any tool. Null when there is nothing to note.
         */
        fun toolNote(name: String, result: String): String? {
            if (result.startsWith("error:")) return flatten(result).take(ERROR_NOTE_MAX)
            if (name !in VERIFY_TOOLS) return null
            val lines = result.lineSequence().map { it.trim() }.filter { FINDING_LINE.containsMatchIn(it) }.map { flatten(it) }.toList()
            if (lines.isEmpty()) return null
            return lines.joinToString(" | ").take(FINDINGS_NOTE_MAX)
        }

        // Query strings and fragments can carry tokens or fetched data: keep only scheme, host and path.
        private val URL_TAIL = Regex("(https?://[^\\s?#\"'<>)]*)[?#][^\\s\"'<>)]*")

        private fun flatten(text: String) = URL_TAIL.replace(text.replace(Regex("\\s+"), " ").trim(), "$1")
        private val json = Json { encodeDefaults = false }

        fun create(context: Context, workspaceId: String): AgentLog {
            val id = UUID.randomUUID().toString()
            val dir = File(context.filesDir, "agent/sessions/$id")
            dir.mkdirs()
            File(context.filesDir, "agent/current").writeText(id)
            val cfg = DeftApp.instance.configStore.config.value.agent
            val meta = buildJsonObject {
                put("id", id)
                put("startedAt", System.currentTimeMillis())
                put("model", cfg.model)
                put("maxTokens", cfg.maxTokens)
                put("contextWindow", cfg.contextWindow)
                put("workspace", workspaceId)
            }
            File(dir, "meta.json").writeText(json.encodeToString(JsonObject.serializer(), meta) + "\n")
            val log = AgentLog(id, dir)
            log.event("session_start")
            return log
        }
    }
}
