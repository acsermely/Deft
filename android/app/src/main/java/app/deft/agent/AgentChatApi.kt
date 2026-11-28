package app.deft.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.pow

object AgentChatApi {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; isLenient = true }

    suspend fun stream(
        apiBase: String,
        apiKey: String,
        model: String,
        maxTokens: Int,
        messages: List<ChatMessage>,
        tools: List<ToolDef>,
        sessionId: String,
        toolChoice: String = "auto",
        onText: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
    ): ModelReply {
        var last: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return streamOnce(
                    apiBase, apiKey, model, maxTokens, messages, tools,
                    sessionId, toolChoice, onText, onReasoning,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ContextOverflowException) {
                throw e
            } catch (e: Throwable) {
                if (e.isContextOverflow()) {
                    throw ContextOverflowException(e.message ?: "prompt exceeds context window")
                }
                last = e
                if (!isRetryable(e) || attempt == MAX_ATTEMPTS - 1) {
                    throw (e as? NonRetryableException)?.cause ?: e
                }
                delay(backoffMs(attempt, e))
            }
        }
        throw last ?: IllegalStateException("request failed")
    }

    private suspend fun streamOnce(
        apiBase: String,
        apiKey: String,
        model: String,
        maxTokens: Int,
        messages: List<ChatMessage>,
        tools: List<ToolDef>,
        sessionId: String,
        toolChoice: String,
        onText: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
    ): ModelReply {
        val apiTools = tools.mapIndexed { i, t ->
            ApiToolDef(
                function = t.function,
                cache_control = if (i == tools.lastIndex) CacheControl("ephemeral") else null,
            )
        }
        val body = json.encodeToString(
            ChatRequest(
                model = model,
                messages = toApiMessages(messages),
                tools = apiTools.takeIf { it.isNotEmpty() },
                tool_choice = toolChoice,
                max_tokens = maxTokens,
                stream = true,
                stream_options = StreamOpts(include_usage = true),
                session_id = sessionId,
                reasoning = ReasoningOpts(effort = "minimal"),
            ),
        )
        val url = URL("${apiBase.trimEnd('/')}/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 180_000
            doOutput = true
            doInput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("X-Title", "Deft")
            setRequestProperty("x-session-id", sessionId.take(256))
        }
        val cancel = coroutineContext[Job]?.invokeOnCompletion { conn.disconnect() }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val rawStream = if (code in 200..299) conn.inputStream else conn.errorStream
            val reader = rawStream.bufferedReader()
            if (code !in 200..299) {
                val err = reader.use(BufferedReader::readText)
                val retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000)
                val message = openRouterError(err, code)
                if (code == 413 || isContextOverflowMessage(message)) {
                    throw ContextOverflowException(message)
                }
                throw HttpStatusException(code, message, retryAfter)
            }
            return try {
                readSse(reader, onText, onReasoning)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ContextOverflowException) {
                throw e
            } catch (e: Throwable) {
                throw NonRetryableException(e)
            }
        } finally {
            cancel?.dispose()
            conn.disconnect()
        }
    }

    private suspend fun readSse(
        reader: BufferedReader,
        onText: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
    ): ModelReply {
        val acc = SseAcc()
        reader.useLines { lines ->
            for (line in lines) {
                coroutineContext.ensureActive()
                if (line.isEmpty() || line.startsWith(":")) continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                applyChunk(data, acc, onText, onReasoning)
            }
        }
        val calls = acc.tools.entries.sortedBy { it.key }.mapNotNull { (index, toolAcc) ->
            val name = toolAcc.name.ifBlank { return@mapNotNull null }
            val id = toolAcc.id?.takeIf { it.isNotBlank() } ?: "call-$index-$name"
            ToolCall(id = id, function = ToolCallFn(name = name, arguments = toolAcc.arguments.ifBlank { "{}" }))
        }
        val finishReason = acc.finish
        if (acc.content.isEmpty() && calls.isEmpty()) {
            throw AgentTurnException(
                AgentError.EMPTY_MODEL,
                "empty model response (no text and no tool calls)",
                finishReason = finishReason,
            )
        }
        return ModelReply(
            message = ChatMessage(
                role = "assistant",
                content = acc.content.toString().ifBlank { null },
                tool_calls = calls.takeIf { it.isNotEmpty() },
            ),
            finishReason = finishReason,
            usage = acc.usage,
        )
    }

    private suspend fun applyChunk(
        data: String,
        acc: SseAcc,
        onText: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
    ) {
        val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return
        parseUsage(root)?.let { acc.usage = it }
        val choices = root["choices"] as? JsonArray
        val choice = choices?.firstOrNull() as? JsonObject ?: return
        val reason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        if (!reason.isNullOrBlank() && reason != "null") acc.finish = reason
        val delta = choice["delta"] as? JsonObject ?: choice["message"] as? JsonObject ?: return
        extractText(delta["content"])?.let { piece ->
            if (piece.isNotEmpty()) {
                acc.content.append(piece)
                onText(acc.content.toString())
            }
        }
        extractReasoning(delta)?.let { piece ->
            if (piece.isNotEmpty()) {
                acc.reasoning.append(piece)
                onReasoning(acc.reasoning.toString())
            }
        }
        val calls = delta["tool_calls"] as? JsonArray
            ?: (delta["function_call"]?.let { JsonArray(listOf(it)) })
            ?: return
        calls.forEachIndexed { fallbackIndex, el ->
            val obj = el as? JsonObject ?: return@forEachIndexed
            val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: fallbackIndex
            val toolAcc = acc.tools.getOrPut(index) { ToolCallAcc() }
            obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { toolAcc.id = it }
            val fn = obj["function"] as? JsonObject ?: obj
            mergeToolName(toolAcc, fn["name"]?.jsonPrimitive?.contentOrNull)
            fn["arguments"]?.let { toolAcc.arguments += jsonToArgString(it) }
        }
    }

    private fun parseUsage(root: JsonObject): TokenUsage? {
        val u = root["usage"] as? JsonObject ?: return null
        val prompt = u["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val completion = u["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val details = u["prompt_tokens_details"] as? JsonObject
            ?: u["input_tokens_details"] as? JsonObject
        val cached = details?.get("cached_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val write = details?.get("cache_write_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return TokenUsage(prompt, completion, cached, write)
    }

    private fun extractReasoning(delta: JsonObject): String? {
        extractText(delta["reasoning"])?.let { if (it.isNotEmpty()) return it }
        extractText(delta["reasoning_content"])?.let { if (it.isNotEmpty()) return it }
        val details = delta["reasoning_details"] as? JsonArray ?: return null
        val parts = details.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull extractText(item)
            extractText(obj["text"]) ?: extractText(obj["content"]) ?: extractText(obj["summary"])
        }
        return parts.joinToString("").ifEmpty { null }
    }

    private fun extractText(el: JsonElement?): String? {
        if (el == null || el is JsonNull) return null
        when (el) {
            is JsonPrimitive -> return el.contentOrNull
            is JsonArray -> {
                val parts = el.mapNotNull { item ->
                    when (item) {
                        is JsonPrimitive -> item.contentOrNull
                        is JsonObject -> item["text"]?.jsonPrimitive?.contentOrNull
                            ?: item["content"]?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                }
                return parts.joinToString("").ifEmpty { null }
            }
            is JsonObject -> return el["text"]?.jsonPrimitive?.contentOrNull
                ?: el["content"]?.jsonPrimitive?.contentOrNull
            else -> return null
        }
    }

    private fun mergeToolName(acc: ToolCallAcc, name: String?) {
        val n = name?.takeIf { it.isNotEmpty() } ?: return
        when {
            acc.name.isEmpty() -> acc.name = n
            acc.name == n -> Unit
            n.startsWith(acc.name) -> acc.name = n
            acc.name.startsWith(n) -> Unit
            else -> acc.name += n
        }
    }

    private fun jsonToArgString(el: JsonElement): String = when (el) {
        is JsonNull -> ""
        is JsonPrimitive -> el.content
        else -> el.toString()
    }

    internal fun toApiMessages(messages: List<ChatMessage>): List<ApiChatMessage> {
        var sysMarked = 0
        return messages.map { m ->
            val cache = m.role == "system" && sysMarked < 2 && m.content != null
            if (m.role == "system") sysMarked++
            ApiChatMessage(
                role = m.role,
                content = contentElement(m.content, cache, m.images, m.files),
                tool_call_id = m.tool_call_id,
                name = m.name,
                tool_calls = m.tool_calls,
            )
        }
    }

    /**
     * Content parts for a message. Without images or files this is the single text part it has always been. With
     * them: a text part (only if the text is not blank), then one image_url part per image, then one file part per
     * PDF, each in order. The PDF part has no plugins field, so the provider uses its own PDF handling when it has it.
     */
    private fun contentElement(
        text: String?,
        cache: Boolean,
        images: List<String>? = null,
        files: List<FilePart>? = null,
    ): JsonElement? {
        if (!images.isNullOrEmpty() || !files.isNullOrEmpty()) {
            return JsonArray(
                buildList {
                    if (!text.isNullOrBlank()) {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", text)
                                if (cache) put("cache_control", buildJsonObject { put("type", "ephemeral") })
                            },
                        )
                    }
                    for (url in images.orEmpty()) {
                        add(
                            buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject { put("url", url) })
                            },
                        )
                    }
                    for (file in files.orEmpty()) {
                        add(
                            buildJsonObject {
                                put("type", "file")
                                put(
                                    "file",
                                    buildJsonObject {
                                        put("filename", file.filename)
                                        put("file_data", file.dataUrl)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        if (text == null) return null
        return JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                    if (cache) {
                        put("cache_control", buildJsonObject { put("type", "ephemeral") })
                    }
                },
            ),
        )
    }

    private fun isRetryable(e: Throwable): Boolean {
        if (e is NonRetryableException || e is ContextOverflowException) return false
        if (e.isContextOverflow()) return false
        if (e is HttpStatusException) return e.code in RETRY_STATUSES
        return e is SocketTimeoutException || e is ConnectException || e is UnknownHostException
    }

    private fun backoffMs(attempt: Int, e: Throwable): Long {
        val hinted = (e as? HttpStatusException)?.retryAfterMs
        if (hinted != null && hinted > 0) return min(hinted, 30_000)
        return min(1000L * 2.0.pow(attempt).toLong(), 8_000L)
    }

    private fun openRouterError(body: String, code: Int): String {
        val parsed = runCatching {
            json.decodeFromString<ErrorWrap>(body).error?.message
        }.getOrNull()
        return parsed?.takeIf { it.isNotBlank() } ?: "OpenRouter HTTP $code: ${body.take(300)}"
    }

    private const val MAX_ATTEMPTS = 4
    private val RETRY_STATUSES = setOf(408, 409, 425, 429, 500, 502, 503, 504)
}

private class SseAcc {
    val content = StringBuilder()
    val reasoning = StringBuilder()
    val tools = sortedMapOf<Int, ToolCallAcc>()
    var finish: String? = null
    var usage: TokenUsage? = null
}

private class ToolCallAcc {
    var id: String? = null
    var name: String = ""
    var arguments: String = ""
}

private class NonRetryableException(cause: Throwable) : RuntimeException(cause.message, cause)

class HttpStatusException(
    val code: Int,
    message: String,
    val retryAfterMs: Long? = null,
) : RuntimeException(message)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ApiChatMessage>,
    val tools: List<ApiToolDef>? = null,
    val tool_choice: String? = null,
    val max_tokens: Int? = null,
    val stream: Boolean? = null,
    val stream_options: StreamOpts? = null,
    val session_id: String? = null,
    val reasoning: ReasoningOpts? = null,
)

@Serializable
internal data class ApiChatMessage(
    val role: String,
    val content: JsonElement? = null,
    val tool_call_id: String? = null,
    val name: String? = null,
    val tool_calls: List<ToolCall>? = null,
)

@Serializable
private data class ApiToolDef(
    val type: String = "function",
    val function: ToolFn,
    val cache_control: CacheControl? = null,
)

@Serializable
private data class CacheControl(
    val type: String,
)

@Serializable
private data class StreamOpts(
    val include_usage: Boolean,
)

@Serializable
private data class ReasoningOpts(
    val effort: String,
)

@Serializable
private data class ErrorWrap(
    val error: ErrorBody? = null,
)

@Serializable
private data class ErrorBody(
    val message: String? = null,
)
