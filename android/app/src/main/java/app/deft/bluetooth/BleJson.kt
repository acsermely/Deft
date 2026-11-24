package app.deft.bluetooth

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default

fun JsonObject.int(key: String, default: Int? = null): Int? =
    this[key]?.jsonPrimitive?.intOrNull ?: default

fun JsonObject.objArray(key: String): List<JsonObject> {
    val el = this[key] ?: return emptyList()
    val arr = el as? JsonArray ?: return emptyList()
    return arr.mapNotNull { it as? JsonObject }
}

fun JsonObject.strArray(key: String): List<String> {
    val el = this[key] ?: return emptyList()
    val arr = el as? JsonArray ?: return emptyList()
    return arr.mapNotNull { it.jsonPrimitive.contentOrNull }
}

fun parseUuid(raw: String): UUID? = runCatching {
    val s = raw.trim()
    when (s.length) {
        4 -> UUID.fromString("0000$s-0000-1000-8000-00805f9b34fb")
        8 -> UUID.fromString("$s-0000-1000-8000-00805f9b34fb")
        else -> UUID.fromString(s)
    }
}.getOrNull()

fun b64(bytes: ByteArray?): String =
    if (bytes == null || bytes.isEmpty()) "" else Base64.encodeToString(bytes, Base64.NO_WRAP)

fun fromB64(s: String?): ByteArray? {
    if (s.isNullOrBlank()) return ByteArray(0)
    return runCatching { Base64.decode(s, Base64.DEFAULT) }.getOrNull()
}
