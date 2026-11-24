package app.deft.bridge.modules

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import app.deft.DeftApp
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.HostSurfaces
import app.deft.bridge.KeystoreAliases
import app.deft.bridge.KeystoreAuth
import app.deft.bridge.jsonArg
import app.deft.data.InstalledWebApp
import app.deft.ui.keystore.KeystoreAuthActivity
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val SIGN_ALGORITHM = "SHA256withECDSA"
private const val AES_GCM = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val AUTH_TIMEOUT_MS = 120_000L

/**
 * `keystore.*` (PARITY F2): keys made in the Android Keystore and kept to one app. The key
 * material never reaches the app; it gets public keys, signatures and ciphertext back. Only
 * while the app is on screen. A key locked by a fingerprint or face asks for one.
 */
class KeystoreModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        val app = DeftApp.instance.repository.getApp(request.webAppId)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The app is not installed")
        return try {
            withContext(Dispatchers.IO) { dispatch(request, app) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "This key was removed when your fingerprints or face changed. Delete it and make a new one.")
        } catch (_: AEADBadTagException) {
            BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "The data does not match this key")
        } catch (_: GeneralSecurityException) {
            BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The key could not be used on this phone")
        } catch (_: ProviderException) {
            BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The key could not be used on this phone")
        }
    }

    private suspend fun dispatch(request: BridgeRequest, app: InstalledWebApp): BridgeOutcome {
        // Keys belong to the app's origin id; the webAppId comes from the bridge, never from args.
        val owner = app.originId ?: app.id
        val args = request.args
        return when (request.method) {
            "keystore.generate" -> generate(args, owner, app)
            "keystore.sign" -> sign(args, owner, app)
            "keystore.encrypt" -> encrypt(args, owner, app)
            "keystore.decrypt" -> decrypt(args, owner, app)
            "keystore.list" -> list(owner)
            "keystore.delete" -> delete(args, owner)
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private fun generate(args: JsonObject, owner: String, app: InstalledWebApp): BridgeOutcome {
        val alias = KeystoreAliases.localAlias(jsonArg(args, "alias")) ?: return badAlias()
        val alg = jsonArg(args, "alg")
        if (alg !in KeystoreAliases.ALGORITHMS) return invalid("alg must be EC_P256 or AES_256")
        val biometric = jsonArg(args, "requireBiometric") == "true"
        val validity = KeystoreAliases.validity(jsonArg(args, "validitySeconds"))
            ?: return invalid("validitySeconds must be 0 to ${KeystoreAliases.MAX_VALIDITY_SECONDS}")
        if (validity > 0 && !biometric) return invalid("validitySeconds needs requireBiometric")
        val name = KeystoreAliases.keystoreAlias(owner, alias)
        if (keyStore().containsAlias(name)) return BridgeOutcome.Err(BridgeErrors.IN_USE, "A key named $alias already exists")
        if (biometric && BiometricManager.from(context).canAuthenticate(STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Add a fingerprint or face to this phone first")
        }
        val publicKey: String? = if (alg == "EC_P256") {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
            generator.initialize(lockable(spec, biometric, validity).build())
            Base64.encodeToString(generator.generateKeyPair().public.encoded, Base64.NO_WRAP)
        } else {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            generator.init(lockable(spec, biometric, validity).build())
            generator.generateKey()
            null
        }
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("alias", JsonPrimitive(alias))
                if (publicKey != null) put("publicKeySpki", JsonPrimitive(publicKey))
            },
        )
    }

    private suspend fun sign(args: JsonObject, owner: String, app: InstalledWebApp): BridgeOutcome {
        val alias = KeystoreAliases.localAlias(jsonArg(args, "alias")) ?: return badAlias()
        val name = KeystoreAliases.keystoreAlias(owner, alias)
        val data = bytesArg(args, "base64") ?: return invalid("base64 is not valid")
        val key = (keyStore().getEntry(name, null) as? KeyStore.PrivateKeyEntry)?.privateKey
            ?: return noKey(alias)
        val signed = useKey(
            app,
            lock = keyLock(key),
            make = { BiometricPrompt.CryptoObject(Signature.getInstance(SIGN_ALGORITHM).apply { initSign(key) }) },
            run = { crypto ->
                val signature = checkNotNull(crypto.signature)
                signature.update(data)
                signature.sign()
            },
        ) ?: return notPassed()
        return BridgeOutcome.Ok(buildJsonObject { put("signature", JsonPrimitive(encode(signed))) })
    }

    private suspend fun encrypt(args: JsonObject, owner: String, app: InstalledWebApp): BridgeOutcome {
        val alias = KeystoreAliases.localAlias(jsonArg(args, "alias")) ?: return badAlias()
        val name = KeystoreAliases.keystoreAlias(owner, alias)
        val data = bytesArg(args, "base64") ?: return invalid("base64 is not valid")
        val key = keyStore().getKey(name, null) as? SecretKey ?: return noKey(alias)
        // The cipher picks a fresh IV; it comes back next to the ciphertext.
        val sealed = useKey(
            app,
            lock = keyLock(key),
            make = { BiometricPrompt.CryptoObject(Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key) }) },
            run = { crypto ->
                val cipher = checkNotNull(crypto.cipher)
                Pair(cipher.iv, cipher.doFinal(data))
            },
        ) ?: return notPassed()
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("iv", JsonPrimitive(encode(sealed.first)))
                put("ciphertext", JsonPrimitive(encode(sealed.second)))
            },
        )
    }

    private suspend fun decrypt(args: JsonObject, owner: String, app: InstalledWebApp): BridgeOutcome {
        val alias = KeystoreAliases.localAlias(jsonArg(args, "alias")) ?: return badAlias()
        val name = KeystoreAliases.keystoreAlias(owner, alias)
        val iv = bytesArg(args, "iv") ?: return invalid("iv is not valid")
        val ciphertext = bytesArg(args, "ciphertext") ?: return invalid("ciphertext is not valid")
        val key = keyStore().getKey(name, null) as? SecretKey ?: return noKey(alias)
        val plain = useKey(
            app,
            lock = keyLock(key),
            make = {
                BiometricPrompt.CryptoObject(
                    Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv)) },
                )
            },
            run = { crypto -> checkNotNull(crypto.cipher).doFinal(ciphertext) },
        ) ?: return notPassed()
        return BridgeOutcome.Ok(buildJsonObject { put("base64", JsonPrimitive(encode(plain))) })
    }

    private fun list(owner: String): BridgeOutcome {
        val names = Collections.list(keyStore().aliases())
            .mapNotNull { KeystoreAliases.localName(owner, it) }
            .sorted()
        return BridgeOutcome.Ok(buildJsonArray { names.forEach { add(JsonPrimitive(it)) } })
    }

    private fun delete(args: JsonObject, owner: String): BridgeOutcome {
        val alias = KeystoreAliases.localAlias(jsonArg(args, "alias")) ?: return badAlias()
        val name = KeystoreAliases.keystoreAlias(owner, alias)
        val store = keyStore()
        if (!store.containsAlias(name)) return noKey(alias)
        store.deleteEntry(name)
        return BridgeOutcome.Ok()
    }

    /**
     * Runs one key operation. A key asked on every use gets the prompt first, which authorises
     * the fresh cipher or signature the operation then runs with. A timed key is tried first,
     * which works while its unlock is still open; when it is locked, the prompt unlocks it.
     * Null when the user did not pass the prompt.
     */
    private suspend fun <T> useKey(
        app: InstalledWebApp,
        lock: KeystoreAliases.Lock,
        make: () -> BiometricPrompt.CryptoObject,
        run: (BiometricPrompt.CryptoObject) -> T,
    ): T? {
        when (lock) {
            KeystoreAliases.Lock.NONE -> return run(make())
            KeystoreAliases.Lock.PER_USE -> {
                val crypto = make()
                if (!askBiometric(app, crypto)) return null
                return run(crypto)
            }
            KeystoreAliases.Lock.TIMED -> Unit
        }
        try {
            return run(make())
        } catch (_: UserNotAuthenticatedException) {
            // Locked: ask below.
        }
        // A timed key cannot be given to the prompt before it is unlocked, so it gets none.
        val crypto = try {
            make()
        } catch (_: UserNotAuthenticatedException) {
            null
        }
        if (!askBiometric(app, crypto)) return null
        return run(crypto ?: make())
    }

    private suspend fun askBiometric(app: InstalledWebApp, crypto: BiometricPrompt.CryptoObject?): Boolean {
        val id = UUID.randomUUID().toString()
        val deferred = KeystoreAuth.register(id, crypto)
        context.startActivity(
            Intent(context, KeystoreAuthActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(KeystoreAuthActivity.EXTRA_REQUEST_ID, id)
                putExtra(KeystoreAuthActivity.EXTRA_APP_NAME, app.name)
            },
        )
        return withTimeoutOrNull(AUTH_TIMEOUT_MS) { deferred.await() } ?: run {
            KeystoreAuth.complete(id, false)
            false
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun keyLock(key: Key): KeystoreAliases.Lock {
        val info = if (key is SecretKey) {
            SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        } else {
            KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        }
        return KeystoreAliases.lock(info.isUserAuthenticationRequired, info.userAuthenticationValidityDurationSeconds)
    }

    private fun lockable(spec: KeyGenParameterSpec.Builder, biometric: Boolean, validity: Int): KeyGenParameterSpec.Builder {
        if (biometric) {
            // Validity 0 asks on every use; a longer one unlocks the key for that many seconds.
            spec.setUserAuthenticationRequired(true)
            spec.setUserAuthenticationParameters(validity, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        return spec
    }

    private fun bytesArg(args: JsonObject, key: String): ByteArray? {
        val text = jsonArg(args, key) ?: return null
        return runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrNull()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun badAlias() = invalid("alias must be 1 to ${KeystoreAliases.MAX_LENGTH} letters, digits, _ or -")

    private fun invalid(message: String) = BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, message)

    private fun noKey(alias: String) = BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "No key named $alias")

    private fun notPassed() = BridgeOutcome.Err(BridgeErrors.CANCELLED, "The fingerprint or face check was not passed")

    companion object {
        private val STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG

        /** Review: deletes every Keystore key of [owner] (the app's origin id), on uninstall. */
        fun forgetOwner(owner: String) {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            for (name in KeystoreAliases.ownedBy(owner, Collections.list(store.aliases()))) {
                runCatching { store.deleteEntry(name) }
            }
        }
    }
}
