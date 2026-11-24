package app.deft.device

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.io.File

/**
 * Persists the device tier in prefs `device_tier` so it is classified and probed once (8.4).
 * Only the debug reset in the Dashboard clears it, which makes the next start probe again.
 */
object DeviceTierStore {
    private const val PREFS = "device_tier"
    private const val K_TIER = "tier"
    private const val K_PROBED = "probed"
    private const val K_FRAME_MS = "frame_ms"  // written by saveProbe for diagnosis; no reader yet

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The stored tier, or the static classification on first use (stored so it is not read again). */
    fun tier(context: Context): DeviceTier {
        val p = prefs(context)
        DeviceTier.parse(p.getString(K_TIER, null))?.let { return it }
        val t = DeviceTierClassifier.classify(readSignals(context))
        p.edit().putString(K_TIER, t.name).apply()
        return t
    }

    fun probed(context: Context): Boolean = prefs(context).getBoolean(K_PROBED, false)

    fun saveProbe(context: Context, tier: DeviceTier, avgFrameMs: Double) {
        prefs(context).edit()
            .putString(K_TIER, tier.name)
            .putBoolean(K_PROBED, true)
            .putFloat(K_FRAME_MS, avgFrameMs.toFloat())
            .apply()
    }

    /** Debug reset: forgets the classification and the probe. */
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun readSignals(context: Context): DeviceSignals {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        return DeviceSignals(
            lowRamDevice = am?.isLowRamDevice == true,
            totalRamBytes = mem.totalMem,
            cores = Runtime.getRuntime().availableProcessors(),
            maxFreqKhz = maxCpuFreqKhz(),
            sdkInt = android.os.Build.VERSION.SDK_INT,
        )
    }

    /** Highest cpuinfo_max_freq across cores, in kHz. 0 when no core exposes it. */
    private fun maxCpuFreqKhz(): Long {
        val cpus = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) } ?: return 0L
        return cpus.maxOfOrNull { cpu ->
            File(cpu, "cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }
                ?.let { runCatching { it.readText().trim().toLong() }.getOrDefault(0L) } ?: 0L
        } ?: 0L
    }
}

/**
 * One-shot frame-time probe. Starts [DELAY_MS] after launch (past the cold-start jank window) on the main thread, samples Choreographer
 * frames for [WINDOW_MS], then stores the tier. Calls [onChanged] only when the tier changed.
 */
object DeviceTierProbe {
    const val DELAY_MS = 3000L
    const val WINDOW_MS = 1000L
    const val MIN_FRAMES = 20

    @Volatile private var running = false

    fun schedule(context: Context, onChanged: () -> Unit) {
        val app = context.applicationContext
        if (DeviceTierStore.probed(app) || running) return
        running = true
        Handler(Looper.getMainLooper()).postDelayed({ start(app, onChanged) }, DELAY_MS)
    }

    private fun start(app: Context, onChanged: () -> Unit) {
        val choreographer = Choreographer.getInstance()
        val stamps = ArrayList<Long>()
        val beginNs = System.nanoTime()
        lateinit var callback: Choreographer.FrameCallback
        callback = Choreographer.FrameCallback { frameNs ->
            stamps += frameNs
            if (frameNs - beginNs < WINDOW_MS * 1_000_000L) {
                choreographer.postFrameCallback(callback)
            } else {
                finish(app, stamps, onChanged)
            }
        }
        choreographer.postFrameCallback(callback)
    }

    private fun finish(app: Context, stamps: List<Long>, onChanged: () -> Unit) {
        if (stamps.size < MIN_FRAMES) {
            // Too few frames to judge (process in the background): keep the static tier and probe again next start.
            running = false
            return
        }
        val avgMs = (stamps.last() - stamps.first()) / 1e6 / (stamps.size - 1)
        val before = DeviceTierStore.tier(app)
        val base = DeviceTierClassifier.classify(DeviceTierStore.readSignals(app))
        val after = DeviceTierClassifier.applyProbe(base, avgMs)
        DeviceTierStore.saveProbe(app, after, avgMs)
        running = false
        if (after != before) onChanged()
    }
}
