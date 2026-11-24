package app.deft.registry

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WebAppManifest(
    val name: String? = null,
    val short_name: String? = null,
    val start_url: String? = null,
    val requiredCapabilities: List<String> = emptyList(),
)

@Serializable
data class ConsentPayload(
    val manifestUrl: String,
    val name: String,
    val startUrl: String,
    val origin: String,
    val capabilities: List<String>,
    val http: Boolean = false,
    val catalogHash: String? = null,
    val existingAppId: String? = null,
    val allCapabilities: List<String> = emptyList(),
    val bundledId: String? = null,
)

fun ConsentPayload.encode(): String = consentJson.encodeToString(this)

fun decodeConsentPayload(raw: String): ConsentPayload =
    consentJson.decodeFromString(raw)

private val consentJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
