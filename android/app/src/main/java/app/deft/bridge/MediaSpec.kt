package app.deft.bridge

/**
 * PARITY F6: argument rules and MediaStore columns for `media.*`, free of Android types so they
 * can be unit tested. The column names are the MediaStore names.
 */
object MediaSpec {
    const val DEFAULT_LIMIT = 100
    const val MAX_LIMIT = 500
    const val DEFAULT_THUMB = 256
    const val MIN_THUMB = 64
    const val MAX_THUMB = 512
    const val MAX_READ_BYTES = 7 * 1024 * 1024

    /** The media kinds an app may name, as the methods' `kind` argument spells them. */
    val KINDS: Set<String> = setOf("images", "video", "audio")

    const val ID = "_id"
    const val NAME = "_display_name"
    const val MIME = "mime_type"
    const val SIZE = "_size"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val DURATION = "duration"
    const val DATE_TAKEN = "datetaken"
    const val DATE_ADDED = "date_added"
    const val BUCKET = "bucket_display_name"
    const val ALBUM = "album"

    data class Query(val kind: String, val sinceMs: Long?, val limit: Int, val offset: Int)

    /** Null when the kind is unknown or a number is out of range; `since` is epoch milliseconds. */
    fun parseQuery(kind: String?, since: String?, limit: String?, offset: String?): Query? {
        if (kind == null || kind !in KINDS) return null
        val sinceMs: Long? = if (since == null) {
            null
        } else {
            since.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        }
        val lim = if (limit == null) DEFAULT_LIMIT else limit.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT } ?: return null
        val off = if (offset == null) 0 else offset.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return Query(kind, sinceMs, lim, off)
    }

    /** A missing size means the default. Null when it is not a number from 64 to 512. */
    fun parseThumbSize(raw: String?): Int? =
        if (raw == null) DEFAULT_THUMB else raw.toIntOrNull()?.takeIf { it in MIN_THUMB..MAX_THUMB }

    /** Item ids come from `media.query`; anything that is not a positive number gives null. */
    fun parseId(raw: String?): Long? = raw?.toLongOrNull()?.takeIf { it > 0 }

    /** The columns read for each kind. Missing ones are left out of the result. */
    fun columns(kind: String): List<String> = when (kind) {
        "images" -> listOf(ID, NAME, MIME, SIZE, DATE_ADDED, WIDTH, HEIGHT, DATE_TAKEN, BUCKET)
        "video" -> listOf(ID, NAME, MIME, SIZE, DATE_ADDED, WIDTH, HEIGHT, DATE_TAKEN, BUCKET, DURATION)
        else -> listOf(ID, NAME, MIME, SIZE, DATE_ADDED, DURATION, ALBUM)
    }
}
