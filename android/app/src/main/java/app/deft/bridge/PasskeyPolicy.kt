package app.deft.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * The rules of the passkeys bridge that need no Android. Every Credential Manager call from the
 * launcher carries the same origin (`android:apk-key-hash:<launcher cert>`), so the passkey provider cannot tell Deft
 * apps apart. The host does it: a call goes on only when its rpId is in the app's manifest `passkeys.rpIds` **and**
 * the website lists the app's origin id in its own opt-in file ([OPT_IN_PATH]).
 */
object PasskeyPolicy {
    const val MAX_RP_IDS = 4
    const val MAX_RP_ID_LENGTH = 253
    const val MAX_REQUEST_BYTES = 16_384
    const val MAX_OPT_IN_BYTES = 65_536
    const val MAX_OPT_IN_ORIGINS = 64
    const val OPT_IN_PATH = "/.well-known/deft.json"
    const val OPT_IN_CACHE_MS = 24L * 60 * 60 * 1000
    /** How long a failed read, or a list that does not name the app, is remembered (1 minute). */
    const val OPT_IN_FAILURE_CACHE_MS = 60_000L
    const val OPT_IN_TIMEOUT_MS = 10_000

    /** The message for every refusal that comes from the website's side or the manifest. */
    const val NOT_ALLOWED = "This website has not allowed this app to use passkeys"

    private const val APP_SUFFIX = ".app.deft.internal"
    private val json = Json { ignoreUnknownKeys = true }
    private val label = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val originIdShape = Regex("^[a-z0-9]{16,20}$")
    private val blockedSuffixes = listOf("internal", "local", "localhost", "test")

    enum class Kind { CREATE, GET }

    /**
     * A relying party id the manifest may name: a lowercase DNS name with at least two labels, at most
     * [MAX_RP_ID_LENGTH] characters, no IP address, no `localhost`, nothing under `.internal`, `.local`,
     * `.localhost` or `.test` (that covers the app origins under `.deft.internal`). Kept identical to deft-lint
     * FL719 (`validRpId` in tools/deft-lint/src/index.js).
     */
    fun validRpId(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_RP_ID_LENGTH) return false
        if (ipv4.matches(value)) return false
        val labels = value.split('.')
        if (labels.size < 2 || labels.any { !label.matches(it) }) return false
        if (labels.last().all { it.isDigit() }) return false
        if (labels.last() in blockedSuffixes) return false
        return true
    }

    /** Manifest `passkeys: {rpIds: [...]}`; null when absent. Throws with a plain message when the shape is wrong. */
    fun parseManifest(element: JsonElement?): List<String>? {
        if (element == null || element is JsonNull) return null
        val obj = element as? JsonObject ?: throw IllegalArgumentException("passkeys must be {rpIds}")
        val list = obj["rpIds"] as? JsonArray ?: throw IllegalArgumentException("passkeys.rpIds must be an array")
        if (list.isEmpty()) throw IllegalArgumentException("passkeys.rpIds must name at least one website")
        if (list.size > MAX_RP_IDS) throw IllegalArgumentException("at most $MAX_RP_IDS passkeys.rpIds")
        return list.map { el ->
            val value = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("passkeys.rpIds must be an array of strings")
            if (!validRpId(value)) throw IllegalArgumentException("invalid passkeys.rpIds entry '$value' (a lowercase domain like example.com)")
            value
        }.distinct()
    }

    sealed interface Checked {
        /** [json] is the request as the host read it: the only text handed on to the passkey provider. */
        data class Ok(val rpId: String, val json: String) : Checked
        data class Bad(val message: String) : Checked
    }

    /**
     * Reads the rpId from a WebAuthn options JSON (`PublicKeyCredentialCreationOptionsJSON` with `rp.id`, or
     * `PublicKeyCredentialRequestOptionsJSON` with `rpId`). The rpId only ever comes from here: never from the page
     * URL, never defaulted. Oversize, malformed or `mediation: "conditional"` requests are refused, and so are the
     * FIDO U2F `appid` / `appidExclude` extensions (they name a second relying party). The request goes on to the
     * provider re-serialized from what was parsed here, so a key named twice cannot read one way here and another
     * way in the provider.
     */
    fun checkRequest(kind: Kind, requestJson: String): Checked {
        if (requestJson.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            return Checked.Bad("requestJson is over ${MAX_REQUEST_BYTES / 1024} KB")
        }
        val root = runCatching { json.parseToJsonElement(requestJson) }.getOrNull() as? JsonObject
            ?: return Checked.Bad("requestJson must be a JSON object")
        if (root["mediation"] != null) return Checked.Bad("mediation is not supported (conditional sign-in is not available)")
        if (root.string("challenge").isNullOrEmpty()) return Checked.Bad("requestJson needs a challenge")
        val extensions = root["extensions"]
        if (extensions != null && extensions !is JsonObject) return Checked.Bad("extensions must be an object")
        if (extensions is JsonObject && ("appid" in extensions || "appidExclude" in extensions)) {
            return Checked.Bad("the appid and appidExclude extensions are not supported")
        }
        val rpId = when (kind) {
            Kind.CREATE -> {
                val rp = root["rp"] as? JsonObject ?: return Checked.Bad("requestJson needs rp {id, name}")
                val user = root["user"] as? JsonObject ?: return Checked.Bad("requestJson needs user {id, name, displayName}")
                if (user.string("id").isNullOrEmpty() || user.string("name").isNullOrEmpty()) {
                    return Checked.Bad("requestJson needs user.id and user.name")
                }
                rp.string("id") ?: return Checked.Bad("requestJson needs rp.id")
            }
            Kind.GET -> root.string("rpId") ?: return Checked.Bad("requestJson needs rpId")
        }
        if (!validRpId(rpId)) return Checked.Bad("rpId must be a lowercase domain like example.com")
        return Checked.Ok(rpId, root.toString())
    }

    /** The origin id of an app origin `https://<id>.app.deft.internal`, or null for any other origin. */
    fun originIdOf(origin: String): String? {
        val host = origin.removePrefix("https://").takeIf { it != origin }?.substringBefore('/')?.lowercase() ?: return null
        if (!host.endsWith(APP_SUFFIX)) return null
        val id = host.removeSuffix(APP_SUFFIX)
        return id.takeIf { originIdShape.matches(it) }
    }

    /**
     * The website's opt-in file: `{"passkeys": {"origins": ["<originId>", ...]}}` (at most [MAX_OPT_IN_ORIGINS]).
     * Null when it is not that shape; ids that are not origin-id shaped are dropped.
     */
    fun parseOptIn(body: String): Set<String>? {
        if (body.toByteArray(Charsets.UTF_8).size > MAX_OPT_IN_BYTES) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val passkeys = root["passkeys"] as? JsonObject ?: return null
        val origins = passkeys["origins"] as? JsonArray ?: return null
        if (origins.size > MAX_OPT_IN_ORIGINS) return null
        return origins.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .filter { originIdShape.matches(it) }
            .toSet()
    }

    /**
     * Why an opt-in response is not used, or null when it is: anything but a direct 200 with a JSON content type
     * and a body within [MAX_OPT_IN_BYTES]. Redirects are never followed.
     */
    fun responseProblem(status: Int, contentType: String?, contentLength: Long): String? = when {
        status in 300..399 -> "the website redirected"
        status != 200 -> "the website answered $status"
        contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json" -> "the website did not send JSON"
        contentLength > MAX_OPT_IN_BYTES -> "the website's list is too large"
        else -> null
    }

    /** The opt-in URL for [rpId]; only ever https, only ever the fixed path. */
    fun optInUrl(rpId: String): String = "https://$rpId$OPT_IN_PATH"

    /**
     * Whether [webAppOriginId] may use passkeys for [rpId]: the manifest names the rpId and the website's list
     * ([optIn], null when it could not be read) names the app's origin id.
     */
    fun allowed(rpId: String, manifestRpIds: List<String>, webAppOriginId: String?, optIn: Set<String>?): Boolean =
        webAppOriginId != null && rpId in manifestRpIds && optIn != null && webAppOriginId in optIn

    /** `android:apk-key-hash:<base64url SHA-256 of the signing cert>`: the origin the website's server must accept. */
    fun facetOrigin(certSha256: ByteArray): String =
        "android:apk-key-hash:" + Base64.getUrlEncoder().withoutPadding().encodeToString(certSha256)

    /** Audit metadata: the rpId only (never the challenge, credential id, user handle or response). */
    fun auditMetadata(rpId: String): String = buildString {
        append("{\"rpId\":")
        append(JsonPrimitive(rpId).toString())
        append('}')
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
