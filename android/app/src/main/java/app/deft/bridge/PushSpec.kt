package app.deft.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Where a UnifiedPush message for a web app goes, and what it carries. Pure, unit
 * tested; the Android side is `push.PushDelivery`. Message contents are never logged.
 */
object PushSpec {
    /** Largest message UnifiedPush delivers; bigger ones are dropped. */
    const val MAX_BYTES = 4096

    const val EVENT_MESSAGE = "push.message"
    const val EVENT_ENDPOINT_CHANGED = "push.endpointChanged"
    const val EVENT_UNREGISTERED = "push.unregistered"

    const val TITLE_MAX = 200
    const val BODY_MAX = 1000

    /**
     * The main way a message reaches the app:
     * - [EVENT]: the app is open, `push.message` goes to the page now;
     * - [TRIGGER]: the app declares the `push` trigger and holds `background`, its module runs;
     * - [NOTIFY]: the payload is JSON with `title` and `body`, it shows as a notification;
     * - [QUEUE]: nothing else applies.
     * In every case but [EVENT] the `push.message` event also waits for the next open.
     */
    enum class Route { EVENT, TRIGGER, NOTIFY, QUEUE }

    fun route(live: Boolean, wantsTrigger: Boolean, hasNotification: Boolean): Route = when {
        live -> Route.EVENT
        wantsTrigger -> Route.TRIGGER
        hasNotification -> Route.NOTIFY
        else -> Route.QUEUE
    }

    fun tooLarge(bytes: ByteArray): Boolean = bytes.size > MAX_BYTES

    /** `push.message` and `push` trigger payload: `{base64}`. */
    fun payload(bytes: ByteArray): String =
        buildJsonObject { put("base64", JsonPrimitive(Base64.getEncoder().encodeToString(bytes))) }.toString()

    data class Notification(val title: String, val body: String)

    /**
     * The notification a message asks for: valid UTF-8 JSON object whose `title` and `body`
     * are non-blank strings. Anything else is null. Text is trimmed and cut to the limits.
     */
    fun notification(bytes: ByteArray): Notification? {
        if (bytes.isEmpty() || tooLarge(bytes)) return null
        val text = utf8(bytes) ?: return null
        val obj = runCatching { BridgeJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val title = obj.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val body = obj.string("body")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Notification(title.take(TITLE_MAX), body.take(BODY_MAX))
    }

    /** `push.register` / `push.endpointChanged` payload. */
    fun endpointPayload(endpoint: String): String =
        buildJsonObject { put("endpoint", JsonPrimitive(endpoint)) }.toString()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
