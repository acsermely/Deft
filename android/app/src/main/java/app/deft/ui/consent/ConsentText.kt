package app.deft.ui.consent

/** Plain lines for the consent screen that depend on state, kept pure for unit tests. */
object ConsentText {
    /** What a one-app-at-a-time group is called in a sentence. */
    fun groupLabel(group: String): String = when (group) {
        "sms" -> "text messages"
        "calls" -> "phone calls"
        "nfc.hce" -> "NFC card"
        else -> group
    }

    /**
     * Shown under a capability of an exclusive group that another app holds: allowing it here
     * takes the group from that app (`AppRepository.replaceGroupHolder`).
     */
    fun replacesHolder(holderName: String, group: String): String =
        "This replaces $holderName as your ${groupLabel(group)} app."

    /** A download size in plain units (1000-based, like Android's storage screens). */
    fun bytes(n: Long): String = when {
        n >= 1_000_000_000L -> String.format(java.util.Locale.ROOT, "%.1f GB", n / 1e9)
        n >= 1_000_000L -> String.format(java.util.Locale.ROOT, "%.1f MB", n / 1e6)
        n >= 1_000L -> String.format(java.util.Locale.ROOT, "%.0f kB", n / 1e3)
        else -> "$n bytes"
    }
}
