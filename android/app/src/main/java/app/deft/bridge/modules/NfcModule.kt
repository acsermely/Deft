package app.deft.bridge.modules

import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.nfc.tech.TagTechnology
import android.util.Base64
import app.deft.bridge.BridgeAccessoryPicks
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.BridgeService
import app.deft.bridge.HostSurfaces
import app.deft.bridge.jsonArg
import app.deft.ui.nfc.NfcReadActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// DEVICE-UNCHECKED (NFC write, read-only and tech transceive): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: write a URL tag; read a transit card's ISO-DEP response.
// Remove this note once checked.
/**
 * NFC. `nfc.read` is the original one-shot read. `nfc.scanStart` reads tags while the
 * app is on screen and emits `nfc.tag`; the tag id in that event is then used with
 * `nfc.writeNdef`, `nfc.makeReadOnly` and `nfc.transceive` while the tag stays in the field.
 */
class NfcModule(
    private val context: Context,
) : BridgeModule {
    private data class Session(val webAppId: String, val tag: Tag, var tech: TagTechnology? = null)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = when (request.method) {
        "nfc.read" -> readOnce()
        "nfc.scanStart" -> scanStart(request)
        "nfc.scanStop" -> scanStop(request.webAppId)
        "nfc.writeNdef" -> withSession(request) { s -> writeNdef(s, request.args) }
        "nfc.makeReadOnly" -> withSession(request) { s -> makeReadOnly(s) }
        "nfc.transceive" -> withSession(request) { s -> transceive(s, request.args) }
        "nfc.hceRegister" -> hceRegister(request)
        "nfc.hceUnregister" -> hceUnregister()
        "nfc.hceRespond" -> hceRespond(request)
        "nfc.paymentRegister" -> paymentRegister(request)
        "nfc.paymentUnregister" -> paymentUnregister()
        "nfc.paymentRespond" -> paymentRespond(request)
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
    }

    private suspend fun readOnce(): BridgeOutcome {
        val id = UUID.randomUUID().toString()
        val deferred = BridgeAccessoryPicks.registerNfc(id)
        context.startActivity(
            Intent(context, NfcReadActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(NfcReadActivity.EXTRA_REQUEST_ID, id)
            },
        )
        val result = deferred.await()
            ?: return BridgeOutcome.Err(BridgeErrors.CANCELLED, "NFC read cancelled or unavailable")
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("idHex", JsonPrimitive(result.idHex))
                if (result.ndefText != null) put("ndefText", JsonPrimitive(result.ndefText))
            },
        )
    }

    private suspend fun scanStart(request: BridgeRequest): BridgeOutcome {
        val webAppId = request.webAppId
        val surface = HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        val started = withContext(Dispatchers.Main) {
            surface.startNfcScan { tag -> scope.launch { onTag(webAppId, tag) } }
        }
        return if (started) BridgeOutcome.Ok() else BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "NFC is off or missing")
    }

    private suspend fun scanStop(webAppId: String): BridgeOutcome {
        HostSurfaces.forApp(webAppId)?.let { withContext(Dispatchers.Main) { it.stopNfcScan() } }
        dropSessions(webAppId)
        return BridgeOutcome.Ok()
    }

    private suspend fun onTag(webAppId: String, tag: Tag) {
        // One live tag per app: a new tap replaces the previous one.
        dropSessions(webAppId)
        val tagId = UUID.randomUUID().toString()
        sessions[tagId] = Session(webAppId, tag)
        val payload = buildJsonObject {
            put("tagId", JsonPrimitive(tagId))
            put("idHex", JsonPrimitive(tag.id.joinToString("") { "%02x".format(it) }))
            put("techs", buildJsonArray { tag.techList.forEach { add(JsonPrimitive(it.substringAfterLast('.'))) } })
            readNdef(tag)?.let { put("ndef", it) }
        }.toString()
        BridgeService.instanceOrNull()?.emit(webAppId, "nfc.tag", payload)
    }

    private fun dropSessions(webAppId: String) {
        for ((id, s) in sessions.entries.filter { it.value.webAppId == webAppId }) {
            runCatching { s.tech?.close() }
            sessions.remove(id)
        }
    }

    fun closeAll() {
        sessions.values.forEach { runCatching { it.tech?.close() } }
        sessions.clear()
    }

    private suspend fun withSession(request: BridgeRequest, block: suspend (Session) -> BridgeOutcome): BridgeOutcome {
        val tagId = jsonArg(request.args, "tagId")
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "tagId from an nfc.tag event required")
        val session = sessions[tagId]?.takeIf { it.webAppId == request.webAppId }
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "unknown tag; call nfc.scanStart and tap a tag")
        return withContext(Dispatchers.IO) {
            try {
                block(session)
            } catch (e: java.io.IOException) {
                runCatching { session.tech?.close() }
                session.tech = null
                BridgeOutcome.Err(BridgeErrors.CANCELLED, "Tag lost: ${e.message ?: "moved out of range"}")
            } catch (e: Exception) {
                BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, e.message ?: "NFC error")
            }
        }
    }

    /** Uses one technology connection per tag; switching technology reconnects. */
    private fun <T : TagTechnology> connect(s: Session, make: (Tag) -> T?, wanted: Class<T>): T {
        val current = s.tech
        if (current != null && wanted.isInstance(current) && current.isConnected) return wanted.cast(current)!!
        runCatching { current?.close() }
        val tech = make(s.tag) ?: throw IllegalArgumentException("this tag does not support ${wanted.simpleName}")
        tech.connect()
        s.tech = tech
        return tech
    }

    private fun readNdef(tag: Tag): JsonObject? {
        val ndef = Ndef.get(tag) ?: return null
        return runCatching {
            ndef.connect()
            try {
                buildJsonObject {
                    put("writable", JsonPrimitive(ndef.isWritable))
                    put("maxSize", JsonPrimitive(ndef.maxSize))
                    put("canMakeReadOnly", JsonPrimitive(ndef.canMakeReadOnly()))
                    put("records", recordsJson(ndef.ndefMessage))
                }
            } finally {
                runCatching { ndef.close() }
            }
        }.getOrNull()
    }

    private fun recordsJson(message: NdefMessage?): JsonArray = buildJsonArray {
        for (r in message?.records.orEmpty()) {
            add(
                buildJsonObject {
                    put("tnf", JsonPrimitive(r.tnf.toInt()))
                    put("type", JsonPrimitive(String(r.type, Charsets.US_ASCII)))
                    if (r.id.isNotEmpty()) put("id", JsonPrimitive(Base64.encodeToString(r.id, Base64.NO_WRAP)))
                    put("payloadBase64", JsonPrimitive(Base64.encodeToString(r.payload, Base64.NO_WRAP)))
                    when {
                        r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) ->
                            NdefPayloads.decodeText(r.payload)?.let { (lang, text) ->
                                put("recordType", JsonPrimitive("text"))
                                put("lang", JsonPrimitive(lang))
                                put("text", JsonPrimitive(text))
                            }
                        r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_URI) ->
                            NdefPayloads.decodeUri(r.payload)?.let {
                                put("recordType", JsonPrimitive("url"))
                                put("url", JsonPrimitive(it))
                            }
                        r.tnf == NdefRecord.TNF_MIME_MEDIA -> put("recordType", JsonPrimitive("mime"))
                        r.tnf == NdefRecord.TNF_EXTERNAL_TYPE -> put("recordType", JsonPrimitive("external"))
                        r.tnf == NdefRecord.TNF_EMPTY -> put("recordType", JsonPrimitive("empty"))
                    }
                },
            )
        }
    }

    /** `records: [{recordType: text|url|mime|external|empty, text?, lang?, url?, mediaType?, type?, payloadBase64?}]` */
    private fun buildMessage(args: JsonObject): NdefMessage {
        val list = (args["records"] as? JsonArray)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("records must be a non-empty array")
        if (list.size > 16) throw IllegalArgumentException("at most 16 records")
        val records = list.map { el ->
            val o = el as? JsonObject ?: throw IllegalArgumentException("each record must be an object")
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun payload() = str("payloadBase64")?.let { Base64.decode(it, Base64.DEFAULT) } ?: ByteArray(0)
            when (str("recordType")) {
                "text" -> NdefRecord(
                    NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, ByteArray(0),
                    NdefPayloads.encodeText(str("text").orEmpty(), str("lang") ?: "en"),
                )
                "url" -> NdefRecord(
                    NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_URI, ByteArray(0),
                    NdefPayloads.encodeUri(str("url") ?: throw IllegalArgumentException("url record needs url")),
                )
                "mime" -> NdefRecord.createMime(
                    str("mediaType") ?: throw IllegalArgumentException("mime record needs mediaType"), payload(),
                )
                "external" -> {
                    val type = str("type") ?: throw IllegalArgumentException("external record needs type (domain:name)")
                    NdefRecord.createExternal(type.substringBefore(':'), type.substringAfter(':', "x"), payload())
                }
                "empty" -> NdefRecord(NdefRecord.TNF_EMPTY, ByteArray(0), ByteArray(0), ByteArray(0))
                else -> throw IllegalArgumentException("recordType must be text, url, mime, external or empty")
            }
        }
        return NdefMessage(records.toTypedArray())
    }

    private fun writeNdef(s: Session, args: JsonObject): BridgeOutcome {
        val message = buildMessage(args)
        val ndef = Ndef.get(s.tag)
        if (ndef != null) {
            val tech = connect(s, { Ndef.get(it) }, Ndef::class.java)
            if (!tech.isWritable) return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "tag is read-only")
            if (message.byteArrayLength > tech.maxSize) {
                return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "message is ${message.byteArrayLength} bytes; tag holds ${tech.maxSize}")
            }
            tech.writeNdefMessage(message)
            return BridgeOutcome.Ok(buildJsonObject { put("bytes", JsonPrimitive(message.byteArrayLength)) })
        }
        val formatable = connect(s, { NdefFormatable.get(it) }, NdefFormatable::class.java)
        formatable.format(message)
        return BridgeOutcome.Ok(buildJsonObject { put("bytes", JsonPrimitive(message.byteArrayLength)); put("formatted", JsonPrimitive(true)) })
    }

    private fun makeReadOnly(s: Session): BridgeOutcome {
        val tech = connect(s, { Ndef.get(it) }, Ndef::class.java)
        if (!tech.canMakeReadOnly()) return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "this tag cannot be locked")
        return if (tech.makeReadOnly()) BridgeOutcome.Ok() else BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "lock failed")
    }

    /** Raw commands: `{tagId, tech: IsoDep|NfcA|NfcB|NfcF|NfcV|MifareUltralight|MifareClassic, base64, timeout?}`. */
    private fun transceive(s: Session, args: JsonObject): BridgeOutcome {
        val data = jsonArg(args, "base64")?.let { Base64.decode(it, Base64.DEFAULT) }
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "base64 command required")
        if (data.size > 4096) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "command larger than 4096 bytes")
        val timeout = jsonArg(args, "timeout")?.toIntOrNull()?.coerceIn(100, 5000)
        val response = when (jsonArg(args, "tech")) {
            "IsoDep" -> connect(s, { IsoDep.get(it) }, IsoDep::class.java).also { t -> timeout?.let { t.timeout = it } }.transceive(data)
            "NfcA" -> connect(s, { NfcA.get(it) }, NfcA::class.java).also { t -> timeout?.let { t.timeout = it } }.transceive(data)
            "NfcB" -> connect(s, { NfcB.get(it) }, NfcB::class.java).transceive(data)
            "NfcF" -> connect(s, { NfcF.get(it) }, NfcF::class.java).also { t -> timeout?.let { t.timeout = it } }.transceive(data)
            "NfcV" -> connect(s, { NfcV.get(it) }, NfcV::class.java).transceive(data)
            "MifareUltralight" -> connect(s, { MifareUltralight.get(it) }, MifareUltralight::class.java).also { t -> timeout?.let { t.timeout = it } }.transceive(data)
            "MifareClassic" -> connect(s, { MifareClassic.get(it) }, MifareClassic::class.java).also { t -> timeout?.let { t.timeout = it } }.transceive(data)
            else -> return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "tech must be IsoDep, NfcA, NfcB, NfcF, NfcV, MifareUltralight or MifareClassic")
        }
        return BridgeOutcome.Ok(buildJsonObject { put("base64", JsonPrimitive(Base64.encodeToString(response, Base64.NO_WRAP))) })
    }

    // ---- card emulation for the app holding nfc.hce (exclusive grant) ----

    private fun cardEmulation(): android.nfc.cardemulation.CardEmulation? {
        val adapter = android.nfc.NfcAdapter.getDefaultAdapter(context) ?: return null
        if (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) return null
        return android.nfc.cardemulation.CardEmulation.getInstance(adapter)
    }

    private fun hceComponent() = android.content.ComponentName(context, app.deft.nfc.DeftHostApduService::class.java)

    private fun hceRegister(request: BridgeRequest): BridgeOutcome {
        return registerAids(request) { aids ->
            cardEmulation()?.registerAidsForService(
                hceComponent(),
                android.nfc.cardemulation.CardEmulation.CATEGORY_OTHER,
                app.deft.nfc.HceRouting.withPlaceholder(aids, app.deft.nfc.HceRouting.PLACEHOLDER_AID),
            )
        }
    }

    /** `{aids: [hex]}` checked, then [register]ed (null: no card emulation); returns the normalized AIDs. */
    private fun registerAids(request: BridgeRequest, register: (List<String>) -> Boolean?): BridgeOutcome {
        val raw = (request.args["aids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "aids must be an array of hex strings")
        val aids = app.deft.nfc.HceRouting.validAids(raw)
            .getOrElse { return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, it.message ?: "invalid AIDs") }
        val ok = runCatching { register(aids) }.getOrDefault(false)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "This phone has no NFC card emulation")
        return if (ok) BridgeOutcome.Ok(buildJsonObject { put("aids", buildJsonArray { aids.forEach { add(JsonPrimitive(it)) } }) })
        else BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Android refused the AIDs")
    }

    private fun hceUnregister(): BridgeOutcome {
        cardEmulation()?.removeAidsForService(hceComponent(), android.nfc.cardemulation.CardEmulation.CATEGORY_OTHER)
        return BridgeOutcome.Ok()
    }

    private fun hceRespond(request: BridgeRequest): BridgeOutcome =
        respondApdu(request) { id, bytes -> app.deft.nfc.DeftHostApduService.respond(request.webAppId, id, bytes) }

    /** `{requestId, base64}` checked, then handed to [respond] (false: unknown, expired or not this app's). */
    private fun respondApdu(request: BridgeRequest, respond: (String, ByteArray) -> Boolean): BridgeOutcome {
        val requestId = jsonArg(request.args, "requestId") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "requestId required")
        val bytes = jsonArg(request.args, "base64")?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "base64 response required")
        if (bytes.size < app.deft.nfc.HceRouting.MIN_RESPONSE_BYTES) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "a response ends with a 2-byte status word")
        }
        return if (respond(requestId, bytes)) BridgeOutcome.Ok()
        else BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "unknown or expired requestId")
    }

    // ---- payment-category card emulation for the app holding nfc.hce.payment (special, exclusive) ----

    private fun paymentRegister(request: BridgeRequest): BridgeOutcome {
        val outcome = registerAids(request) { aids ->
            app.deft.nfc.DeftPaymentApduService.cardEmulation(context)?.registerAidsForService(
                app.deft.nfc.DeftPaymentApduService.component(context),
                android.nfc.cardemulation.CardEmulation.CATEGORY_PAYMENT,
                app.deft.nfc.HceRouting.withPlaceholder(aids, app.deft.nfc.HceRouting.PAYMENT_PLACEHOLDER_AID),
            )
        }
        if (outcome !is BridgeOutcome.Ok) return outcome
        // Android only routes payment AIDs to the default payment app; say whether that is us.
        val isDefault = app.deft.nfc.DeftPaymentApduService.isDefault(context)
        val result = buildJsonObject {
            (outcome.value as? JsonObject)?.forEach { (k, v) -> put(k, v) }
            put("isDefault", JsonPrimitive(isDefault))
        }
        return BridgeOutcome.Ok(result)
    }

    private fun paymentUnregister(): BridgeOutcome {
        app.deft.nfc.DeftPaymentApduService.clearAids(context)
        return BridgeOutcome.Ok()
    }

    private fun paymentRespond(request: BridgeRequest): BridgeOutcome =
        respondApdu(request) { id, bytes -> app.deft.nfc.DeftPaymentApduService.respond(request.webAppId, id, bytes) }
}
