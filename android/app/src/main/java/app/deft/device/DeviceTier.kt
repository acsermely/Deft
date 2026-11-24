package app.deft.device

/**
 * Device tier (proposal 8.4). Decides whether the glass material (blur) is published.
 * LOW: opaque material, no blur. MID and HIGH: blur on.
 */
enum class DeviceTier(val cssName: String) {
    LOW("low"), MID("mid"), HIGH("high");

    /** One step slower. LOW stays LOW. */
    fun lower(): DeviceTier = when (this) {
        HIGH -> MID
        MID, LOW -> LOW
    }

    companion object {
        /** Parses a stored tier name. Null for anything unknown, so a corrupt pref falls back to classification. */
        fun parse(raw: String?): DeviceTier? = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

/** Static hardware facts read once. [maxFreqKhz] is 0 when the kernel does not expose cpufreq. */
data class DeviceSignals(
    val lowRamDevice: Boolean,
    val totalRamBytes: Long,
    val cores: Int,
    val maxFreqKhz: Long,
    val sdkInt: Int,
)

object DeviceTierClassifier {
    const val GIB = 1024L * 1024L * 1024L

    /** Below this total RAM the device is LOW (a "4 GB" phone reports about 3.7 GiB). */
    const val LOW_RAM_BYTES = 5 * GIB

    /** HIGH needs this much RAM, this many cores and this max clock (MHz), on Android 12 or later. */
    const val HIGH_RAM_BYTES = 7 * GIB
    const val HIGH_CORES = 8
    const val HIGH_MAX_FREQ_MHZ = 2200L
    const val HIGH_MIN_SDK = 31

    /** Lowest SDK that gets blur at all. Older WebViews composite blur poorly. */
    const val MIN_BLUR_SDK = 29

    /** Average frame interval above this (ms) means under about 40 fps: one tier down. */
    const val SLOW_FRAME_MS = 24.0

    fun classify(s: DeviceSignals): DeviceTier {
        val freqMhz = s.maxFreqKhz / 1000
        return when {
            s.lowRamDevice -> DeviceTier.LOW
            s.sdkInt < MIN_BLUR_SDK -> DeviceTier.LOW
            s.totalRamBytes < LOW_RAM_BYTES -> DeviceTier.LOW
            s.totalRamBytes >= HIGH_RAM_BYTES && s.cores >= HIGH_CORES &&
                freqMhz >= HIGH_MAX_FREQ_MHZ && s.sdkInt >= HIGH_MIN_SDK -> DeviceTier.HIGH
            else -> DeviceTier.MID
        }
    }

    /**
     * Applies the frame-time probe. [avgFrameMs] <= 0 means the probe got too few frames to judge, so the
     * static tier stands. A slow average drops one tier.
     */
    fun applyProbe(base: DeviceTier, avgFrameMs: Double): DeviceTier =
        if (avgFrameMs > SLOW_FRAME_MS) base.lower() else base
}
