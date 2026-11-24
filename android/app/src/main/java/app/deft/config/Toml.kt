package app.deft.config

object Toml {
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = stripComment(raw).trim()
            if (line.isEmpty() || line.startsWith("[")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            out[key] = unquote(line.substring(eq + 1).trim())
        }
        return out
    }

    private fun stripComment(raw: String): String {
        var inSingle = false
        var inDouble = false
        raw.forEachIndexed { i, c ->
            when {
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '#' && !inSingle && !inDouble -> return raw.substring(0, i)
            }
        }
        return raw
    }

    private fun unquote(value: String): String {
        if (value.length >= 2) {
            val a = value.first()
            val b = value.last()
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return value.substring(1, value.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
            }
        }
        return value
    }

    fun str(map: Map<String, String>, key: String, default: String): String =
        map[key]?.takeIf { it.isNotBlank() } ?: default

    fun int(map: Map<String, String>, key: String, default: Int): Int =
        map[key]?.toIntOrNull() ?: default

    fun float(map: Map<String, String>, key: String, default: Float): Float =
        map[key]?.toFloatOrNull() ?: default

    fun bool(map: Map<String, String>, key: String, default: Boolean): Boolean =
        when (map[key]?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> default
        }

    fun encode(entries: List<Pair<String, Any>>): String =
        entries.joinToString("\n", postfix = "\n") { (key, value) ->
            "$key = ${encodeValue(value)}"
        }

    private fun encodeValue(value: Any): String = when (value) {
        is Boolean -> value.toString()
        is Int -> value.toString()
        is Long -> value.toString()
        is Float -> formatNumber(value.toDouble())
        is Double -> formatNumber(value)
        else -> {
            val s = value.toString().replace("\\", "\\\\").replace("\"", "\\\"")
            "\"$s\""
        }
    }

    private fun formatNumber(value: Double): String {
        val asInt = value.toInt()
        return if (value == asInt.toDouble()) asInt.toString() else value.toString()
    }
}
