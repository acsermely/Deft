package app.deft.bridge.modules

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.deft.bridge.OsPermissions
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.BridgeService
import app.deft.bridge.HostSurfaces
import app.deft.bridge.SensorSpec
import app.deft.bridge.StepStore
import app.deft.bridge.jsonArg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** `sensors.*` (PARITY F1): motion and environment sensors, only while the app is on screen. */
class SensorsModule(private val context: Context) : BridgeModule {
    private class Subscription(
        val id: String,
        val webAppId: String,
        val typeName: String,
        val manager: SensorManager,
        val intervalNs: Long,
    ) : SensorEventListener {
        private val throttle = SensorSpec.Throttle(intervalNs)
        private var latestValues: FloatArray = FloatArray(0)
        private var latestTimestamp = 0L

        @Volatile
        var stopped = false

        override fun onSensorChanged(event: SensorEvent) {
            // The sensor may report faster than asked; pass on at most one reading per interval,
            // and send the newest held one when the interval ends so on-change values are not lost.
            val values = event.values.copyOf()
            val decision = synchronized(this) {
                latestValues = values
                latestTimestamp = event.timestamp
                throttle.offer(event.timestamp)
            }
            when (decision) {
                SensorSpec.Throttle.Decision.Emit -> send(values, event.timestamp)
                is SensorSpec.Throttle.Decision.Defer -> scope.launch {
                    delay((decision.delayNs + 999_999) / 1_000_000)
                    if (stopped) return@launch
                    val held = synchronized(this@Subscription) {
                        if (throttle.flush()) latestValues to latestTimestamp else null
                    }
                    held?.let { (heldValues, heldTimestamp) -> send(heldValues, heldTimestamp) }
                }
                SensorSpec.Throttle.Decision.Hold -> Unit
            }
        }

        private fun send(values: FloatArray, timestamp: Long) {
            val payload = buildJsonObject {
                put("id", JsonPrimitive(id))
                put("type", JsonPrimitive(typeName))
                put("values", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                put("timestamp", JsonPrimitive(timestamp))
            }.toString()
            scope.launch { BridgeService.instanceOrNull()?.emit(webAppId, "sensors.reading", payload) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        // Daily steps come from the launcher's own store and work with the app closed or in a run.
        if (request.method == "sensors.stepsToday") return stepsToday(request)
        HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        return when (request.method) {
            "sensors.list" -> list()
            "sensors.subscribe" -> subscribe(request)
            "sensors.unsubscribe" -> unsubscribe(request)
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private fun manager(): SensorManager? = context.getSystemService(SensorManager::class.java)

    private fun list(): BridgeOutcome {
        val manager = manager() ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Sensors are not available")
        val items = buildJsonArray {
            for ((name, type) in SensorSpec.types) {
                val sensor = manager.getDefaultSensor(type) ?: continue
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive(name))
                        put("name", JsonPrimitive(sensor.name))
                        put("vendor", JsonPrimitive(sensor.vendor))
                        put("maxRate", sensor.minDelay.takeIf { it > 0 }?.let { JsonPrimitive(1_000_000.0 / it) } ?: JsonNull)
                        put("maxRange", JsonPrimitive(sensor.maximumRange))
                        put("resolution", JsonPrimitive(sensor.resolution))
                    },
                )
            }
        }
        return BridgeOutcome.Ok(items)
    }

    private suspend fun subscribe(request: BridgeRequest): BridgeOutcome {
        val plan = SensorSpec.parse(jsonArg(request.args, "type"), jsonArg(request.args, "rateHz"))
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "type must be a sensor from sensors.list; rateHz a number")
        if (SensorSpec.needsActivityRecognition(plan.type) && !activityRecognitionGranted()) {
            OsPermissions.ensure(
                context,
                request.webAppId, request.background,
                listOf(Manifest.permission.ACTIVITY_RECOGNITION),
                "Allow physical activity",
            )?.let { return it }
        }
        val manager = manager() ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Sensors are not available")
        val sensor = manager.getDefaultSensor(plan.type)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "This device has no ${plan.name} sensor")
        synchronized(subscriptions) {
            if (subscriptions.values.count { it.webAppId == request.webAppId } >= SensorSpec.MAX_SUBSCRIPTIONS) {
                return BridgeOutcome.Err(BridgeErrors.IN_USE, "At most ${SensorSpec.MAX_SUBSCRIPTIONS} sensors at a time")
            }
            val sub = Subscription(
                id = UUID.randomUUID().toString(),
                webAppId = request.webAppId,
                typeName = plan.name,
                manager = manager,
                intervalNs = SensorSpec.intervalNs(plan.rateHz),
            )
            // Sensor delay is in microseconds; the module does the throttling, so ask for the rate.
            val registered = manager.registerListener(sub, sensor, 1_000_000 / plan.rateHz)
            if (!registered) return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The ${plan.name} sensor is not available right now")
            subscriptions[sub.id] = sub
            return BridgeOutcome.Ok(buildJsonObject { put("id", JsonPrimitive(sub.id)) })
        }
    }

    private suspend fun stepsToday(request: BridgeRequest): BridgeOutcome {
        if (!activityRecognitionGranted()) {
            OsPermissions.ensure(
                context,
                request.webAppId, request.background,
                listOf(Manifest.permission.ACTIVITY_RECOGNITION),
                "Allow physical activity",
            )?.let { return it }
        }
        val manager = manager() ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Sensors are not available")
        if (manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) == null) {
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "This device has no step counter")
        }
        val store = StepStore.get(context)
        store.markWanted()
        store.flush()
        return BridgeOutcome.Ok(store.snapshotJson())
    }

    private fun unsubscribe(request: BridgeRequest): BridgeOutcome {
        val id = jsonArg(request.args, "id")
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "id from sensors.subscribe required")
        val sub = subscriptions[id]?.takeIf { it.webAppId == request.webAppId } ?: return BridgeOutcome.Ok()
        subscriptions.remove(id)
        sub.stopped = true
        sub.manager.unregisterListener(sub)
        return BridgeOutcome.Ok()
    }

    private fun activityRecognitionGranted(): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    companion object {
        private val subscriptions = ConcurrentHashMap<String, Subscription>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** PARITY F1: leaving the app's screen stops its sensors. Called from the host activity's onStop. */
        fun stopFor(webAppId: String) {
            synchronized(subscriptions) {
                val mine = subscriptions.values.filter { it.webAppId == webAppId }
                for (sub in mine) {
                    subscriptions.remove(sub.id)
                    sub.stopped = true
                    sub.manager.unregisterListener(sub)
                }
            }
        }
    }
}
