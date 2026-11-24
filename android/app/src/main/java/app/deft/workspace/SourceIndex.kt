package app.deft.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

/** Checks a local signed source index. Nothing is fetched from the network. */
object SourceIndex {
    private val json = Json { ignoreUnknownKeys = true }

    data class Checked(val serial: Long, val packageNames: List<String>)

    fun verify(text: String, previousSerial: Long? = null, now: Instant = Instant.now()): Checked {
        val root = json.parseToJsonElement(text).jsonObject
        val body = root["body"]?.jsonObject ?: throw IllegalArgumentException("missing body")
        val publicKey = root["publicKey"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("missing publicKey")
        val signature = root["signature"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("missing signature")
        if (!verifySig(DeftJcs.of(body), signature, publicKey)) {
            throw IllegalArgumentException("bad signature")
        }
        val serial = body["serial"]?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("missing serial")
        if (previousSerial != null && serial < previousSerial) {
            throw IllegalArgumentException("index rollback")
        }
        val expires = body["expires"]?.jsonPrimitive?.content
        if (expires != null && Instant.parse(expires).isBefore(now)) {
            throw IllegalArgumentException("index expired")
        }
        return Checked(serial, packageNames(body))
    }

    private fun packageNames(body: JsonObject): List<String> {
        val arr = body["packages"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            (obj["name"] as? JsonPrimitive)?.contentOrNull
        }
    }

    private fun verifySig(payload: String, signatureB64: String, publicB64: String): Boolean =
        runCatching {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(publicB64)),
            )
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(key)
            sig.update(payload.toByteArray())
            sig.verify(Base64.getDecoder().decode(signatureB64))
        }.getOrDefault(false)
}
