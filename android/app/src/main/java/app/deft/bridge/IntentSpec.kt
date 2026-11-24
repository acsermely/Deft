package app.deft.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * PARITY E2: argument rules for `intents.start` and `intents.startForResult`, free of Android
 * types so they can be unit tested. Every rule that keeps an app from sending the launcher's own
 * files, or steering other apps with arbitrary actions, lives here.
 */
object IntentSpec {
    /** The short action names an app may use, and the Android action each one stands for. */
    val ACTIONS: Map<String, String> = mapOf(
        "SEND" to "android.intent.action.SEND",
        "SEND_MULTIPLE" to "android.intent.action.SEND_MULTIPLE",
        "VIEW" to "android.intent.action.VIEW",
        "EDIT" to "android.intent.action.EDIT",
        "INSERT" to "android.intent.action.INSERT",
        "PICK" to "android.intent.action.PICK",
        "GET_CONTENT" to "android.intent.action.GET_CONTENT",
        "IMAGE_CAPTURE" to "android.media.action.IMAGE_CAPTURE",
        "VIDEO_CAPTURE" to "android.media.action.VIDEO_CAPTURE",
        "SET_ALARM" to "android.intent.action.SET_ALARM",
        "SET_TIMER" to "android.intent.action.SET_TIMER",
        "SHOW_ALARMS" to "android.intent.action.SHOW_ALARMS",
        "DIAL" to "android.intent.action.DIAL",
        "SENDTO" to "android.intent.action.SENDTO",
        "WEB_SEARCH" to "android.intent.action.WEB_SEARCH",
        "INSERT_OR_EDIT" to "android.intent.action.INSERT_OR_EDIT",
    )

    /** Schemes an app may put in `data`. `content:` and `file:` are launcher or other private files and are never sent out. */
    val DATA_SCHEMES: Set<String> = setOf("http", "https", "mailto", "tel", "sms", "geo")

    const val MAX_EXTRAS = 16
    const val MAX_EXTRA_VALUE = 4096

    private const val STREAM = "android.intent.extra.STREAM"
    private val TEXT_EXTRAS = setOf("android.intent.extra.TEXT", "android.intent.extra.SUBJECT")
    private val EXTRA_KEY = Regex("^android\\.(intent\\.extra|provider)\\.[A-Za-z0-9_.]{1,100}$")
    private val MIME = Regex("^[a-z0-9.+-]+/[a-z0-9.+*-]+$")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val LAUNCHER_URI = Regex("^\\s*(content|file):", RegexOption.IGNORE_CASE)

    /** A checked intent: [androidAction] is the full Android action name. Values are plain strings. */
    data class Spec(
        val androidAction: String,
        val shortAction: String,
        val type: String?,
        val data: String?,
        val extras: Map<String, String>,
    )

    /** Checks the args of `intents.start` / `intents.startForResult`. Failures carry a plain message. */
    fun parse(args: JsonObject): Result<Spec> = runCatching { read(args) }

    private fun read(args: JsonObject): Spec {
        val shortAction = string(args, "action") ?: refuse("action required")
        val androidAction = ACTIONS[shortAction] ?: refuse("action not allowed")

        val type = string(args, "type")?.also {
            if (it.length > 255 || !MIME.matches(it)) refuse("type is not a MIME type")
        }

        val data = string(args, "data")?.also { checkData(it) }

        if (shortAction == "SEND_MULTIPLE" && data != null) refuse("SEND_MULTIPLE takes text only")

        val extras = LinkedHashMap<String, String>()
        when (val raw = args["extras"]) {
            null -> Unit
            is JsonObject -> {
                if (raw.size > MAX_EXTRAS) refuse("too many extras")
                for ((key, value) in raw) {
                    if (!EXTRA_KEY.matches(key) || key == STREAM) refuse("extra $key is not allowed")
                    if (shortAction == "SEND_MULTIPLE" && key !in TEXT_EXTRAS) refuse("SEND_MULTIPLE takes text only")
                    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: refuse("extra $key must be a string")
                    if (text.length > MAX_EXTRA_VALUE) refuse("extra $key is too long")
                    if (LAUNCHER_URI.containsMatchIn(text)) refuse("extra $key names a file, which cannot be sent")
                    extras[key] = text
                }
            }
            else -> refuse("extras must be an object")
        }

        return Spec(androidAction, shortAction, type, data, extras)
    }

    private fun checkData(data: String) {
        val scheme = SCHEME.find(data)?.groupValues?.get(1)?.lowercase() ?: refuse("data must be a URI with a scheme")
        if (scheme == "content" || scheme == "file") refuse("files cannot be sent to other apps")
        if (scheme !in DATA_SCHEMES) refuse("data scheme $scheme is not allowed")
    }

    /** A present, non-null string argument, or null when the key is absent. Any other type is refused. */
    private fun string(args: JsonObject, key: String): String? {
        val raw = args[key] ?: return null
        return (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: refuse("$key must be a string")
    }

    private fun refuse(message: String): Nothing = throw IllegalArgumentException(message)
}
