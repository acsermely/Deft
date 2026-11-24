package app.deft.bridge

/** `system.stats`: small pure helpers so the unit conversions can be tested. */
object SystemStatsSpec {
    const val MIN_GAP_MS = 1_000L

    /** `PowerManager.THERMAL_STATUS_*` (0..6) as a word. */
    fun thermalName(status: Int): String = when (status) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "unknown"
    }

    /** Battery percent from the sticky intent's level and scale; null when unknown. */
    fun percent(level: Int, scale: Int): Int? =
        if (level < 0 || scale <= 0) null else (level * 100L / scale).toInt().coerceIn(0, 100)

    /** `scaling_cur_freq` is in kHz; whole MHz, or null for text that is not a number. */
    fun mhz(kHzText: String?): Int? = kHzText?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { (it / 1000).toInt() }

    /** `BATTERY_PROPERTY_CURRENT_NOW` is in microamps (Long.MIN_VALUE when unsupported); null or whole mA. */
    fun milliAmps(microAmps: Long): Int? =
        if (microAmps == Long.MIN_VALUE) null else (microAmps / 1000).toInt()
}
