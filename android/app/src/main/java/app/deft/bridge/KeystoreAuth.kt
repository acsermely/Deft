package app.deft.bridge

import androidx.biometric.BiometricPrompt
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * PARITY F2: a fingerprint or face check for one key use. The module registers the cipher or
 * signature it wants authorised; [ui.keystore.KeystoreAuthActivity] shows the prompt and
 * reports back here. A null crypto object means a timed key: the prompt only unlocks it.
 */
object KeystoreAuth {
    private class Pending(val crypto: BiometricPrompt.CryptoObject?, val result: CompletableDeferred<Boolean>)

    private val pending = ConcurrentHashMap<String, Pending>()

    fun register(id: String, crypto: BiometricPrompt.CryptoObject?): CompletableDeferred<Boolean> {
        val d = CompletableDeferred<Boolean>()
        pending[id] = Pending(crypto, d)
        return d
    }

    /** The crypto object to authorise for [id], or null when none is pending or it is a timed key. */
    fun cryptoFor(id: String): BiometricPrompt.CryptoObject? = pending[id]?.crypto

    fun complete(id: String, allowed: Boolean) {
        pending.remove(id)?.result?.complete(allowed)
    }
}
