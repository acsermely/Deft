package app.deft.bridge

/** PARITY D5: the shape of a picture-in-picture window, width to height. */
data class PipRatio(val width: Int, val height: Int)

/** PARITY D5: `aspect` strings such as `16:9`, kept inside the range Android accepts (1:2.39 to 2.39:1). */
object PipAspect {
    private const val MAX_RATIO = 2.39
    private const val MAX_PART = 10000
    private val DEFAULT = PipRatio(16, 9)

    /**
     * A missing aspect means 16:9. Anything that is not `w:h` with whole numbers from 1 to 10000
     * gives null, which the caller reports as invalid args. Ratios past the limit are cut back.
     */
    fun parse(text: String?): PipRatio? {
        if (text == null) return DEFAULT
        val parts = text.split(":")
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull()?.takeIf { it in 1..MAX_PART } ?: return null
        val h = parts[1].toIntOrNull()?.takeIf { it in 1..MAX_PART } ?: return null
        return when {
            w.toDouble() / h > MAX_RATIO -> PipRatio((h * MAX_RATIO).toInt(), h)
            h.toDouble() / w > MAX_RATIO -> PipRatio(w, (w * MAX_RATIO).toInt())
            else -> PipRatio(w, h)
        }
    }
}
