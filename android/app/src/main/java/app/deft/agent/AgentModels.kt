package app.deft.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val tool_call_id: String? = null,
    val name: String? = null,
    val tool_calls: List<ToolCall>? = null,
    /** Image data URLs attached to a user message; never logged or counted as text. */
    val images: List<String>? = null,
    /** PDF attachments of a user message, sent as file parts. */
    val files: List<FilePart>? = null,
)

/** A PDF attached to a user message: [dataUrl] is "data:application/pdf;base64,...". */
@Serializable
data class FilePart(
    val filename: String,
    val dataUrl: String,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFn,
)

@Serializable
data class ToolCallFn(
    val name: String,
    val arguments: String,
)

@Serializable
data class ToolDef(
    val type: String = "function",
    val function: ToolFn,
)

@Serializable
data class ToolFn(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

enum class ToolKind { READ, WRITE, QUESTION }

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val kind: ToolKind,
)

data class QuestionPrompt(
    val header: String,
    val prompt: String,
    val options: List<String>,
)

data class PendingTool(
    val call: ToolCall,
    val kind: ToolKind,
    val preview: String,
)

data class AgentLine(
    val id: String,
    val role: String,
    val text: String,
    /** Files the user attached to this message, shown as chips or thumbnails; the model reads them separately. */
    val attachments: List<AgentAttachment> = emptyList(),
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)
