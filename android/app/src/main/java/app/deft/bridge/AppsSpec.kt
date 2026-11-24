package app.deft.bridge

/** PARITY G7: limits and checks for `apps.*` and `usage.stats`, kept free of Android types for unit tests. */
object AppsSpec {
    /** Edge length of the icon `apps.icon` returns (PNG, base64). */
    const val ICON_PX = 96

    /** A usage window may be at most this long; Android keeps about a year of usage history. */
    const val MAX_USAGE_RANGE_MS = 366L * 24 * 60 * 60 * 1000

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** A dotted Android package name, at most 255 characters. */
    fun validPackage(name: String?): Boolean =
        name != null && name.length <= 255 && PACKAGE.matches(name)

    /**
     * Epoch milliseconds for `usage.stats`: both ends present, `sinceMs` not negative, `untilMs`
     * after it, and the window no longer than [MAX_USAGE_RANGE_MS]. Null when the window is refused.
     */
    fun usageRange(sinceText: String?, untilText: String?): Pair<Long, Long>? {
        val since = sinceText?.toLongOrNull() ?: return null
        val until = untilText?.toLongOrNull() ?: return null
        if (since < 0 || until <= since) return null
        if (until - since > MAX_USAGE_RANGE_MS) return null
        return since to until
    }
}
