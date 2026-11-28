package app.deft.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OpenRouter's OAuth PKCE flow (openrouter.ai/docs/use-cases/oauth-pkce): the user signs in on openrouter.ai and
 * the launcher receives a key the user owns and can revoke. Pure helpers plus the two HTTP calls; the loopback
 * listener and the browser are in [OpenRouterSignIn].
 */
object OpenRouterAuth {
    const val API_BASE = "https://openrouter.ai/api/v1"
    const val AUTH_URL = "https://openrouter.ai/auth"
    const val KEYS_PAGE = "https://openrouter.ai/settings/keys"
    const val CALLBACK_PATH = "/callback"

    /** The label the new key gets in the user's OpenRouter key list. Neutral until the name is cleared. */
    const val KEY_LABEL = "Android launcher agent"

    private val json = Json { ignoreUnknownKeys = true }

    data class Pkce(val verifier: String, val challenge: String, val state: String)

    fun newPkce(random: SecureRandom = SecureRandom()): Pkce {
        val verifier = base64Url(ByteArray(32).also(random::nextBytes))
        val state = base64Url(ByteArray(16).also(random::nextBytes))
        return Pkce(verifier, challengeFor(verifier), state)
    }

    /** S256: base64url(sha256(verifier)) without padding (RFC 7636 4.2). */
    fun challengeFor(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun callbackUrl(port: Int): String = "http://localhost:$port$CALLBACK_PATH"

    fun authUrl(port: Int, pkce: Pkce): String = buildString {
        append(AUTH_URL)
        append("?callback_url=").append(enc(callbackUrl(port)))
        append("&code_challenge=").append(enc(pkce.challenge))
        append("&code_challenge_method=S256")
        append("&state=").append(enc(pkce.state))
        append("&key_label=").append(enc(KEY_LABEL))
        append("&expiration=never")
    }

    sealed interface Callback {
        data class Code(val code: String) : Callback
        data class Denied(val reason: String) : Callback
        /** Not the callback (a favicon request, a wrong state, junk): answer it and keep waiting. */
        data object Ignore : Callback
    }

    /** Reads the first line of an HTTP request, e.g. `GET /callback?code=...&state=... HTTP/1.1`. */
    fun parseCallback(requestLine: String, expectedState: String): Callback {
        val parts = requestLine.trim().split(' ')
        if (parts.size < 2 || parts[0] != "GET") return Callback.Ignore
        val target = parts[1]
        val path = target.substringBefore('?')
        if (path != CALLBACK_PATH) return Callback.Ignore
        val query = target.substringAfter('?', "")
        val params = query.split('&').filter { it.isNotEmpty() }.associate {
            dec(it.substringBefore('=')) to dec(it.substringAfter('=', ""))
        }
        val error = params["error"]
        if (error != null) return Callback.Denied(params["error_description"] ?: error)
        if (params["state"] != expectedState) return Callback.Ignore
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: return Callback.Ignore
        return Callback.Code(code)
    }

    /** POST /auth/keys: trades the one-time code for the user's key. */
    fun exchange(code: String, verifier: String): String {
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
            put("code_challenge_method", "S256")
        }.toString()
        val (status, text) = request("POST", "$API_BASE/auth/keys", body = body)
        if (status !in 200..299) throw HttpStatusException(status, "OpenRouter sign-in failed (HTTP $status)")
        return parseKey(text) ?: throw HttpStatusException(status, "OpenRouter sign-in returned no key")
    }

    fun parseKey(body: String): String? = runCatching {
        (json.parseToJsonElement(body) as JsonObject)["key"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** GET /key: true when the account never bought credits. Null when OpenRouter can't be read. */
    fun isFreeTier(apiKey: String): Boolean? = runCatching {
        val (status, text) = request("GET", "$API_BASE/key", apiKey = apiKey)
        if (status !in 200..299) null else parseFreeTier(text)
    }.getOrNull()

    fun parseFreeTier(body: String): Boolean? = runCatching {
        val data = (json.parseToJsonElement(body) as JsonObject)["data"] as JsonObject
        data["is_free_tier"]?.jsonPrimitive?.booleanOrNull
    }.getOrNull()

    private fun request(method: String, url: String, body: String? = null, apiKey: String? = null): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (apiKey != null) setRequestProperty("Authorization", "Bearer $apiKey")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            return status to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private fun base64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")
}
