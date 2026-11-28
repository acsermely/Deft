package app.deft.bridge

/** Argument rules for `audio.*`, free of Android types so they can be unit tested. */
object AudioSpec {
    /** The volume streams an app may name, as the method's `stream` argument spells them. */
    val STREAMS: Set<String> = setOf("music", "ring", "alarm", "notification", "voiceCall")

    /** The ringer modes an app may name for `audio.setRingerMode`. */
    val RINGER_MODES: Set<String> = setOf("normal", "vibrate", "silent")

    /** The outputs an app may name for `audio.route`. */
    val ROUTE_TARGETS: Set<String> = setOf("speaker", "earpiece", "bluetooth", "default")

    /** A whole-number volume. Out-of-range numbers are clamped to 0..max by the caller; anything else gives null. */
    fun parseValue(raw: String?): Int? = raw?.toIntOrNull()

    fun clamp(value: Int, max: Int): Int = value.coerceIn(0, max)
}
