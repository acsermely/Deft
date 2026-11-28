package app.deft.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `location.*` argument rules, the stored geofence format, event payloads and the
 * GPS/network fix filter. Pure, unit tested; the Android side is `location.BackgroundLocation`,
 * `location.Geofences` and `bridge.modules.LocationModule`.
 */
object LocationSpec {
    const val MIN_INTERVAL_MS = 10_000L
    const val MAX_INTERVAL_MS = 3_600_000L
    const val MAX_MIN_DISTANCE_M = 100_000.0
    const val MIN_RADIUS_M = 100.0
    const val MAX_RADIUS_M = 100_000.0
    const val MIN_EXPIRES_MS = 60_000L
    const val MAX_EXPIRES_MS = 365L * 24 * 3600 * 1000
    const val MAX_GEOFENCES = 20
    const val MAX_WATCHES = 4
    const val ID_MAX = 64

    private val ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,$ID_MAX}$")

    data class Watch(val intervalMs: Long, val minDistanceM: Float)

    /** One geofence of one app. [expiresAt] is epoch milliseconds, or null for "until removed". */
    data class Geofence(
        val id: String,
        val lat: Double,
        val lng: Double,
        val radiusM: Float,
        val expiresAt: Long?,
    ) {
        fun expired(now: Long): Boolean = expiresAt != null && expiresAt <= now

        /** What `addProximityAlert` wants: milliseconds from [now], or -1 for no expiry. */
        fun remainingMs(now: Long): Long = if (expiresAt == null) -1L else (expiresAt - now).coerceAtLeast(1L)
    }

    sealed class Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>()
        data class Bad(val message: String) : Parsed<Nothing>()
    }

    /** A JSON number (not a numeric string), or null when absent or not a number. */
    private fun number(args: JsonObject, key: String): Double? {
        val p = args[key] as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.doubleOrNull?.takeIf { it.isFinite() }
    }

    private fun present(args: JsonObject, key: String) = args[key] != null && args[key] !is JsonNull

    fun validId(id: String?): Boolean = id != null && ID_PATTERN.matches(id)

    /** `location.watchBackground {intervalMs ≥ 10000, minDistanceM?}`. */
    fun parseWatch(args: JsonObject): Parsed<Watch> {
        val interval = number(args, "intervalMs")
            ?: return Parsed.Bad("intervalMs must be a number")
        if (interval < MIN_INTERVAL_MS || interval > MAX_INTERVAL_MS) {
            return Parsed.Bad("intervalMs must be from $MIN_INTERVAL_MS to $MAX_INTERVAL_MS")
        }
        var distance = 0.0
        if (present(args, "minDistanceM")) {
            distance = number(args, "minDistanceM") ?: return Parsed.Bad("minDistanceM must be a number")
            if (distance < 0 || distance > MAX_MIN_DISTANCE_M) {
                return Parsed.Bad("minDistanceM must be from 0 to ${MAX_MIN_DISTANCE_M.toLong()}")
            }
        }
        return Parsed.Ok(Watch(interval.toLong(), distance.toFloat()))
    }

    /** `location.geofenceAdd {id, lat, lng, radiusM ≥ 100, expiresMs?}`; [now] fixes the expiry time. */
    fun parseGeofence(args: JsonObject, now: Long): Parsed<Geofence> {
        val id = (args["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!validId(id)) return Parsed.Bad("id must be 1 to $ID_MAX letters, digits, '.', '_' or '-'")
        val lat = number(args, "lat") ?: return Parsed.Bad("lat must be a number")
        if (lat < -90.0 || lat > 90.0) return Parsed.Bad("lat must be from -90 to 90")
        val lng = number(args, "lng") ?: return Parsed.Bad("lng must be a number")
        if (lng < -180.0 || lng > 180.0) return Parsed.Bad("lng must be from -180 to 180")
        val radius = number(args, "radiusM") ?: return Parsed.Bad("radiusM must be a number")
        if (radius < MIN_RADIUS_M || radius > MAX_RADIUS_M) {
            return Parsed.Bad("radiusM must be from ${MIN_RADIUS_M.toLong()} to ${MAX_RADIUS_M.toLong()}")
        }
        var expiresAt: Long? = null
        if (present(args, "expiresMs")) {
            val ms = number(args, "expiresMs") ?: return Parsed.Bad("expiresMs must be a number")
            if (ms < MIN_EXPIRES_MS || ms > MAX_EXPIRES_MS) {
                return Parsed.Bad("expiresMs must be from $MIN_EXPIRES_MS to $MAX_EXPIRES_MS")
            }
            expiresAt = now + ms.toLong()
        }
        return Parsed.Ok(Geofence(id!!, lat, lng, radius.toFloat(), expiresAt))
    }

    /**
     * The app's list after adding [fence] to [current] at [now]: expired entries drop out, an
     * entry with the same id is replaced, and more than [MAX_GEOFENCES] is refused (null).
     */
    fun withAdded(current: List<Geofence>, fence: Geofence, now: Long): List<Geofence>? {
        val kept = current.filter { !it.expired(now) && it.id != fence.id }
        if (kept.size >= MAX_GEOFENCES) return null
        return kept + fence
    }

    // ---- stored form (private SharedPreferences, one JSON array per app) ----

    fun encode(list: List<Geofence>): String = buildJsonArray {
        for (g in list) {
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(g.id))
                    put("lat", JsonPrimitive(g.lat))
                    put("lng", JsonPrimitive(g.lng))
                    put("radiusM", JsonPrimitive(g.radiusM))
                    g.expiresAt?.let { put("expiresAt", JsonPrimitive(it)) }
                },
            )
        }
    }.toString()

    /** Reads what [encode] wrote; broken entries are skipped, broken text is an empty list. */
    fun decode(text: String?): List<Geofence> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = runCatching { BridgeJson.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el -> decodeOne(el) }.distinctBy { it.id }.take(MAX_GEOFENCES)
    }

    private fun decodeOne(el: JsonElement): Geofence? {
        val o = el as? JsonObject ?: return null
        val id = (o["id"] as? JsonPrimitive)?.content?.takeIf { validId(it) } ?: return null
        val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in -90.0..90.0 } ?: return null
        val lng = (o["lng"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in -180.0..180.0 } ?: return null
        val radius = (o["radiusM"] as? JsonPrimitive)?.doubleOrNull
            ?.takeIf { it in MIN_RADIUS_M..MAX_RADIUS_M } ?: return null
        val expiresAt = (o["expiresAt"] as? JsonPrimitive)?.longOrNull
        return Geofence(id, lat, lng, radius.toFloat(), expiresAt)
    }

    // ---- payloads ----

    fun geofenceJson(g: Geofence): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(g.id))
        put("lat", JsonPrimitive(g.lat))
        put("lng", JsonPrimitive(g.lng))
        put("radiusM", JsonPrimitive(g.radiusM))
        put("expiresAt", g.expiresAt?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    /** `location.geofence` event and `geofence` trigger payload. */
    fun geofencePayload(id: String, entering: Boolean): String = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("entering", JsonPrimitive(entering))
    }.toString()

    /** A location fix as Android reports it; optional fields are null when the provider has none. */
    data class Fix(
        val lat: Double,
        val lng: Double,
        val accuracy: Float?,
        val altitude: Double?,
        val speed: Float?,
        val bearing: Float?,
        val time: Long,
        val provider: String,
    )

    /** `location.fix` payload. */
    fun fixPayload(watchId: String, fix: Fix): String = buildJsonObject {
        put("watchId", JsonPrimitive(watchId))
        put("lat", JsonPrimitive(fix.lat))
        put("lng", JsonPrimitive(fix.lng))
        put("accuracy", fix.accuracy?.let { JsonPrimitive(it) } ?: JsonNull)
        fix.altitude?.let { put("altitude", JsonPrimitive(it)) }
        fix.speed?.let { put("speed", JsonPrimitive(it)) }
        fix.bearing?.let { put("bearing", JsonPrimitive(it)) }
        put("time", JsonPrimitive(fix.time))
        put("provider", JsonPrimitive(fix.provider))
    }.toString()

    /**
     * Whether a fix from [provider] at [now] is passed on, given the last GPS fix time
     * ([lastGpsAt], 0 for none). GPS always is; a network fix only when GPS has been silent for
     * two intervals, so the app does not get two fixes for every interval.
     */
    fun acceptFix(provider: String, now: Long, lastGpsAt: Long, intervalMs: Long): Boolean {
        if (provider == GPS) return true
        return lastGpsAt == 0L || now - lastGpsAt >= 2 * intervalMs
    }

    const val GPS = "gps"
    const val NETWORK = "network"
}
