package app.deft.agent

import android.content.Context
import app.deft.DeftApp
import app.deft.config.AgentConfig
import app.deft.workspace.AgentWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.min

class AgentHarness(
    private val context: Context,
    val workspaceId: String,
    val log: AgentLog = AgentLog.create(context, workspaceId),
) {
    private val tools = AgentTools(context, log.dir, workspaceId, log)
    private val history = mutableListOf<ChatMessage>()
    private var system: String? = null
    private var lastStatusLine: String? = null
    private var lastPromptTokens: Int? = null
    private val isHome = AgentWorkspace.isHomeSlug(workspaceId)

    val sessionId: String get() = log.sessionId

    /** The tools the next request offers; custom tools come and go with [AgentTools.refreshCustomTools]. */
    val toolDefs: List<ToolDef>
        get() = tools.specs.sortedBy { it.name }.map {
            ToolDef(function = ToolFn(it.name, it.description, it.parameters))
        }

    /**
     * One user turn. [text] is what the model reads (the typed text plus any inlined text files); [typed] is what the
     * user wrote, which is what gets logged and what tool guards read. Images are data URLs, [files] are PDFs.
     * Only counts go to the event log.
     */
    suspend fun sendUser(
        text: String,
        typed: String,
        images: List<String>,
        files: List<FilePart>,
        textFiles: Int,
        onText: suspend (String) -> Unit,
        onTool: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
    ): TurnResult {
        ensureSystem()
        noteSessions()
        log.event(
            "user",
            buildJsonObject {
                put("text", typed.take(2000))
                if (images.isNotEmpty()) put("images", images.size)
                if (files.isNotEmpty()) put("files", files.size)
                if (textFiles > 0) put("text_files", textFiles)
            },
        )
        history += ChatMessage(
            role = "user",
            content = text,
            images = images.ifEmpty { null },
            files = files.ifEmpty { null },
        )
        tools.latestUserText = typed
        return loop(onText, onTool, onReasoning, proactiveCompact = true)
    }

    suspend fun apply(
        pending: List<PendingTool>,
        onText: suspend (String) -> Unit,
        onTool: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
    ): TurnResult {
        var toolsOk = false
        var wroteOk = false
        var terminalOk = false
        var handoff: String? = null
        for (item in pending) {
            onTool(toolHint(item.call, "applied"))
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    tools.onApplied(item.call.function.name)
                    tools.execute(item.call.function.name, item.call.function.arguments)
                }
            }
            val result = outcome.fold({ it }, { "error: ${it.message}" })
            if (outcome.isSuccess) {
                val name = item.call.function.name
                toolsOk = true
                if (tools.isWrapUpWrite(name)) wroteOk = true
                if (tools.isTerminalWrite(name)) terminalOk = true
                if (tools.isHandoff(name, item.call.function.arguments)) handoff = result
            }
            appendToolResult(item.call, result)
        }
        noteSessions()
        if (handoff != null) return handedOff(handoff, null)
        return loop(
            onText,
            onTool,
            onReasoning,
            proactiveCompact = false,
            startToolsOk = toolsOk,
            startWroteOk = wroteOk,
            startTerminalOk = terminalOk,
        )
    }

    suspend fun reject(
        pending: List<PendingTool>,
        onText: suspend (String) -> Unit,
        onTool: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
    ): TurnResult {
        for (item in pending) {
            onTool(toolHint(item.call, "rejected"))
            appendToolResult(item.call, "rejected by user")
        }
        return loop(onText, onTool, onReasoning, proactiveCompact = false)
    }

    suspend fun answer(
        pending: List<PendingTool>,
        answers: List<String>,
        onText: suspend (String) -> Unit,
        onTool: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
    ): TurnResult {
        pending.forEachIndexed { i, item ->
            onTool(toolHint(item.call, "answered"))
            appendToolResult(item.call, answers.getOrNull(i)?.ifBlank { "skipped" } ?: "skipped")
        }
        return loop(onText, onTool, onReasoning, proactiveCompact = false)
    }

    fun close() {
        tools.close()
        log.event("session_end")
    }

    private suspend fun loop(
        onText: suspend (String) -> Unit,
        onTool: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
        proactiveCompact: Boolean,
        startToolsOk: Boolean = false,
        startWroteOk: Boolean = false,
        startTerminalOk: Boolean = false,
    ): TurnResult {
        val cfg = DeftApp.instance.configStore.config.value.agent
        val key = AgentKeyStore.get(context) ?: run {
            val err = AgentError(AgentError.NO_KEY, "No OpenRouter API key")
            log.error(err)
            return TurnResult(error = err)
        }
        var rounds = 0
        var overflowRetried = false
        var emptyContinues = 0
        var silentEmptyRetry = false
        var toolsOk = startToolsOk
        var wroteOk = startWroteOk
        var terminalOk = startTerminalOk
        var afterWriteNudge = false
        var keepWorkingNudge = false
        val repeats = RepeatGuard()
        while (rounds < MAX_TOOL_ROUNDS) {
            rounds++
            // W5: a tool switched off or applied since the last request shows up in this one.
            withContext(Dispatchers.IO) { tools.refreshCustomTools() }
            AgentContext.pruneToolBodies(history)
            if (proactiveCompact && rounds == 1) {
                maybeCompact(cfg, key)
            }
            val projected = history.toList()
            val bytes = contextBytes(projected)
            onReasoning("Thinking…")
            val reply = try {
                withContext(Dispatchers.IO) {
                    AgentChatApi.stream(
                        apiKey = key,
                        model = cfg.model,
                        maxTokens = cfg.maxTokens,
                        fast = cfg.fast,
                        effort = cfg.effort,
                        messages = projected,
                        tools = toolDefs,
                        sessionId = sessionId,
                        onText = { text ->
                            withContext(Dispatchers.Main) { onText(text) }
                        },
                        onReasoning = { text ->
                            withContext(Dispatchers.Main) { onReasoning(text) }
                        },
                    )
                }
            } catch (e: CancellationException) {
                log.error(AgentError(AgentError.CANCELLED, "stopped"))
                throw e
            } catch (t: Throwable) {
                if (t.isContextOverflow() && !overflowRetried) {
                    overflowRetried = true
                    log.event(
                        "overflow",
                        buildJsonObject {
                            put("contextBytes", bytes)
                            put("estimatedTokens", AgentContext.estimateRequest(projected, toolDefs))
                        },
                    )
                    if (!compact(cfg, key)) {
                        val err = AgentError(
                            AgentError.COMPACT_FAILED,
                            "could not compact after context overflow",
                            contextBytes = bytes,
                        )
                        log.error(err)
                        return TurnResult(error = err)
                    }
                    rounds--
                    continue
                }
                val empty = emptyTurn(t)
                if (empty != null) {
                    val finish = empty.finishReason
                    if (finish == "tool_calls" && !silentEmptyRetry) {
                        silentEmptyRetry = true
                        log.event(
                            "empty_retry",
                            buildJsonObject {
                                put("finishReason", finish)
                                put("contextBytes", bytes)
                            },
                        )
                        onReasoning("Empty tool_calls reply, retrying…")
                        rounds--
                        continue
                    }
                    if (finish != "length" && toolsOk && !wroteOk && !terminalOk && !keepWorkingNudge) {
                        keepWorkingNudge = true
                        history += ChatMessage(role = "user", content = KEEP_WORKING_NUDGE)
                        log.event(
                            "empty_continue",
                            buildJsonObject {
                                put("finishReason", finish.orEmpty())
                                put("nudge", 1)
                                put("keepWorking", true)
                                put("contextBytes", bytes)
                            },
                        )
                        onReasoning("Empty reply, continuing…")
                        continue
                    }
                    if (wroteOk && finish != "length") {
                        if (!afterWriteNudge) {
                            afterWriteNudge = true
                            history += ChatMessage(role = "user", content = AFTER_WORK_NUDGE)
                            log.event(
                                "empty_continue",
                                buildJsonObject {
                                    put("finishReason", finish.orEmpty())
                                    put("nudge", 1)
                                    put("afterTools", true)
                                    put("contextBytes", bytes)
                                },
                            )
                            onReasoning("Empty reply, continuing…")
                            continue
                        }
                        log.event(
                            "empty_done",
                            buildJsonObject {
                                put("finishReason", finish.orEmpty())
                                put("contextBytes", bytes)
                            },
                        )
                        return TurnResult(text = "")
                    }
                    if (terminalOk && finish != "length") {
                        log.event(
                            "empty_done",
                            buildJsonObject {
                                put("finishReason", finish.orEmpty())
                                put("terminal", true)
                                put("contextBytes", bytes)
                            },
                        )
                        return TurnResult(text = "")
                    }
                    if (emptyContinues < MAX_EMPTY_CONTINUES) {
                        emptyContinues++
                        history += ChatMessage(role = "user", content = emptyContinuePrompt(finish))
                        log.event(
                            "empty_continue",
                            buildJsonObject {
                                put("finishReason", finish.orEmpty())
                                put("nudge", emptyContinues)
                                put("contextBytes", bytes)
                            },
                        )
                        onReasoning("Empty reply, continuing…")
                        continue
                    }
                }
                val err = AgentError.from(t, bytes)
                log.error(err)
                return TurnResult(error = err)
            }
            val assistant = reply.message
            silentEmptyRetry = false
            history += assistant
            reply.usage?.promptTokens?.let { lastPromptTokens = it }
            log.event(
                "assistant",
                buildJsonObject {
                    put("textBytes", assistant.content?.length ?: 0)
                    put("finishReason", reply.finishReason.orEmpty())
                    put("contextBytes", bytes)
                    put("estimatedTokens", AgentContext.estimateRequest(projected, toolDefs))
                    put("maxTokens", cfg.maxTokens)
                    reply.usage?.let { u ->
                        put("promptTokens", u.promptTokens)
                        put("completionTokens", u.completionTokens)
                        put("cachedTokens", u.cachedTokens)
                        put("cacheWriteTokens", u.cacheWriteTokens)
                    }
                    put(
                        "toolCalls",
                        buildJsonArray {
                            for (call in assistant.tool_calls.orEmpty()) {
                                add(
                                    buildJsonObject {
                                        put("name", call.function.name)
                                        put("argBytes", call.function.arguments.length)
                                    },
                                )
                            }
                        },
                    )
                },
            )
            val calls = assistant.tool_calls.orEmpty()
            if (calls.isEmpty()) {
                return TurnResult(text = assistant.content.orEmpty())
            }
            val questions = calls.filter { tools.kindOf(it.function.name) == ToolKind.QUESTION }
            val auto = calls.filter {
                tools.kindOf(it.function.name) != ToolKind.QUESTION && !tools.needsApproval(it.function.name, it.function.arguments)
            }
            val applyWrites = calls.filter { tools.needsApproval(it.function.name, it.function.arguments) }.filter { call ->
                val blocked = tools.createGuard(call.function.name, call.function.arguments)
                if (blocked != null) appendToolResult(call, "error: $blocked")
                blocked == null
            }
            var handoff: String? = null
            var stuck: String? = null
            for (call in auto) {
                if (handoff != null || stuck != null) {
                    appendToolResult(call, if (handoff != null) HANDOFF_SKIPPED else STUCK_SKIPPED)
                    continue
                }
                onTool(toolHint(call))
                val outcome = runCatching {
                    withContext(Dispatchers.IO) {
                        tools.execute(call.function.name, call.function.arguments)
                    }
                }
                val result = outcome.fold({ it }, { err ->
                    if (err is IllegalArgumentException && err.message?.contains("json", true) == true) {
                        log.error(AgentError(AgentError.TOOL_ARGS, err.message ?: "bad tool args"))
                    }
                    "error: ${err.message}"
                })
                if (outcome.isSuccess) {
                    val name = call.function.name
                    toolsOk = true
                    if (tools.isWrapUpWrite(name)) wroteOk = true
                    if (tools.isTerminalWrite(name)) terminalOk = true
                    if (tools.isHandoff(name, call.function.arguments)) handoff = result
                }
                val sent = when (val verdict = repeats.check(call.function.name, call.function.arguments, result)) {
                    RepeatGuard.Verdict.Ok -> result
                    is RepeatGuard.Verdict.Note -> "$result\n\n${verdict.text}"
                    is RepeatGuard.Verdict.Stop -> {
                        stuck = verdict.text
                        "$result\n\nnote: the turn stopped here because ${call.function.name} kept returning this same result."
                    }
                }
                appendToolResult(call, sent)
            }
            noteSessions()
            if (handoff != null) {
                for (call in applyWrites + questions) appendToolResult(call, HANDOFF_SKIPPED)
                return handedOff(handoff, assistant.content)
            }
            stuck?.let { stop ->
                for (call in applyWrites + questions) appendToolResult(call, STUCK_SKIPPED)
                log.event("stuck", buildJsonObject { put("rounds", rounds) })
                history += ChatMessage(role = "assistant", content = stop)
                return TurnResult(text = listOfNotNull(assistant.content?.takeIf { it.isNotBlank() }, stop).joinToString("\n\n"))
            }
            if (applyWrites.isNotEmpty()) {
                for (call in applyWrites) {
                    onTool(toolHint(call, "needs Apply"))
                    log.event(
                        "pending_write",
                        buildJsonObject {
                            put("name", call.function.name)
                            put("argBytes", call.function.arguments.length)
                        },
                    )
                }
                val pending = applyWrites.map { call ->
                    val kind = tools.kindOf(call.function.name) ?: ToolKind.WRITE
                    PendingTool(call, kind, tools.previewPending(call.function.name, call.function.arguments))
                }
                return TurnResult(text = assistant.content, pending = pending)
            }
            if (questions.isNotEmpty()) {
                for (call in questions) {
                    onTool(toolHint(call, "question"))
                }
                val pending = questions.map { call ->
                    PendingTool(call, ToolKind.QUESTION, tools.preview(call.function.name, call.function.arguments))
                }
                return TurnResult(text = assistant.content, pending = pending)
            }
        }
        val err = AgentError(AgentError.MAX_ROUNDS, "Stopped after $MAX_TOOL_ROUNDS tool rounds")
        log.error(err)
        return TurnResult(error = err)
    }

    /** The work moved to another tab; end this turn without another model round. */
    private fun handedOff(result: String, assistantText: String?): TurnResult {
        log.event("handoff", buildJsonObject { put("result", result.take(240)) })
        val note = result.replaceFirstChar { it.uppercase() } + "."
        history += ChatMessage(role = "assistant", content = note)
        return TurnResult(text = assistantText?.takeIf { it.isNotBlank() } ?: note)
    }

    private suspend fun appendToolResult(call: ToolCall, result: String) {
        val truncated = withContext(Dispatchers.IO) {
            val cut = AgentContext.truncateTool(result)
            if (cut.truncated) log.writeToolOut(call.id, result)
            cut
        }
        val content = if (truncated.truncated) {
            AgentContext.stubForOverflow(truncated, call.id)
        } else {
            truncated.text
        }
        log.event(
            "tool",
            buildJsonObject {
                put("name", call.function.name)
                put("resultBytes", result.length)
                put("sentBytes", content.length)
                put("truncated", truncated.truncated)
                put("ok", !result.startsWith("error:"))
                AgentLog.toolNote(call.function.name, result)?.let { put("note", it) }
            },
        )
        history += ChatMessage(
            role = "tool",
            content = content,
            tool_call_id = call.id,
            name = call.function.name,
        )
    }

    private fun toolHint(call: ToolCall, suffix: String = ""): String {
        val preview = tools.preview(call.function.name, call.function.arguments)
            .trim()
            .replace('\n', ' ')
            .take(72)
        return buildString {
            append(call.function.name)
            if (preview.isNotBlank()) {
                append(" · ")
                append(preview)
            }
            if (suffix.isNotBlank()) {
                append(" — ")
                append(suffix)
            }
        }
    }

    private fun ensureSystem() {
        if (system != null) return
        val skill = context.assets.open("agent/SKILL.md").bufferedReader().use { it.readText() }
        system = skill
        history.add(0, ChatMessage(role = "system", content = skill))
        val slugText = AgentContext.workspaceLine(workspaceId)
        history.add(1, ChatMessage(role = "system", content = slugText))
        if (isHome) {
            lastStatusLine = AgentSession.statusSystemLine()
            history.add(2, ChatMessage(role = "system", content = lastStatusLine))
        }
        log.event("skill", buildJsonObject { put("bytes", skill.length) })
    }

    private fun noteSessions() {
        if (!isHome) return
        val text = AgentSession.statusSystemLine()
        if (text == lastStatusLine) return
        lastStatusLine = text
        history += ChatMessage(role = "system", content = text)
    }

    private suspend fun maybeCompact(cfg: AgentConfig, key: String) {
        AgentContext.pruneToolBodies(history)
        if (!AgentContext.overBudget(history, toolDefs, cfg, lastPromptTokens)) return
        compact(cfg, key)
    }

    private suspend fun compact(cfg: AgentConfig, key: String): Boolean {
        AgentContext.pruneToolBodies(history)
        val start = AgentContext.firstNonSystem(history)
        var tailAt = AgentContext.repairTailStart(
            history,
            AgentContext.tailStart(history, AgentContext.KEEP_RECENT_TOKENS),
        )
        if (tailAt <= start) return false
        if (history.getOrNull(tailAt)?.role == "tool") {
            log.error(AgentError(AgentError.TRIM_ORPHAN, "compact tail started on a tool result"))
            tailAt = AgentContext.repairTailStart(history, tailAt)
            if (tailAt <= start) return false
        }
        val systems = history.subList(0, start).toList()
        val head = history.subList(start, tailAt).toList()
        val tail = history.subList(tailAt, history.size).toList()
        if (head.isEmpty()) return false
        val instruction = ChatMessage(role = "user", content = AgentContext.COMPACT_PROMPT)
        val live = systems + AgentContext.attachmentsAsText(head) + instruction
        val compactCfg = cfg.copy(maxTokens = min(cfg.maxTokens, AgentContext.SUMMARY_MAX_TOKENS))
        val fallback = AgentContext.overBudget(live, toolDefs, compactCfg, lastPromptTokens)
        val compactMessages = if (fallback) {
            systems + ChatMessage(
                role = "user",
                content = AgentContext.serializeForSummary(head) + "\n\n" + AgentContext.COMPACT_PROMPT,
            )
        } else {
            live
        }
        val compactSession = if (fallback) "$sessionId-compact" else sessionId
        val reply = try {
            withContext(Dispatchers.IO) {
                AgentChatApi.stream(
                    apiKey = key,
                    model = cfg.model,
                    maxTokens = compactCfg.maxTokens,
                    fast = cfg.fast,
                    effort = cfg.effort,
                    messages = compactMessages,
                    tools = toolDefs,
                    sessionId = compactSession.take(256),
                    toolChoice = "none",
                    onText = {},
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.event(
                "compact_failed",
                buildJsonObject { put("message", (t.message ?: t::class.java.simpleName).take(300)) },
            )
            return false
        }
        val summary = reply.message.content?.trim().orEmpty()
        if (summary.isEmpty()) {
            log.event("compact_failed", buildJsonObject { put("message", "empty summary") })
            return false
        }
        val checkpointRole = if (tail.firstOrNull()?.role == "user") "assistant" else "user"
        val checkpoint = ChatMessage(role = checkpointRole, content = AgentContext.wrapCheckpoint(summary, AgentContext.skillReads(head)))
        history.clear()
        history += systems
        history += checkpoint
        history += tail
        lastPromptTokens = null
        log.event(
            "compact",
            buildJsonObject {
                put("headMessages", head.size)
                put("tailMessages", tail.size)
                put("summaryBytes", summary.length)
                reply.usage?.let { u ->
                    put("promptTokens", u.promptTokens)
                    put("cachedTokens", u.cachedTokens)
                }
            },
        )
        return true
    }

    private fun contextBytes(messages: List<ChatMessage> = history): Int = messages.sumOf { m ->
        (m.content?.length ?: 0) +
            (m.name?.length ?: 0) +
            m.tool_calls.orEmpty().sumOf { it.function.name.length + it.function.arguments.length }
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 80
        private const val MAX_EMPTY_CONTINUES = 2
        private const val HANDOFF_SKIPPED = "skipped: work was handed off to another tab"
        private const val STUCK_SKIPPED = "skipped: the turn was stopped because a tool kept returning the same result"
        private const val AFTER_WORK_NUDGE =
            "You returned no text after writes. If the user's request is not finished, call the next tools now. If it is finished, write one or two short sentences (minimum one). Do not return empty."
        private const val KEEP_WORKING_NUDGE =
            "You only read or listed files and then returned empty. The user's request is not done. Call tools now and make the changes. Do not stop after listing files. Do not return empty."

        private fun emptyTurn(t: Throwable): AgentTurnException? {
            var cur: Throwable? = t
            while (cur != null) {
                if (cur is AgentTurnException && cur.code == AgentError.EMPTY_MODEL) return cur
                cur = cur.cause
            }
            return null
        }

        private fun emptyContinuePrompt(finishReason: String?): String = when (finishReason) {
            "length" ->
                "Your last reply was cut off (max tokens) with no text and no tool calls. Continue from where you left off: call a tool or write the next output."
            else ->
                "The user's request is not finished. Call tools now and do the work. If you are done, write one or two short sentences (minimum one). Do not return empty."
        }
    }
}

data class TurnResult(
    val text: String? = null,
    val pending: List<PendingTool> = emptyList(),
    val error: AgentError? = null,
)
