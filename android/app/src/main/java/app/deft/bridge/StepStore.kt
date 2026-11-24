package app.deft.bridge

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.deft.catalog.Capability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * `sensors.stepsToday`: the launcher's own daily step totals ([StepLedger]), kept whether or not an
 * app is open. One non-wakeup `TYPE_STEP_COUNTER` listener for the whole process, started only when
 * ACTIVITY_RECOGNITION is held and some app holding `sensors` has asked once ([markWanted], persisted).
 * Batching: 10 minutes while the home screen is hidden, none while it is visible
 * ([setHomeVisible]). An inexact, non-waking alarm at local midnight flushes the sensor so the
 * day's last steps are counted to that day. State (30 days) is a small JSON string in preferences.
 */
class StepStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private var state: StepLedger.State = load()
    private var registered: SensorEventListener2? = null
    private var homeVisible = false
    private var savedAt = 0L
    private var flushWaiter: CompletableDeferred<Unit>? = null

    private val listener = object : SensorEventListener2 {
        override fun onSensorChanged(event: SensorEvent) {
            val counter = event.values.firstOrNull()?.toLong() ?: return
            // Batched readings arrive late: date each one by when the step happened, not when it was delivered.
            val ageMs = (SystemClock.elapsedRealtimeNanos() - event.timestamp).coerceAtLeast(0L) / 1_000_000L
            onCounter(counter, today(System.currentTimeMillis() - ageMs))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onFlushCompleted(sensor: Sensor?) {
            flushWaiter?.complete(Unit)
        }
    }

    private fun manager(): SensorManager? = app.getSystemService(SensorManager::class.java)

    private fun permitted(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** A `sensors` app asked for step totals: from now on the listener runs. Persisted. */
    fun markWanted() {
        if (!prefs.getBoolean(WANTED, false)) prefs.edit().putBoolean(WANTED, true).apply()
        start()
    }

    /** Starts the listener and the midnight alarm when permitted and wanted; safe to call any time. */
    fun start() {
        synchronized(lock) {
            if (!prefs.getBoolean(WANTED, false) || !permitted()) return
            scheduleMidnight()
            if (registered != null) return
            val manager = manager() ?: return
            val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
            val latency = if (homeVisible) 0 else HIDDEN_LATENCY_US
            if (manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, latency)) registered = listener
        }
    }

    /**
     * No app holds `sensors` any more (revoked, dropped from its manifest, or uninstalled): the listener, the
     * midnight alarm and the "wanted" flag go. A later `sensors` call asks again ([markWanted]). Safe to call any time.
     */
    suspend fun stopIfUnheld(repository: app.deft.data.AppRepository) {
        val held = repository.allApps().any { Capability.SENSORS in repository.grantedCapabilities(it.id) }
        if (held) return
        synchronized(lock) {
            prefs.edit().putBoolean(WANTED, false).apply()
            if (registered != null) {
                manager()?.unregisterListener(listener)
                registered = null
            }
            save()
            val alarms = app.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                app, 0, Intent(app, MidnightReceiver::class.java).setAction(ACTION_MIDNIGHT),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pending != null) runCatching { alarms.cancel(pending) }
        }
    }

    /** The home activity calls this on resume (true) and pause (false); it changes the batching only. */
    fun setHomeVisible(visible: Boolean) {
        synchronized(lock) {
            if (homeVisible == visible) return
            homeVisible = visible
            if (!visible) save()
            val manager = manager() ?: return
            if (registered != null) {
                manager.unregisterListener(listener)
                registered = null
            }
        }
        start()
    }

    /** Feeds a reading into the ledger and saves it (at most once a minute unless the day or a reboot changed it). */
    internal fun onCounter(counter: Long, date: String) {
        synchronized(lock) {
            val before = state
            state = StepLedger.onReading(state, counter, date)
            // The counter is cumulative, so a lost write costs nothing; only a new day or a reboot must be saved now.
            val now = SystemClock.elapsedRealtime()
            if (state.date != before.date || counter < before.lastCounter || now - savedAt >= SAVE_GAP_MS) save(now)
        }
    }

    private fun save(now: Long = SystemClock.elapsedRealtime()) {
        savedAt = now
        prefs.edit().putString(STATE, encode(state)).apply()
    }

    /** Asks the sensor to deliver its batched readings, and waits briefly for them. */
    suspend fun flush() {
        val manager = manager() ?: return
        val waiter = CompletableDeferred<Unit>()
        synchronized(lock) {
            if (registered == null) return
            flushWaiter = waiter
        }
        if (!manager.flush(listener)) return
        withTimeoutOrNull(FLUSH_WAIT_MS) { waiter.await() }
    }

    fun snapshot(): StepLedger.Snapshot = synchronized(lock) { StepLedger.snapshot(state, today(System.currentTimeMillis())) }

    fun snapshotJson(): JsonObject {
        val snap = snapshot()
        return buildJsonObject {
            put("today", JsonPrimitive(snap.today))
            put(
                "days",
                kotlinx.serialization.json.JsonArray(
                    snap.days.map { buildJsonObject { put("date", JsonPrimitive(it.date)); put("steps", JsonPrimitive(it.steps)) } },
                ),
            )
        }
    }

    /** One alarm per local midnight; inexact and non-waking, so it costs no battery. */
    private fun scheduleMidnight() {
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val next = LocalDate.now(ZoneId.systemDefault()).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = PendingIntent.getBroadcast(
            app, 0, Intent(app, MidnightReceiver::class.java).setAction(ACTION_MIDNIGHT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { alarms.set(AlarmManager.RTC, next + 5_000L, intent) }
    }

    private fun today(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun load(): StepLedger.State {
        val text = prefs.getString(STATE, null) ?: return StepLedger.State()
        return runCatching { decode(text) }.getOrDefault(StepLedger.State())
    }

    /** Fires at local midnight (also after the process was killed): flush, then book the next one. */
    class MidnightReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            val store = get(context)
            store.start()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    store.flush()
                } finally {
                    store.start()
                    pending.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_MIDNIGHT = "app.deft.STEPS_MIDNIGHT"
        private const val PREFS = "step_store"
        private const val STATE = "state"
        private const val WANTED = "wanted"
        private const val HIDDEN_LATENCY_US = 10 * 60 * 1_000_000
        private const val FLUSH_WAIT_MS = 500L
        private const val SAVE_GAP_MS = 60_000L

        @Volatile private var instance: StepStore? = null

        fun get(context: Context): StepStore =
            instance ?: synchronized(this) { instance ?: StepStore(context).also { instance = it } }

        internal fun encode(state: StepLedger.State): String = buildJsonObject {
            state.date?.let { put("date", JsonPrimitive(it)) }
            put("base", JsonPrimitive(state.dayBase))
            put("last", JsonPrimitive(state.lastCounter))
            put("days", buildJsonObject { for ((d, n) in state.days) put(d, JsonPrimitive(n)) })
        }.toString()

        internal fun decode(text: String): StepLedger.State {
            val o = Json.parseToJsonElement(text).jsonObject
            return StepLedger.State(
                date = o["date"]?.jsonPrimitive?.content,
                dayBase = o["base"]?.jsonPrimitive?.longOrNull ?: 0,
                lastCounter = o["last"]?.jsonPrimitive?.longOrNull ?: 0,
                days = o["days"]?.jsonObject?.mapValues { it.value.jsonPrimitive.intOrNull ?: 0 }.orEmpty(),
            )
        }
    }
}
