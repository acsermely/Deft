package app.deft.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SafeAreaLayout {
    private val json = Json { ignoreUnknownKeys = true }

    data class Flags(val viewportFitCover: Boolean, val safeArea: Boolean)

    fun flags(probeRaw: String): Flags? {
        val obj = parseObject(probeRaw) ?: return null
        if (obj["error"] != null) return null
        val fit = obj["viewportFitCover"]?.jsonPrimitive?.booleanOrNull ?: return null
        val safe = obj["safeArea"]?.jsonPrimitive?.booleanOrNull ?: return null
        return Flags(viewportFitCover = fit, safeArea = safe)
    }

    fun warningTexts(probeRaw: String): List<String> {
        val f = flags(probeRaw) ?: return emptyList()
        return buildList {
            if (!f.viewportFitCover) add("missing viewport-fit=cover (notch/cutout)")
            if (!f.safeArea) add("missing env(safe-area-inset-*) (notch/home indicator)")
        }
    }

    private fun parseObject(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        if (el is JsonObject) return el
        val inner = el.jsonPrimitive.contentOrNull ?: return null
        return runCatching { json.parseToJsonElement(inner).jsonObject }.getOrNull()
    }
}
