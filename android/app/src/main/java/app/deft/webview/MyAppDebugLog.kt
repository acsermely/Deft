package app.deft.webview

/** In-process ERROR/WARN ring for phone-created my-apps (Preview). Not used for installed web apps. */
object MyAppDebugLog {
    const val MAX_SLUGS = 8

    private val lock = Any()
    private val buckets = object : LinkedHashMap<String, WebViewFindingRing>(MAX_SLUGS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WebViewFindingRing>?): Boolean =
            size > MAX_SLUGS
    }

    fun append(slug: String, finding: WebViewFinding) {
        val key = slug.trim().lowercase()
        if (key.isEmpty()) return
        val stored = WebViewFindings.finding(
            finding.level,
            finding.kind,
            finding.text,
            finding.source,
            finding.line,
        )
        synchronized(lock) {
            buckets.getOrPut(key) { WebViewFindingRing() }.offer(stored)
        }
    }

    fun hint(slug: String?): String? {
        if (slug.isNullOrBlank()) return null
        val items = snapshot(slug)
        val errors = items.count { it.level == "ERROR" }
        val warnings = items.count { it.level == "WARN" }
        if (errors == 0 && warnings == 0) return null
        return "Preview debug: $errors errors, $warnings warnings (apps.debug)."
    }

    /** Drop this slug's ring. Next Preview starts empty. */
    fun clear(slug: String) {
        val key = slug.trim().lowercase()
        if (key.isEmpty()) return
        synchronized(lock) { buckets.remove(key) }
    }

    fun dump(slug: String): String {
        val key = slug.trim().lowercase()
        val (items, overflow) = synchronized(lock) {
            buckets[key]?.snapshot() ?: (emptyList<WebViewFinding>() to false)
        }
        return WebViewFindings.format(
            slug = slug,
            findings = items,
            emptyHint = "no Preview errors yet (open with Preview first; in-memory until process death)",
            overflow = overflow,
        )
    }

    internal fun snapshot(slug: String): List<WebViewFinding> = synchronized(lock) {
        buckets[slug.trim().lowercase()]?.snapshot()?.first.orEmpty()
    }

    internal fun resetForTests() {
        synchronized(lock) { buckets.clear() }
    }
}
