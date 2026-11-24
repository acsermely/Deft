package app.deft.webview

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object DeftLintFormat {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Diag(
        val id: String = "",
        val severity: String = "",
        val path: String = "",
        val line: Int = 0,
        val col: Int = 0,
        val message: String = "",
    )

    fun parse(raw: String): List<Diag> {
        val text = raw.trim()
        if (text.isEmpty() || text == "null" || text == "[]") return emptyList()
        return json.decodeFromString<List<Diag>>(text)
    }

    fun lines(diags: List<Diag>, cap: Int = 12): String {
        if (diags.isEmpty()) return ""
        val shown = diags.take(cap)
        val body = shown.joinToString("\n") { diag ->
            "${diag.path}:${diag.line}:${diag.col} ${diag.id} ${diag.severity} ${diag.message}"
        }
        val more = diags.size - shown.size
        return if (more > 0) "$body\n$more more (run apps.verify for all)" else body
    }
}
