package app.deft.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * `notifications.*` (capability `notifications.listen`) rules. Pure, unit tested; the
 * Android side is `notifications.DeftNotificationListener` and `bridge.modules.NotificationsModule`.
 *
 * What the holder sees of another app's notification:
 * - Never the launcher's own notifications ([forwardable]); those include every web app's
 *   `notify.post`, so one web app never reads another's through this power.
 * - `VISIBILITY_SECRET` (the app asked never to show it on the lock screen, or the channel was set
 *   so by the user): title, text and subText are left out and `redacted` is true.
 * - The lock-screen case: while the phone is locked and the user chose to hide sensitive content
 *   on the lock screen, a `VISIBILITY_PRIVATE` notification is shown as the lock screen shows it:
 *   its public version, or nothing (`redacted` true).
 * - Every text is cut to [TEXT_MAX] characters, labels to [LABEL_MAX], at most [MAX_ACTIONS] actions.
 */
object NotificationsSpec {
    const val GROUP = "notif-listener"
    const val EVENT_POSTED = "notifications.posted"
    const val EVENT_REMOVED = "notifications.removed"

    const val TEXT_MAX = 1000
    const val LABEL_MAX = 200
    const val ACTION_TITLE_MAX = 100
    const val MAX_ACTIONS = 10
    const val KEY_MAX = 1024
    const val REPLY_MAX = 1000

    /** At most one audit row per hour for forwarded events (the 30-day review needs a recent use). */
    const val AUDIT_EVERY_MS = 3_600_000L

    // android.app.Notification visibility values, mirrored so this file needs no Android.
    const val VISIBILITY_PUBLIC = 1
    const val VISIBILITY_PRIVATE = 0
    const val VISIBILITY_SECRET = -1

    /** android.app.NotificationManager.VISIBILITY_NO_OVERRIDE: the channel does not change it. */
    const val NO_OVERRIDE = -1000

    data class Action(val title: String?, val reply: Boolean)

    /** What the listener read from one notification, before redaction and caps. */
    data class Raw(
        val key: String,
        val pkg: String,
        val appLabel: String?,
        val title: String?,
        val text: String?,
        val subText: String?,
        val time: Long,
        val category: String?,
        val ongoing: Boolean,
        val visibility: Int,
        val channelLockscreen: Int = NO_OVERRIDE,
        val publicTitle: String? = null,
        val publicText: String? = null,
        val hasPublicVersion: Boolean = false,
        val actions: List<Action> = emptyList(),
    )

    /** The phone's state that decides the lock-screen case. */
    data class Privacy(val locked: Boolean, val hideSensitiveOnLockScreen: Boolean) {
        companion object {
            val UNLOCKED = Privacy(locked = false, hideSensitiveOnLockScreen = false)
        }
    }

    enum class Redaction { NONE, PUBLIC_VERSION, HIDDEN }

    fun forwardable(pkg: String, ownPackage: String): Boolean = pkg.isNotEmpty() && pkg != ownPackage

    /** The stricter of the notification's own visibility and the channel's lock-screen setting. */
    fun effectiveVisibility(visibility: Int, channelLockscreen: Int): Int =
        if (channelLockscreen == NO_OVERRIDE) visibility else minOf(visibility, channelLockscreen)

    fun redaction(raw: Raw, privacy: Privacy): Redaction {
        val v = effectiveVisibility(raw.visibility, raw.channelLockscreen)
        if (v <= VISIBILITY_SECRET) return Redaction.HIDDEN
        if (v == VISIBILITY_PRIVATE && privacy.locked && privacy.hideSensitiveOnLockScreen) {
            return if (raw.hasPublicVersion) Redaction.PUBLIC_VERSION else Redaction.HIDDEN
        }
        return Redaction.NONE
    }

    /** [s] trimmed and cut to [max] characters (never in the middle of a surrogate pair); blank is null. */
    fun cap(s: String?, max: Int): String? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.length <= max) return t
        val end = if (t[max - 1].isHighSurrogate()) max - 1 else max
        return t.substring(0, end)
    }

    /** `{key, package, appLabel, title, text, subText?, time, category?, ongoing, redacted, actions}`. */
    fun shape(raw: Raw, privacy: Privacy): JsonObject {
        val red = redaction(raw, privacy)
        val (title, text) = when (red) {
            Redaction.NONE -> raw.title to raw.text
            Redaction.PUBLIC_VERSION -> raw.publicTitle to raw.publicText
            Redaction.HIDDEN -> null to null
        }
        val subText = if (red == Redaction.NONE) cap(raw.subText, LABEL_MAX) else null
        return buildJsonObject {
            put("key", raw.key)
            put("package", raw.pkg)
            put("appLabel", cap(raw.appLabel, LABEL_MAX) ?: raw.pkg)
            put("title", cap(title, LABEL_MAX)?.let { JsonPrimitive(it) } ?: JsonNull)
            put("text", cap(text, TEXT_MAX)?.let { JsonPrimitive(it) } ?: JsonNull)
            if (subText != null) put("subText", subText)
            put("time", raw.time)
            cap(raw.category, 64)?.let { put("category", it) }
            put("ongoing", raw.ongoing)
            put("redacted", red != Redaction.NONE)
            put(
                "actions",
                JsonArray(
                    raw.actions.take(MAX_ACTIONS).mapIndexed { i, a ->
                        buildJsonObject {
                            put("index", i)
                            put("title", cap(a.title, ACTION_TITLE_MAX)?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("reply", a.reply)
                        }
                    },
                ),
            )
        }
    }

    /**
     * Android's `NotificationListenerService.REASON_*` as plain words; null for reasons not
     * listed (the event then has no `reason`).
     */
    fun reasonName(reason: Int): String? = when (reason) {
        1 -> "click"
        2 -> "dismissed"
        3 -> "dismissedAll"
        8, 9 -> "app"
        10 -> "listener"
        12 -> "group"
        18 -> "snoozed"
        19 -> "timeout"
        7, 17 -> "blocked"
        else -> null
    }

    fun removedPayload(key: String, reason: Int?): String = buildJsonObject {
        put("key", key)
        reason?.let(::reasonName)?.let { put("reason", it) }
    }.toString()

    /** What [RecentPosts] compares: the shaped event without `time`, which changes on every repost. */
    fun content(shaped: JsonObject): String = JsonObject(shaped.filterKeys { it != "time" }).toString()

    /** A posted event wakes a closed holder's background module only when it is not ongoing. */
    fun wakesBackground(ongoing: Boolean): Boolean = !ongoing

    fun auditDue(lastAt: Long, now: Long): Boolean = lastAt == 0L || now - lastAt >= AUDIT_EVERY_MS

    sealed class Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>()
        data class Bad(val message: String) : Parsed<Nothing>()
    }

    data class Reply(val key: String, val actionIndex: Int, val text: String)

    fun parseKey(args: JsonObject): Parsed<String> {
        val p = args["key"] as? JsonPrimitive
        if (p == null || !p.isString) return Parsed.Bad("key must be a string")
        val key = p.content
        if (key.isEmpty() || key.length > KEY_MAX) return Parsed.Bad("key must be 1 to $KEY_MAX characters")
        return Parsed.Ok(key)
    }

    /** `{key, actionIndex, text}`: actionIndex a whole number 0 to [MAX_ACTIONS]-1, text 1 to [REPLY_MAX]. */
    fun parseReply(args: JsonObject): Parsed<Reply> {
        val key = when (val k = parseKey(args)) {
            is Parsed.Bad -> return k
            is Parsed.Ok -> k.value
        }
        val ip = args["actionIndex"] as? JsonPrimitive
        val d = ip?.takeIf { !it.isString }?.doubleOrNull
        if (d == null || d != Math.floor(d) || d < 0 || d >= MAX_ACTIONS) {
            return Parsed.Bad("actionIndex must be a number from 0 to ${MAX_ACTIONS - 1}")
        }
        val tp = args["text"] as? JsonPrimitive
        if (tp == null || !tp.isString) return Parsed.Bad("text must be a string")
        val text = tp.contentOrNull.orEmpty()
        if (text.isBlank()) return Parsed.Bad("text must not be empty")
        if (text.length > REPLY_MAX) return Parsed.Bad("text must be at most $REPLY_MAX characters")
        return Parsed.Ok(Reply(key, d.toInt(), text))
    }

    /**
     * Drops a posted event whose payload is the same as the last one forwarded for that key
     * (progress bars and media players repost the same content often). Keeps [capacity] keys.
     * Not thread-safe; the listener uses it from one thread.
     */
    class RecentPosts(private val capacity: Int = 256) {
        private val last = object : LinkedHashMap<String, Int>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > capacity
        }

        /** True when [payload] repeats the last forwarded one for [key]; otherwise records it. */
        fun isRepeat(key: String, payload: String): Boolean {
            val h = payload.hashCode()
            if (last[key] == h) return true
            last[key] = h
            return false
        }

        fun forget(key: String) {
            last.remove(key)
        }

        fun clear() = last.clear()
    }
}
