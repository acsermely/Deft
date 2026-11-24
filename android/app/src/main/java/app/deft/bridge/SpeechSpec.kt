package app.deft.bridge

/** PARITY F3: argument rules for `speech.*`, free of Android types so they can be unit tested. */
object SpeechSpec {
    const val MIN_RATE = 0.25f
    const val MAX_RATE = 4.0f

    private val LANG_TAG = Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")

    /** A missing rate or pitch means [default]. A number is clamped into 0.25..4.0. Anything else gives null. */
    fun rateOrPitch(raw: String?, default: Float = 1f): Float? {
        if (raw == null) return default
        val n = raw.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        return n.coerceIn(MIN_RATE, MAX_RATE)
    }

    /** BCP-47 shape, such as `en`, `en-US` or `zh-Hant-TW`. */
    fun isLangTag(tag: String): Boolean = tag.length <= 35 && LANG_TAG.matches(tag)

    const val MAX_ALTERNATIVES = 5

    /** The recognizer's other candidates after the top one, blank ones skipped, at most [MAX_ALTERNATIVES]. */
    fun alternatives(candidates: List<String>?): List<String> =
        candidates.orEmpty().drop(1).filter { it.isNotBlank() }.take(MAX_ALTERNATIVES)

    /**
     * Review: the phone has one voice for every app. A new utterance replaces only the caller's
     * own speech: it is queued behind another app's speech ([inFlightOwners] holds the app of
     * every utterance not yet done) and flushes the queue only when nothing else is in it.
     */
    fun flushes(caller: String, inFlightOwners: Collection<String>): Boolean = inFlightOwners.all { it == caller }

    /**
     * Review: `speech.stop` stops the voice only for the app speaking now ([currentOwner]); before
     * the first utterance starts, only when everything queued is the caller's.
     */
    fun mayStop(caller: String, currentOwner: String?, inFlightOwners: Collection<String>): Boolean =
        if (currentOwner != null) currentOwner == caller
        else inFlightOwners.isNotEmpty() && inFlightOwners.all { it == caller }
}
