package app.deft.bridge

import app.deft.ime.ImeSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ArgSchema(
    val required: Set<String> = emptySet(),
    val stringMax: Map<String, Int> = emptyMap(),
    val arrayMax: Map<String, Int> = emptyMap(),
)

object BridgeArgs {
    private const val DEFAULT_STRING = 8_192
    private const val SHORT = 256
    private const val BODY = 4_096

    private val schemas: Map<String, ArgSchema> = mapOf(
        "background.done" to ArgSchema(),
        "contacts.list" to ArgSchema(stringMax = mapOf("query" to SHORT)),
        "contacts.search" to ArgSchema(required = setOf("query"), stringMax = mapOf("query" to SHORT)),
        "contacts.get" to ArgSchema(required = setOf("id"), stringMax = mapOf("id" to SHORT)),
        "contacts.create" to ArgSchema(
            required = setOf("name"),
            stringMax = mapOf("name" to SHORT, "phone" to 32, "email" to SHORT, "org" to SHORT),
        ),
        "contacts.update" to ArgSchema(
            required = setOf("id"),
            stringMax = mapOf("id" to SHORT, "name" to SHORT, "phone" to 32, "email" to SHORT, "org" to SHORT),
        ),
        "contacts.delete" to ArgSchema(required = setOf("id"), stringMax = mapOf("id" to SHORT)),
        "sms.listThreads" to ArgSchema(stringMax = mapOf("limit" to 8, "offset" to 12)),
        "sms.listMessages" to ArgSchema(stringMax = mapOf("threadId" to SHORT, "limit" to 8, "offset" to 12)),
        "sms.send" to ArgSchema(
            required = setOf("to", "body"),
            stringMax = mapOf("to" to 32, "body" to BODY, "subscriptionId" to 12),
        ),
        "sms.sendMms" to ArgSchema(
            required = setOf("to"),
            stringMax = mapOf("to" to 32, "body" to BODY, "base64" to 400_000, "mime" to 80),
        ),
        "sms.markRead" to ArgSchema(required = setOf("id"), stringMax = mapOf("id" to SHORT)),
        "sms.delete" to ArgSchema(required = setOf("id"), stringMax = mapOf("id" to SHORT)),
        "telephony.call" to ArgSchema(required = setOf("e164"), stringMax = mapOf("e164" to 32, "subscriptionId" to 12)),
        "telephony.listSims" to ArgSchema(),
        "telephony.blockedList" to ArgSchema(),
        "telephony.block" to ArgSchema(required = setOf("e164"), stringMax = mapOf("e164" to 32)),
        "telephony.unblock" to ArgSchema(required = setOf("e164"), stringMax = mapOf("e164" to 32)),
        "telephony.ussd" to ArgSchema(required = setOf("code"), stringMax = mapOf("code" to 32, "subscriptionId" to 12)),
        "telephony.answer" to ArgSchema(),
        "telephony.reject" to ArgSchema(),
        "telephony.end" to ArgSchema(),
        "telephony.hold" to ArgSchema(),
        "telephony.dtmf" to ArgSchema(required = setOf("digit"), stringMax = mapOf("digit" to 1)),
        "telephony.listCalls" to ArgSchema(stringMax = mapOf("limit" to 8, "offset" to 12)),
        "telephony.deleteCall" to ArgSchema(required = setOf("id"), stringMax = mapOf("id" to SHORT)),
        "files.pickTree" to ArgSchema(),
        "files.pickMedia" to ArgSchema(stringMax = mapOf("mime" to SHORT)),
        "files.listVolumes" to ArgSchema(),
        "net.fetch" to ArgSchema(required = setOf("url"), stringMax = mapOf("url" to NetFetchSpec.MAX_URL)),
        "files.listDir" to ArgSchema(required = setOf("path"), stringMax = mapOf("path" to DEFAULT_STRING)),
        "files.stat" to ArgSchema(required = setOf("path"), stringMax = mapOf("path" to DEFAULT_STRING)),
        "files.read" to ArgSchema(required = setOf("path"), stringMax = mapOf("path" to DEFAULT_STRING)),
        "files.write" to ArgSchema(
            required = setOf("path"),
            stringMax = mapOf("path" to DEFAULT_STRING, "content" to 512 * 1024, "base64" to 400_000),
        ),
        "files.copy" to ArgSchema(
            required = setOf("from", "to"),
            stringMax = mapOf("from" to DEFAULT_STRING, "to" to DEFAULT_STRING),
        ),
        "files.move" to ArgSchema(
            required = setOf("from", "to"),
            stringMax = mapOf("from" to DEFAULT_STRING, "to" to DEFAULT_STRING),
        ),
        "files.rename" to ArgSchema(
            required = setOf("path", "name"),
            stringMax = mapOf("path" to DEFAULT_STRING, "name" to SHORT),
        ),
        "files.delete" to ArgSchema(required = setOf("path"), stringMax = mapOf("path" to DEFAULT_STRING)),
        "files.share" to ArgSchema(required = setOf("path"), stringMax = mapOf("path" to DEFAULT_STRING)),
        "files.openWith" to ArgSchema(required = setOf("path"), stringMax = mapOf("path" to DEFAULT_STRING)),
        "calendar.listCalendars" to ArgSchema(),
        "calendar.listEvents" to ArgSchema(stringMax = mapOf("calendarId" to SHORT)),
        "calendar.create" to ArgSchema(
            required = setOf("calendarId", "title", "dtStart", "dtEnd"),
            stringMax = mapOf("calendarId" to SHORT, "title" to SHORT, "description" to BODY),
        ),
        "calendar.update" to ArgSchema(
            required = setOf("eventId"),
            stringMax = mapOf("eventId" to SHORT, "title" to SHORT, "description" to BODY),
        ),
        "calendar.delete" to ArgSchema(required = setOf("eventId"), stringMax = mapOf("eventId" to SHORT)),
        "alarms.schedule" to ArgSchema(
            required = setOf("triggerAt", "title", "body"),
            stringMax = mapOf("title" to SHORT, "body" to BODY, "url" to DEFAULT_STRING),
        ),
        "alarms.cancel" to ArgSchema(required = setOf("id"), stringMax = mapOf("id" to SHORT)),
        "alarms.list" to ArgSchema(),
        "notify.post" to ArgSchema(
            required = setOf("title", "body"),
            stringMax = mapOf("title" to SHORT, "body" to BODY, "url" to DEFAULT_STRING, "id" to 64, "channel" to 32, "group" to 64, "image" to 1_400_000),
            arrayMax = mapOf("actions" to 3),
        ),
        "notify.update" to ArgSchema(
            required = setOf("id", "title", "body"),
            stringMax = mapOf("title" to SHORT, "body" to BODY, "url" to DEFAULT_STRING, "id" to 64, "channel" to 32, "group" to 64, "image" to 1_400_000),
            arrayMax = mapOf("actions" to 3),
        ),
        "notify.cancel" to ArgSchema(required = setOf("id"), stringMax = mapOf("id" to 64)),
        "notify.list" to ArgSchema(),
        "notify.createChannel" to ArgSchema(required = setOf("id", "name"), stringMax = mapOf("id" to 32, "name" to 40, "importance" to 16, "description" to 200)),
        "notify.deleteChannel" to ArgSchema(required = setOf("id"), stringMax = mapOf("id" to 32)),
        "notify.listChannels" to ArgSchema(),
        "intents.open" to ArgSchema(required = setOf("url"), stringMax = mapOf("url" to DEFAULT_STRING)),
        "intents.start" to ArgSchema(
            required = setOf("action"),
            stringMax = mapOf("action" to SHORT, "type" to SHORT, "data" to DEFAULT_STRING),
        ),
        "intents.startForResult" to ArgSchema(
            required = setOf("action"),
            stringMax = mapOf("action" to SHORT, "type" to SHORT, "data" to DEFAULT_STRING),
        ),
        "intents.readShare" to ArgSchema(required = setOf("id"), stringMax = mapOf("id" to SHORT)),
        "intents.share" to ArgSchema(
            stringMax = mapOf("title" to SHORT, "text" to 64 * 1024, "url" to DEFAULT_STRING),
            arrayMax = mapOf("files" to 10),
        ),
        "files.saveAs" to ArgSchema(
            required = setOf("name", "base64"),
            stringMax = mapOf("name" to SHORT, "mime" to SHORT, "base64" to 7_000_000),
        ),
        "clipboard.readText" to ArgSchema(),
        "clipboard.watchStart" to ArgSchema(),
        "clipboard.watchStop" to ArgSchema(),
        "tiles.request" to ArgSchema(required = setOf("label"), stringMax = mapOf("label" to 30)),
        "tiles.update" to ArgSchema(stringMax = mapOf("label" to 30, "subtitle" to 40, "active" to 8, "open" to 8)),
        "tiles.release" to ArgSchema(),
        "widgets.update" to ArgSchema(required = setOf("title"), stringMax = mapOf("id" to 32, "title" to 40, "text" to 120, "value" to 16, "url" to DEFAULT_STRING)),
        "widgets.remove" to ArgSchema(stringMax = mapOf("id" to 32)),
        "widgets.list" to ArgSchema(),
        "display.keepScreenOn" to ArgSchema(required = setOf("on"), stringMax = mapOf("on" to 8)),
        "display.setOrientation" to ArgSchema(required = setOf("orientation"), stringMax = mapOf("orientation" to 32)),
        "display.print" to ArgSchema(stringMax = mapOf("title" to SHORT)),
        "display.secure" to ArgSchema(required = setOf("on"), stringMax = mapOf("on" to 8)),
        "display.hideOverlays" to ArgSchema(required = setOf("on"), stringMax = mapOf("on" to 8)),
        "torch.set" to ArgSchema(required = setOf("on"), stringMax = mapOf("on" to 8, "level" to 4)),
        "torch.state" to ArgSchema(),
        "pip.enter" to ArgSchema(stringMax = mapOf("aspect" to 8)),
        "pip.setAuto" to ArgSchema(required = setOf("on"), stringMax = mapOf("on" to 8, "aspect" to 8)),
        "sensors.list" to ArgSchema(),
        "sensors.subscribe" to ArgSchema(required = setOf("type"), stringMax = mapOf("type" to 32, "rateHz" to 8)),
        "sensors.unsubscribe" to ArgSchema(required = setOf("id"), stringMax = mapOf("id" to SHORT)),
        "audio.getVolumes" to ArgSchema(),
        "audio.setVolume" to ArgSchema(required = setOf("stream", "value"), stringMax = mapOf("stream" to 16, "value" to 8)),
        "audio.getRingerMode" to ArgSchema(),
        "audio.setRingerMode" to ArgSchema(required = setOf("mode"), stringMax = mapOf("mode" to 16)),
        "audio.devices" to ArgSchema(),
        "audio.route" to ArgSchema(required = setOf("target"), stringMax = mapOf("target" to 16)),
        "dnd.state" to ArgSchema(),
        "dnd.set" to ArgSchema(required = setOf("filter"), stringMax = mapOf("filter" to 16)),
        "dnd.requestAccess" to ArgSchema(),
        "keystore.generate" to ArgSchema(
            required = setOf("alias", "alg"),
            stringMax = mapOf("alias" to KeystoreAliases.MAX_LENGTH, "alg" to 16, "requireBiometric" to 8, "validitySeconds" to 8),
        ),
        "keystore.sign" to ArgSchema(required = setOf("alias", "base64"), stringMax = mapOf("alias" to KeystoreAliases.MAX_LENGTH, "base64" to 400_000)),
        "keystore.encrypt" to ArgSchema(required = setOf("alias", "base64"), stringMax = mapOf("alias" to KeystoreAliases.MAX_LENGTH, "base64" to 400_000)),
        "keystore.decrypt" to ArgSchema(
            required = setOf("alias", "iv", "ciphertext"),
            stringMax = mapOf("alias" to KeystoreAliases.MAX_LENGTH, "iv" to 64, "ciphertext" to 400_000),
        ),
        "keystore.list" to ArgSchema(),
        "keystore.delete" to ArgSchema(required = setOf("alias"), stringMax = mapOf("alias" to KeystoreAliases.MAX_LENGTH)),
        "media.query" to ArgSchema(
            required = setOf("kind"),
            stringMax = mapOf("kind" to 8, "since" to 20, "limit" to 8, "offset" to 12),
        ),
        "media.thumbnail" to ArgSchema(required = setOf("id", "kind"), stringMax = mapOf("id" to 20, "kind" to 8, "size" to 4)),
        "media.read" to ArgSchema(required = setOf("id", "kind"), stringMax = mapOf("id" to 20, "kind" to 8)),
        "speech.speak" to ArgSchema(
            required = setOf("text"),
            stringMax = mapOf("text" to 4_000, "lang" to 35, "rate" to 8, "pitch" to 8, "id" to SHORT),
        ),
        "speech.stop" to ArgSchema(),
        "speech.voices" to ArgSchema(),
        "speech.recognize" to ArgSchema(stringMax = mapOf("lang" to 35, "offline" to 8)),
        "speech.cancel" to ArgSchema(),
        "nfc.read" to ArgSchema(),
        "nfc.scanStart" to ArgSchema(),
        "nfc.scanStop" to ArgSchema(),
        "nfc.writeNdef" to ArgSchema(required = setOf("tagId", "records"), stringMax = mapOf("tagId" to SHORT), arrayMax = mapOf("records" to 16)),
        "nfc.hceRegister" to ArgSchema(required = setOf("aids"), arrayMax = mapOf("aids" to 16)),
        "nfc.hceUnregister" to ArgSchema(),
        "nfc.hceRespond" to ArgSchema(required = setOf("requestId", "base64"), stringMax = mapOf("requestId" to SHORT, "base64" to 6_000)),
        "nfc.paymentRegister" to ArgSchema(required = setOf("aids"), arrayMax = mapOf("aids" to 16)),
        "nfc.paymentUnregister" to ArgSchema(),
        "nfc.paymentRespond" to ArgSchema(required = setOf("requestId", "base64"), stringMax = mapOf("requestId" to SHORT, "base64" to 6_000)),
        "nfc.makeReadOnly" to ArgSchema(required = setOf("tagId"), stringMax = mapOf("tagId" to SHORT)),
        "nfc.transceive" to ArgSchema(required = setOf("tagId", "tech", "base64"), stringMax = mapOf("tagId" to SHORT, "tech" to 32, "base64" to 6_000)),
        "usb.list" to ArgSchema(),
        "usb.requestPermission" to ArgSchema(required = setOf("deviceName"), stringMax = mapOf("deviceName" to SHORT)),
        "usb.open" to ArgSchema(required = setOf("deviceName"), stringMax = mapOf("deviceName" to SHORT)),
        "usb.close" to ArgSchema(stringMax = mapOf("deviceName" to SHORT)),
        "usb.claimInterface" to ArgSchema(required = setOf("deviceName", "interface"), stringMax = mapOf("deviceName" to SHORT)),
        "usb.releaseInterface" to ArgSchema(required = setOf("deviceName", "interface"), stringMax = mapOf("deviceName" to SHORT)),
        "usb.controlTransfer" to ArgSchema(
            required = setOf("deviceName", "requestType", "request"),
            stringMax = mapOf("deviceName" to SHORT, "base64" to 90_000),
        ),
        "usb.bulkTransfer" to ArgSchema(required = setOf("deviceName", "endpoint"), stringMax = mapOf("deviceName" to SHORT, "base64" to 90_000)),
        "usb.interruptTransfer" to ArgSchema(required = setOf("deviceName", "endpoint"), stringMax = mapOf("deviceName" to SHORT, "base64" to 90_000)),
        // PARITY F8/G4: base64 of at most 64 KiB (65,536 bytes is 87,384 characters).
        "net.discover" to ArgSchema(required = setOf("type"), stringMax = mapOf("type" to 64)),
        "net.discoverStop" to ArgSchema(),
        "net.resolve" to ArgSchema(required = setOf("name", "type"), stringMax = mapOf("name" to SHORT, "type" to 64)),
        "net.tcpConnect" to ArgSchema(required = setOf("host", "port"), stringMax = mapOf("host" to 253, "port" to 8, "timeoutMs" to 8)),
        "net.tcpSend" to ArgSchema(required = setOf("socketId", "base64"), stringMax = mapOf("socketId" to 32, "base64" to 88_000)),
        "net.udpSend" to ArgSchema(
            required = setOf("host", "port", "base64"),
            stringMax = mapOf("host" to 253, "port" to 8, "base64" to 88_000),
        ),
        "net.udpListen" to ArgSchema(required = setOf("port"), stringMax = mapOf("port" to 8)),
        "net.close" to ArgSchema(required = setOf("socketId"), stringMax = mapOf("socketId" to 32)),
        "wifi.scan" to ArgSchema(),
        "wifi.info" to ArgSchema(),
        "wifi.openPanel" to ArgSchema(),
        "wifi.suggest" to ArgSchema(required = setOf("ssid"), stringMax = mapOf("ssid" to 128, "passphrase" to 64)),
        // PARITY G7: package names are at most 255 characters; usage times are epoch milliseconds.
        "apps.list" to ArgSchema(),
        "apps.icon" to ArgSchema(required = setOf("package"), stringMax = mapOf("package" to 256)),
        "apps.launch" to ArgSchema(required = setOf("package"), stringMax = mapOf("package" to 256)),
        "usage.stats" to ArgSchema(required = setOf("sinceMs", "untilMs"), stringMax = mapOf("sinceMs" to 20, "untilMs" to 20)),
        "device.id" to ArgSchema(),
        // PARITY E3: a sign-in URL is at most 8 KiB; the nonce is 32 hex characters.
        "auth.prepare" to ArgSchema(),
        "auth.start" to ArgSchema(required = setOf("url", "nonce"), stringMax = mapOf("url" to 8_192, "nonce" to 32)),
        // PARITY D3: type is one of five short names; title and text show in the notification.
        "fgs.start" to ArgSchema(required = setOf("type", "title"), stringMax = mapOf("type" to 20, "title" to 80, "text" to 200)),
        "fgs.update" to ArgSchema(stringMax = mapOf("title" to 80, "text" to 200)),
        "fgs.stop" to ArgSchema(),
        // PARITY D6
        "push.register" to ArgSchema(),
        "push.unregister" to ArgSchema(),
        "push.status" to ArgSchema(),
        // PARITY G5: numbers are checked in LocationSpec; ids are short names.
        "location.watchBackground" to ArgSchema(required = setOf("intervalMs"), stringMax = mapOf("intervalMs" to 24, "minDistanceM" to 24)),
        "location.watchStop" to ArgSchema(required = setOf("watchId"), stringMax = mapOf("watchId" to 32)),
        "location.geofenceAdd" to ArgSchema(
            required = setOf("id", "lat", "lng", "radiusM"),
            stringMax = mapOf(
                "id" to LocationSpec.ID_MAX, "lat" to 32, "lng" to 32, "radiusM" to 24, "expiresMs" to 24,
            ),
        ),
        "location.geofenceRemove" to ArgSchema(required = setOf("id"), stringMax = mapOf("id" to LocationSpec.ID_MAX)),
        "location.geofenceList" to ArgSchema(),
        // PARITY G10
        "accounts.add" to ArgSchema(required = setOf("name"), stringMax = mapOf("name" to AccountsSpec.NAME_MAX * 2)),
        "accounts.list" to ArgSchema(),
        "accounts.remove" to ArgSchema(required = setOf("name"), stringMax = mapOf("name" to AccountsSpec.NAME_MAX * 2)),
        "accounts.requestSync" to ArgSchema(required = setOf("name"), stringMax = mapOf("name" to AccountsSpec.NAME_MAX * 2)),
        "accounts.setPeriodicSync" to ArgSchema(
            required = setOf("name", "seconds"),
            stringMax = mapOf("name" to AccountsSpec.NAME_MAX * 2, "seconds" to 16),
        ),
        "accounts.listAll" to ArgSchema(),
        // PARITY G11: no arguments.
        "admin.lockNow" to ArgSchema(),
        "admin.status" to ArgSchema(),
        // PARITY F7b: action and flags checked in ScreeningSpec.parseRespond.
        "calls.screenRespond" to ArgSchema(
            required = setOf("callId", "action"),
            stringMax = mapOf(
                "callId" to app.deft.telephony.ScreeningSpec.CALL_ID_MAX,
                "action" to 16, "skipLog" to 8, "skipNotification" to 8,
            ),
        ),
        // PARITY G11 ime: only from the keyboard page; numbers and key names checked in ImeSpec.
        "ime.commitText" to ArgSchema(required = setOf("text"), stringMax = mapOf("text" to ImeSpec.TEXT_MAX)),
        "ime.deleteBefore" to ArgSchema(required = setOf("n"), stringMax = mapOf("n" to 8)),
        "ime.sendKey" to ArgSchema(required = setOf("key"), stringMax = mapOf("key" to 16)),
        "ime.setComposing" to ArgSchema(required = setOf("text"), stringMax = mapOf("text" to ImeSpec.TEXT_MAX)),
        "ime.finishComposing" to ArgSchema(),
        "ime.hide" to ArgSchema(),
        "ime.switchKeyboard" to ArgSchema(),
        "ime.setHeight" to ArgSchema(required = setOf("dp"), stringMax = mapOf("dp" to 8)),
        // PARITY G8: Android notification keys are "<user>|<package>|<id>|<tag>|<uid>"; replies at most 1000 characters.
        "notifications.active" to ArgSchema(),
        "notifications.dismiss" to ArgSchema(required = setOf("key"), stringMax = mapOf("key" to NotificationsSpec.KEY_MAX)),
        "notifications.reply" to ArgSchema(
            required = setOf("key", "actionIndex", "text"),
            stringMax = mapOf("key" to NotificationsSpec.KEY_MAX, "actionIndex" to 4, "text" to NotificationsSpec.REPLY_MAX),
        ),
        "notifications.open" to ArgSchema(required = setOf("key"), stringMax = mapOf("key" to NotificationsSpec.KEY_MAX)),
        // PARITY G9: numbers are short; setText at most A11ySpec.SET_TEXT_MAX; a path has at most 50 points.
        "a11y.windowTree" to ArgSchema(stringMax = mapOf("maxDepth" to 8, "maxNodes" to 8)),
        "a11y.perform" to ArgSchema(
            required = setOf("nodeId", "action"),
            stringMax = mapOf("nodeId" to A11ySpec.NODE_ID_MAX, "action" to 32, "text" to A11ySpec.SET_TEXT_MAX),
        ),
        "a11y.gesture" to ArgSchema(
            required = setOf("path"),
            stringMax = mapOf("durationMs" to 8),
            arrayMax = mapOf("path" to A11ySpec.MAX_POINTS),
        ),
        "a11y.global" to ArgSchema(required = setOf("action"), stringMax = mapOf("action" to 32)),
        // PARITY D4: artworkUrl may be a data: image (MediaSessionSpec.ARTWORK_URL_MAX); eight action names.
        "media.setMetadata" to ArgSchema(
            required = setOf("title"),
            stringMax = mapOf(
                "title" to SHORT, "artist" to SHORT, "album" to SHORT,
                "artworkUrl" to MediaSessionSpec.ARTWORK_URL_MAX, "durationMs" to 16,
            ),
        ),
        "media.setState" to ArgSchema(required = setOf("state"), stringMax = mapOf("state" to 16, "positionMs" to 16, "rate" to 16)),
        "media.setActions" to ArgSchema(required = setOf("actions"), arrayMax = mapOf("actions" to 8)),
        "media.release" to ArgSchema(),
    )

    fun validate(method: String, args: JsonObject): String? {
        val spec = schemas[method] ?: ArgSchema()
        for (key in spec.required) {
            if (args[key] == null) return "missing $key"
        }
        for ((key, value) in args) {
            val max = spec.stringMax[key] ?: if (value is JsonPrimitive) DEFAULT_STRING else null
            if (max != null && value is JsonPrimitive) {
                val text = value.contentOrNull ?: return "$key must be a string"
                if (text.length > max) return "$key exceeds $max characters"
            }
            val arrMax = spec.arrayMax[key]
            if (arrMax != null) {
                val arr = value as? JsonArray ?: return "$key must be an array"
                if (arr.size > arrMax) return "$key has too many items"
            }
        }
        return null
    }

    /** Irreversible methods: confirmed every time, even right after a tap. */
    val ALWAYS_CONFIRM: Set<String> = setOf("nfc.makeReadOnly", "keystore.delete", "telephony.ussd", "wifi.suggest")

    fun confirmSummary(method: String, args: JsonObject): String {
        fun s(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (method) {
            "sms.send" -> "Send SMS to ${s("to")}:\n${s("body")}"
            "sms.sendMms" -> "Send MMS to ${s("to")}\n${s("body")}"
            "telephony.call" -> "Place a call to ${s("e164")}"
            "telephony.ussd" -> "Send code ${s("code")} to your carrier"
            "nfc.makeReadOnly" -> "Lock this NFC tag read-only. This cannot be undone."
            "keystore.delete" -> "Delete the key ${s("alias")}. Anything locked with it can no longer be opened. This cannot be undone."
            "wifi.suggest" -> "Suggest the Wi-Fi network ${s("ssid")} for this phone to join" +
                if (args["passphrase"] != null) " (with a password)." else " (open network, no password)."
            else -> method
        }
    }
}
