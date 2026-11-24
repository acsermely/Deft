package app.deft.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Turns an [AppVerifier] probe into layout warnings. WebView is not available in JVM tests. */
object RenderProbe {
    private val json = Json { ignoreUnknownKeys = true }

    fun warningTexts(probeRaw: String): List<String> {
        val obj = parse(probeRaw) ?: return emptyList()
        if (obj["error"] != null) return emptyList()
        return buildList {
            val width = obj["clientWidth"]?.jsonPrimitive?.intOrNull
            if (obj["hOverflow"]?.jsonPrimitive?.booleanOrNull == true && (width == null || width >= 320)) {
                val where = if (width == null) "" else " at ${width}px"
                add("horizontal overflow$where (scrollWidth > clientWidth)")
            }
            val usesVar = obj["bodyBgUsesVar"]?.jsonPrimitive?.booleanOrNull
            val follows = obj["themeChangesBg"]?.jsonPrimitive?.booleanOrNull
            if (usesVar == false && follows != true) add("body background does not follow the theme")
        }
    }

    private fun parse(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        if (el is JsonObject) return el
        val inner = el.jsonPrimitive.contentOrNull ?: return null
        return runCatching { json.parseToJsonElement(inner).jsonObject }.getOrNull()
    }
}
