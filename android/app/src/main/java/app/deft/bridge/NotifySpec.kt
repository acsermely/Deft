package app.deft.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Parsed `notify.post` / `notify.createChannel` arguments, free of Android types so
 * the rules can be unit-tested. [OriginNotifications] turns these into real notifications.
 */
object NotifySpec {
    val ID = Regex("^[A-Za-z0-9_.-]{1,64}$")
    val CHANNEL_ID = Regex("^[a-z0-9_-]{1,32}$")
    const val DEFAULT_CHANNEL = "default"
    const val MAX_CHANNELS = 10
    const val MAX_ACTIONS = 3
    const val MAX_IMAGE_BYTES = 1024 * 1024

    enum class Importance { MIN, LOW, DEFAULT, HIGH }

    data class Channel(val id: String, val name: String, val importance: Importance, val description: String?)

    data class Action(val id: String, val title: String, val open: Boolean)

    data class Reply(val actionId: String, val label: String, val title: String)

    data class Progress(val value: Int, val max: Int, val indeterminate: Boolean)

    data class Post(
        val id: String,
        val channel: String,
        val title: String,
        val body: String,
        val url: String?,
        val actions: List<Action>,
        val reply: Reply?,
        val progress: Progress?,
        val ongoing: Boolean,
        val silent: Boolean,
        val group: String?,
        val imageBase64: String?,
    )

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: (str(key) == "true")
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    fun channel(args: JsonObject): Result<Channel> {
        val id = args.str("id") ?: return fail("id required")
        if (!CHANNEL_ID.matches(id)) return fail("channel id must be 1-32 chars [a-z0-9_-]")
        val name = args.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: return fail("name required")
        val importance = when (args.str("importance") ?: "default") {
            "min" -> Importance.MIN
            "low" -> Importance.LOW
            "default" -> Importance.DEFAULT
            "high" -> Importance.HIGH
            else -> return fail("importance must be min, low, default or high")
        }
        return Result.success(Channel(id, name, importance, args.str("description")?.take(200)))
    }

    /** [generatedId] is used when the app gives no id (a new notification each time). */
    fun post(args: JsonObject, generatedId: () -> String): Result<Post> {
        val title = args.str("title") ?: return fail("title required")
        val body = args.str("body") ?: return fail("body required")
        val id = args.str("id") ?: generatedId()
        if (!ID.matches(id)) return fail("id must be 1-64 chars [A-Za-z0-9_.-]")
        val channel = args.str("channel") ?: DEFAULT_CHANNEL
        if (!CHANNEL_ID.matches(channel)) return fail("channel must be 1-32 chars [a-z0-9_-]")
        val actions = (args["actions"] as? JsonArray).orEmpty().map { el ->
            val o = el as? JsonObject ?: return fail("each action must be {id, title}")
            val aid = o.str("id")?.takeIf { ID.matches(it) } ?: return fail("action id must be 1-64 chars [A-Za-z0-9_.-]")
            val at = o.str("title")?.takeIf { it.isNotBlank() }?.take(40) ?: return fail("action $aid needs a title")
            Action(aid, at, o.bool("open"))
        }
        val reply = (args["reply"] as? JsonObject)?.let { o ->
            val aid = o.str("actionId") ?: "reply"
            if (!ID.matches(aid)) return fail("reply.actionId must be 1-64 chars [A-Za-z0-9_.-]")
            Reply(aid, o.str("label")?.take(60) ?: "Reply", o.str("title")?.take(40) ?: "Reply")
        }
        if (actions.size + (if (reply != null) 1 else 0) > MAX_ACTIONS) return fail("at most $MAX_ACTIONS actions including reply")
        if (actions.map { it.id }.toSet().size != actions.size) return fail("action ids must be unique")
        if (reply != null && actions.any { it.id == reply.actionId }) return fail("reply.actionId clashes with an action id")
        val progress = (args["progress"] as? JsonObject)?.let { o ->
            val max = o.int("max") ?: 100
            val value = o.int("value") ?: 0
            if (max !in 1..1_000_000 || value !in 0..max) return fail("progress needs 0 <= value <= max, max 1..1000000")
            Progress(value, max, o.bool("indeterminate"))
        }
        val group = args.str("group")
        if (group != null && !ID.matches(group)) return fail("group must be 1-64 chars [A-Za-z0-9_.-]")
        val image = args.str("image")
        if (image != null && image.length > MAX_IMAGE_BYTES * 4 / 3 + 4) return fail("image larger than $MAX_IMAGE_BYTES bytes")
        return Result.success(
            Post(
                id = id,
                channel = channel,
                title = title.take(200),
                body = body.take(4000),
                url = args.str("url"),
                actions = actions,
                reply = reply,
                progress = progress,
                ongoing = args.bool("ongoing"),
                silent = args.bool("silent"),
                group = group,
                imageBase64 = image,
            ),
        )
    }

    /** Android channel ids and notification tags are namespaced by app, so apps cannot touch each other's. */
    fun androidChannelId(webAppId: String, channel: String) = "deft:$webAppId:$channel"
    fun groupId(webAppId: String) = "deft:$webAppId"
    fun tag(webAppId: String, id: String) = "deft:$webAppId:$id"
    fun idFromTag(webAppId: String, tag: String?): String? = tag?.takeIf { it.startsWith("deft:$webAppId:") }?.removePrefix("deft:$webAppId:")

    private fun <T> fail(message: String): Result<T> = Result.failure(IllegalArgumentException(message))
}
