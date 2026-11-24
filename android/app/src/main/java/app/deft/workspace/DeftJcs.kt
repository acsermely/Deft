package app.deft.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** JSON Canonicalization Scheme for objects, arrays, strings, numbers, booleans, and null. */
object DeftJcs {
    fun of(value: JsonElement): String = canon(value)

    private fun canon(value: JsonElement): String = when (value) {
        is JsonObject -> buildString {
            append('{')
            val keys = value.keys.sorted()
            keys.forEachIndexed { index, key ->
                if (index > 0) append(',')
                append(JsonPrimitive(key).toString())
                append(':')
                append(canon(value.getValue(key)))
            }
            append('}')
        }
        is JsonArray -> buildString {
            append('[')
            value.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append(canon(item))
            }
            append(']')
        }
        is JsonPrimitive -> value.toString()
    }
}
