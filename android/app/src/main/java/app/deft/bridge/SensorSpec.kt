package app.deft.bridge

import android.hardware.Sensor
import kotlin.math.roundToInt

/** PARITY F1: the sensor types `sensors.*` accepts and how fast they may report. */
object SensorSpec {
    const val MAX_SUBSCRIPTIONS = 4
    const val DEFAULT_RATE_HZ = 5
    const val MIN_RATE_HZ = 1
    const val MAX_RATE_HZ = 50

    data class Plan(val name: String, val type: Int, val rateHz: Int)

    val types: Map<String, Int> = mapOf(
        "stepCounter" to Sensor.TYPE_STEP_COUNTER,
        "stepDetector" to Sensor.TYPE_STEP_DETECTOR,
        "light" to Sensor.TYPE_LIGHT,
        "pressure" to Sensor.TYPE_PRESSURE,
        "proximity" to Sensor.TYPE_PROXIMITY,
        "magnetometer" to Sensor.TYPE_MAGNETIC_FIELD,
        "humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
        "ambientTemperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
        "accelerometer" to Sensor.TYPE_ACCELEROMETER,
        "gravity" to Sensor.TYPE_GRAVITY,
        "linearAcceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
        "rotationVector" to Sensor.TYPE_ROTATION_VECTOR,
    )

    /**
     * A missing rate means the default. A number is clamped into 1..50 Hz and rounded. Anything
     * that is not a number gives null, which the caller reports as invalid args.
     */
    fun rateHz(raw: String?): Int? {
        if (raw == null) return DEFAULT_RATE_HZ
        val n = raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return n.coerceIn(MIN_RATE_HZ.toDouble(), MAX_RATE_HZ.toDouble()).roundToInt()
    }

    /** Null when the type is unknown or the rate is not a number. */
    fun parse(name: String?, rate: String?): Plan? {
        if (name == null) return null
        val type = types[name] ?: return null
        val hz = rateHz(rate) ?: return null
        return Plan(name, type, hz)
    }

    /** Minimum time between two readings the app receives, in nanoseconds. */
    fun intervalNs(rateHz: Int): Long = 1_000_000_000L / rateHz

    /**
     * Passes on at most one reading per interval without losing the last one: a reading that
     * arrives too soon is held, and the caller emits the newest held reading when the interval
     * ends ([flush]). On-change sensors (steps, light, proximity) may not report again, so
     * dropping it would lose the value. Timestamps are sensor event times in nanoseconds.
     */
    class Throttle(private val intervalNs: Long) {
        sealed interface Decision {
            /** Emit this reading now. */
            data object Emit : Decision

            /** Hold this reading; call [flush] after [delayNs] and emit the newest held reading. */
            data class Defer(val delayNs: Long) : Decision

            /** Hold this reading; a flush is already scheduled. */
            data object Hold : Decision
        }

        private var lastNs: Long? = null
        private var pending = false

        fun offer(timestampNs: Long): Decision {
            if (pending) return Decision.Hold
            val last = lastNs
            if (last == null || timestampNs - last >= intervalNs) {
                lastNs = timestampNs
                return Decision.Emit
            }
            pending = true
            return Decision.Defer(intervalNs - (timestampNs - last))
        }

        /** The interval ended: true when a held reading should be emitted now. */
        fun flush(): Boolean {
            if (!pending) return false
            pending = false
            lastNs = (lastNs ?: 0L) + intervalNs
            return true
        }
    }

    /** Step sensors need ACTIVITY_RECOGNITION on Android 10 and newer. */
    fun needsActivityRecognition(type: Int): Boolean =
        type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR
}
