package app.deft.agent

data class AgentError(
    val code: String,
    val message: String,
    val http: Int? = null,
    val finishReason: String? = null,
    val contextBytes: Int? = null,
) {
    fun display(): String = buildString {
        append(code)
        append(": ")
        append(message)
        if (http != null) append(" (HTTP ").append(http).append(')')
        if (!finishReason.isNullOrBlank()) append(" finish=").append(finishReason)
        if (contextBytes != null) append(" ctx=").append(contextBytes).append('B')
    }

    companion object {
        const val EMPTY_MODEL = "EMPTY_MODEL"
        const val HTTP = "HTTP"
        const val MAX_ROUNDS = "MAX_ROUNDS"
        const val TRIM_ORPHAN = "TRIM_ORPHAN"
        const val CONTEXT_OVERFLOW = "CONTEXT_OVERFLOW"
        const val COMPACT_FAILED = "COMPACT_FAILED"
        const val TOOL_ARGS = "TOOL_ARGS"
        const val CANCELLED = "CANCELLED"
        const val NO_KEY = "NO_KEY"
        const val UNKNOWN = "UNKNOWN"

        fun from(t: Throwable, contextBytes: Int? = null): AgentError {
            if (t is ContextOverflowException || t.isContextOverflow()) {
                return AgentError(
                    CONTEXT_OVERFLOW,
                    t.message ?: "prompt exceeds context window",
                    http = (t as? HttpStatusException)?.code,
                    contextBytes = contextBytes,
                )
            }
            if (t is AgentTurnException) {
                return AgentError(t.code, t.message ?: t.code, finishReason = t.finishReason, contextBytes = contextBytes)
            }
            if (t is HttpStatusException) {
                return AgentError("${HTTP}_${t.code}", t.message ?: "HTTP ${t.code}", t.code, contextBytes = contextBytes)
            }
            return AgentError(UNKNOWN, t.message ?: t::class.java.simpleName, contextBytes = contextBytes)
        }
    }
}

class ContextOverflowException(message: String) : RuntimeException(message)

fun isContextOverflowMessage(message: String): Boolean {
    val msg = message.lowercase()
    if (msg.contains("rate limit") || msg.contains("too many requests") || msg.contains("throttl")) {
        return false
    }
    val hits = listOf(
        "prompt is too long",
        "prompt too long",
        "context length exceeded",
        "context_length_exceeded",
        "maximum context length",
        "model_context_window_exceeded",
        "exceeds the context",
        "exceeds the maximum number of tokens",
        "too many tokens in your",
        "input is too long",
        "prompt exceeds",
    )
    return hits.any { msg.contains(it) }
}

fun Throwable.isContextOverflow(): Boolean {
    if (this is ContextOverflowException) return true
    if (this is HttpStatusException && code == 413) return true
    return isContextOverflowMessage(message.orEmpty())
}

class AgentTurnException(
    val code: String,
    override val message: String,
    val finishReason: String? = null,
) : RuntimeException(message)

data class ModelReply(
    val message: ChatMessage,
    val finishReason: String?,
    val usage: TokenUsage? = null,
)
