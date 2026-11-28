package app.deft.bridge.modules

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import app.deft.DeftApp
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.HostSurfaces
import app.deft.bridge.PasskeyPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * `passkeys.status` / `passkeys.create` / `passkeys.get`: passkeys for an
 * app's own website through Android's Credential Manager. The website opts in twice: its `assetlinks.json` names the
 * launcher (the passkey provider checks that) and its [PasskeyPolicy.OPT_IN_PATH] names the app's origin id (the host
 * checks that, see [PasskeyPolicy]). WebAuthn inside the WebView stays off.
 */
class PasskeysModule(private val context: Context) : BridgeModule {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = when (request.method) {
        "passkeys.status" -> status(request)
        "passkeys.create" -> run(request, PasskeyPolicy.Kind.CREATE)
        "passkeys.get" -> run(request, PasskeyPolicy.Kind.GET)
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
    }

    private fun status(request: BridgeRequest): BridgeOutcome {
        HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        val origin = facetOrigin()
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Could not read the launcher's signing certificate")
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("supported", JsonPrimitive(supported()))
                put("origin", JsonPrimitive(origin))
            },
        )
    }

    private suspend fun run(request: BridgeRequest, kind: PasskeyPolicy.Kind): BridgeOutcome {
        val raw = (request.args["requestJson"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "requestJson must be a string (JSON.stringify the options)")
        val checked = when (val c = PasskeyPolicy.checkRequest(kind, raw)) {
            is PasskeyPolicy.Checked.Bad -> return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, c.message)
            is PasskeyPolicy.Checked.Ok -> c
        }
        val rpId = checked.rpId
        // Only the text the host checked goes on, never the page's own string.
        val requestJson = checked.json
        if (!supported()) return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Passkeys need Android 14 or newer")
        val surface = HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        if (surface.isPreview) return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_IN_PREVIEW)

        val repo = DeftApp.instance.repository
        val app = repo.getApp(request.webAppId)
            ?: return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, PasskeyPolicy.NOT_ALLOWED)
        val manifestRpIds = withContext(Dispatchers.IO) { repo.passkeyRpIdsFor(app) }
        // The origin the host locked this bridge to, never anything the page sent.
        val originId = PasskeyPolicy.originIdOf(request.origin)
        if (originId == null || rpId !in manifestRpIds) {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, PasskeyPolicy.NOT_ALLOWED)
        }
        if (!inFlight.add(request.webAppId)) {
            return BridgeOutcome.Err(BridgeErrors.IN_USE, "A passkey request is already open")
        }
        try {
            val optIn = when (val read = PasskeyOptIn.allows(rpId, originId)) {
                is PasskeyOptIn.Result.Read -> read.origins
                is PasskeyOptIn.Result.Failed ->
                    return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "${PasskeyPolicy.NOT_ALLOWED} (${read.reason})")
            }
            if (!PasskeyPolicy.allowed(rpId, manifestRpIds, originId, optIn)) {
                return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, PasskeyPolicy.NOT_ALLOWED)
            }
            val response = withContext(Dispatchers.Main) {
                val activity = surface.passkeyActivity()
                    ?: return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
                val manager = CredentialManager.create(activity)
                when (kind) {
                    // No origin argument: the call carries the launcher's own origin, never one the page chose.
                    PasskeyPolicy.Kind.CREATE -> create(manager, activity, requestJson)
                    PasskeyPolicy.Kind.GET -> get(manager, activity, requestJson)
                }
            }
            return when (response) {
                is BridgeOutcome.Ok -> response.copy(metadata = PasskeyPolicy.auditMetadata(rpId))
                is BridgeOutcome.Err -> response
            }
        } finally {
            inFlight.remove(request.webAppId)
        }
    }

    private suspend fun create(manager: CredentialManager, activity: android.app.Activity, requestJson: String): BridgeOutcome = try {
        val built = try {
            CreatePublicKeyCredentialRequest(requestJson)
        } catch (e: IllegalArgumentException) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, invalid(e))
        }
        val result = manager.createCredential(activity, built)
        val json = (result as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
        if (json == null) {
            BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The passkey provider returned no passkey")
        } else {
            BridgeOutcome.Ok(buildJsonObject { put("responseJson", JsonPrimitive(json)) })
        }
    } catch (e: CreateCredentialException) {
        createError(e)
    }

    private suspend fun get(manager: CredentialManager, activity: android.app.Activity, requestJson: String): BridgeOutcome = try {
        val built = try {
            GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson)))
        } catch (e: IllegalArgumentException) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, invalid(e))
        }
        val result = manager.getCredential(activity, built)
        val json = (result.credential as? PublicKeyCredential)?.authenticationResponseJson
        if (json == null) {
            BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The passkey provider returned no passkey")
        } else {
            BridgeOutcome.Ok(buildJsonObject { put("responseJson", JsonPrimitive(json)) })
        }
    } catch (e: GetCredentialException) {
        getError(e)
    }

    private fun createError(e: CreateCredentialException): BridgeOutcome.Err = when (e) {
        is CreateCredentialCancellationException -> BridgeOutcome.Err(BridgeErrors.CANCELLED, "The passkey sheet was closed")
        is CreateCredentialInterruptedException -> BridgeOutcome.Err(BridgeErrors.CANCELLED, "The passkey sheet was interrupted; try again")
        is CreateCredentialNoCreateOptionException, is CreateCredentialProviderConfigurationException ->
            BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, NO_PROVIDER)
        is CreatePublicKeyCredentialDomException -> domError(e.domError.type, e.errorMessage)
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, failed(e.type, e.errorMessage))
    }

    private fun getError(e: GetCredentialException): BridgeOutcome.Err = when (e) {
        is GetCredentialCancellationException -> BridgeOutcome.Err(BridgeErrors.CANCELLED, "The passkey sheet was closed")
        is GetCredentialInterruptedException -> BridgeOutcome.Err(BridgeErrors.CANCELLED, "The passkey sheet was interrupted; try again")
        is NoCredentialException -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "No passkey for this website on this phone")
        is GetCredentialProviderConfigurationException -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, NO_PROVIDER)
        is GetPublicKeyCredentialDomException -> domError(e.domError.type, e.errorMessage)
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, failed(e.type, e.errorMessage))
    }

    /** A WebAuthn DOMException from the provider: NotAllowedError is the user saying no; the rest are failures. */
    private fun domError(type: String, message: CharSequence?): BridgeOutcome.Err =
        if (type.endsWith("NOT_ALLOWED_ERROR")) {
            BridgeOutcome.Err(BridgeErrors.CANCELLED, "The passkey request was refused or timed out")
        } else {
            BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, failed(type, message))
        }

    /** Type and provider message only, cut short: what a developer needs to see why the provider refused. */
    private fun failed(type: String, message: CharSequence?): String {
        val name = type.substringAfterLast('.').substringAfterLast("TYPE_")
        val detail = message?.toString()?.take(200)?.takeIf { it.isNotBlank() }
        return if (detail == null) "Passkey request failed ($name)" else "Passkey request failed ($name): $detail"
    }

    /** Android's own check of the options refused them (a field it needs is missing or the wrong shape). */
    private fun invalid(e: IllegalArgumentException): String =
        "requestJson was refused by Android" + (e.message?.take(200)?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")

    private fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private fun facetOrigin(): String? = cachedOrigin ?: runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching null
        PasskeyPolicy.facetOrigin(MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()))
    }.getOrNull()?.also { cachedOrigin = it }

    @Volatile
    private var cachedOrigin: String? = null

    private companion object {
        const val NO_PROVIDER = "No passkey provider is set up on this phone (Settings, Passwords and accounts)"
    }
}
