package app.deft.workspace

import java.util.regex.Pattern

/** Glob matching and grep formatting for workspace file tools. */
object WorkspaceFiles {
    const val GREP_MAX_HITS = 40
    const val GREP_DEFAULT_CONTEXT = 2
    const val GREP_MAX_CONTEXT = 20
    const val LIST_MAX_ENTRIES = 200
    const val LINE_PREVIEW = 400

    fun globMatches(relative: String, pattern: String): Boolean {
        val path = relative.replace('\\', '/').trim().trimStart('/')
        if (path.isEmpty()) return false
        val raw = pattern.replace('\\', '/').trim().trimStart('/')
        if (raw.isEmpty()) return true
        return expandBraces(raw).any { globToRegex(it).matches(path) }
    }

    fun compileGrep(pattern: String): Pair<Pattern, Boolean> {
        val flags = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return runCatching { Pattern.compile(pattern, flags) to false }
            .getOrElse { Pattern.compile(Pattern.quote(pattern), flags) to true }
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024}KiB"
        else -> "${(bytes + 512 * 1024) / (1024 * 1024)}MiB"
    }

    fun grepHitBlock(rel: String, lines: List<String>, matchIndex: Int, context: Int): String {
        val from = (matchIndex - context).coerceAtLeast(0)
        val to = (matchIndex + context).coerceAtMost(lines.lastIndex)
        return (from..to).joinToString("\n") { i ->
            val mark = if (i == matchIndex) ':' else '-'
            "$rel:${i + 1}$mark${lines[i].take(LINE_PREVIEW)}"
        }
    }

    private fun expandBraces(pattern: String): List<String> {
        val m = BRACE.find(pattern) ?: return listOf(pattern)
        return m.groupValues[1].split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { expandBraces(pattern.replaceRange(m.range, it)) }
            .ifEmpty { listOf(pattern) }
    }

    private fun globToRegex(glob: String): Regex {
        var g = glob
        if ('/' !in g) g = "**/$g"
        val sb = StringBuilder("^")
        var i = 0
        while (i < g.length) {
            when {
                g.startsWith("**/", i) -> {
                    sb.append("(?:.*/)?")
                    i += 3
                }
                g.startsWith("**", i) -> {
                    sb.append(".*")
                    i += 2
                }
                g[i] == '*' -> {
                    sb.append("[^/]*")
                    i++
                }
                g[i] == '?' -> {
                    sb.append("[^/]")
                    i++
                }
                else -> {
                    sb.append(Regex.escape(g[i].toString()))
                    i++
                }
            }
        }
        sb.append('$')
        return Regex(sb.toString())
    }

    private val BRACE = Regex("\\{([^{}]+)\\}")
}
