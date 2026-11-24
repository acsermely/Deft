package app.deft.bridge.modules

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.CallRate
import app.deft.bridge.SystemStatsSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/** `system.stats`: device status from public APIs only; no phone-wide CPU use (Android blocks it). */
class SystemStatsModule(private val context: Context) : BridgeModule {
    private val rate = CallRate(1, SystemStatsSpec.MIN_GAP_MS)

    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        if (request.method != "system.stats") return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        if (!rate.tryAcquire(request.webAppId, SystemClock.elapsedRealtime())) {
            return BridgeOutcome.Err(BridgeErrors.RATE_LIMITED, "At most one system.stats call a second")
        }
        return withContext(Dispatchers.IO) { BridgeOutcome.Ok(stats()) }
    }

    private fun stats() = buildJsonObject {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        put("memory", buildJsonObject {
            put("total", JsonPrimitive(mem.totalMem))
            put("available", JsonPrimitive(mem.availMem))
            put("low", JsonPrimitive(mem.lowMemory))
        })
        val pm = context.getSystemService(PowerManager::class.java)
        put("thermal", buildJsonObject {
            put("status", JsonPrimitive(SystemStatsSpec.thermalName(pm?.currentThermalStatus ?: -1)))
            val headroom = runCatching { pm?.getThermalHeadroom(10) }.getOrNull()
            put("headroom", if (headroom == null || headroom.isNaN()) JsonNull else JsonPrimitive(headroom))
        })
        val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = context.getSystemService(BatteryManager::class.java)
        put("battery", buildJsonObject {
            val level = SystemStatsSpec.percent(
                sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1,
                sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1,
            )
            put("level", level?.let { JsonPrimitive(it) } ?: JsonNull)
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            put("charging", JsonPrimitive(status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL))
            val temp = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
            put("tempC", temp?.let { JsonPrimitive(it / 10.0) } ?: JsonNull)
            val ma = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let { SystemStatsSpec.milliAmps(it) }
            put("currentMa", ma?.let { JsonPrimitive(it) } ?: JsonNull)
        })
        val fs = runCatching { StatFs(context.filesDir.path) }.getOrNull()
        put("storage", buildJsonObject {
            put("total", JsonPrimitive(fs?.totalBytes ?: 0L))
            put("free", JsonPrimitive(fs?.availableBytes ?: 0L))
        })
        cpuFreq()?.let { put("cpuFreq", JsonArray(it.map { mhz -> JsonPrimitive(mhz) })) }
    }

    /** Best effort: SELinux may hide these files; then the field is left out. */
    private fun cpuFreq(): List<Int>? {
        val cpus = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(CPU_DIR) }
            ?.sortedBy { it.name.removePrefix("cpu").toInt() } ?: return null
        val out = cpus.mapNotNull { cpu ->
            runCatching { SystemStatsSpec.mhz(File(cpu, "cpufreq/scaling_cur_freq").readText()) }.getOrNull()
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private companion object {
        val CPU_DIR = Regex("cpu[0-9]+")
    }
}
