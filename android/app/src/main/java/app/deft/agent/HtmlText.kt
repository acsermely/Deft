package app.deft.agent

/**
 * HTML to readable text for `web.fetch` and `net.test`. Pure string work, no Android, so it is
 * unit tested. Scripts, styles, templates and the head go; block tags become line breaks; links
 * keep their target compactly as `text [https://...]` (a few, http(s) only); entities are decoded.
 */
object HtmlText {
    // Every pattern stops at the next '<' or '>' or is length-bounded, so hostile markup (millions of
    // unclosed tags) stays linear; an unclosed script/style swallows the rest of the page.
    private const val MAX_LINKS = 40
    private const val MAX_HREF = 120

    private val title = Regex("<title[^<>]*>([\\s\\S]{0,500}?)</title\\s*>", RegexOption.IGNORE_CASE)
    private val dropped = Regex(
        "<!--[\\s\\S]*?(?:-->|$)|<(script|style|noscript|template|svg|head|iframe|object)\\b[^<>]*>[\\s\\S]*?(?:</\\1\\s*>|$)",
        RegexOption.IGNORE_CASE,
    )
    private val link = Regex("<a\\b[^<>]*?\\shref\\s*=\\s*[\"']([^\"'<>]*)[\"'][^<>]*>([\\s\\S]{0,300}?)</a\\s*>", RegexOption.IGNORE_CASE)
    private val block = Regex("</?(p|div|br|li|tr|h[1-6]|section|article|header|footer|nav|ul|ol|table|blockquote|pre|dt|dd)\\b[^<>]*>", RegexOption.IGNORE_CASE)
    private val tag = Regex("<[^<>]*>")
    private val entity = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});")
    private val spaces = Regex("[ \\t\\u00a0\\u200b]+")
    private val blankLines = Regex("\\n{3,}")

    fun toText(html: String): String {
        val heading = title.find(html)?.groupValues?.get(1)?.let { decode(stripTags(it)).trim() }.orEmpty()
        var s = dropped.replace(html, " ")
        var links = 0
        s = link.replace(s) { m ->
            val text = stripTags(m.groupValues[2]).trim()
            val href = m.groupValues[1].trim()
            val keep = links < MAX_LINKS && href.length <= MAX_HREF && (href.startsWith("https://") || href.startsWith("http://"))
            if (keep) links++
            when {
                text.isEmpty() -> ""
                keep -> "$text [$href]"
                else -> text
            }
        }
        s = block.replace(s, "\n")
        s = decode(tag.replace(s, ""))
        val lines = s.lines().map { spaces.replace(it, " ").trim() }
        val body = blankLines.replace(lines.joinToString("\n").trim(), "\n\n")
        return if (heading.isEmpty()) body else "$heading\n\n$body"
    }

    private fun stripTags(s: String): String = tag.replace(s, "")

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "copy" to "(c)", "reg" to "(R)", "mdash" to "-", "ndash" to "-",
        "hellip" to "...", "lsquo" to "'", "rsquo" to "'", "ldquo" to "\"", "rdquo" to "\"", "bull" to "*",
    )

    internal fun decode(s: String): String = entity.replace(s) { m ->
        val key = m.groupValues[1]
        when {
            key.startsWith("#x") || key.startsWith("#X") -> codePoint(key.substring(2), 16)
            key.startsWith("#") -> codePoint(key.substring(1), 10)
            else -> named[key] ?: m.value
        } ?: m.value
    }

    private fun codePoint(digits: String, radix: Int): String? {
        val cp = digits.toIntOrNull(radix) ?: return null
        if (cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return null
        return String(Character.toChars(cp))
    }

    /** At most [maxBytes] of UTF-8; a cut sequence is dropped, never split. */
    fun cap(text: String, maxBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        return String(bytes, 0, maxBytes, Charsets.UTF_8).trimEnd('\uFFFD')
    }
}
