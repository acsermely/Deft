package app.deft.ui.keystore

import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.deft.bridge.KeystoreAuth
import app.deft.ui.OverlayGuard

/**
 * PARITY F2: the fingerprint or face prompt for `keystore.*` calls on a biometric-locked key.
 * It has no screen of its own; the system prompt is the whole UI. The result goes back through
 * [KeystoreAuth].
 */
class KeystoreAuthActivity : FragmentActivity() {
    private var requestId: String = ""
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayGuard.protect(this)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty()
        // The window title names the requesting app, never just "Deft".
        if (appName.isNotBlank()) setTitle(appName)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            finishWith(false)
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    finishWith(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    finishWith(false)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock a key on this phone")
            .setDescription("$appName wants to use a key that is locked by your fingerprint or face.")
            .setAllowedAuthenticators(authenticators)
            .setNegativeButtonText(getString(android.R.string.cancel))
            .build()
        // After a recreating change the prompt is still up; the new BiometricPrompt reattaches to it.
        if (savedInstanceState != null) return
        val crypto = KeystoreAuth.cryptoFor(requestId)
        if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
    }

    override fun onDestroy() {
        // A recreating change keeps the request open; the prompt reattaches in the new instance.
        if (!completed && requestId.isNotBlank() && !isChangingConfigurations) KeystoreAuth.complete(requestId, false)
        super.onDestroy()
    }

    private fun finishWith(allowed: Boolean) {
        if (completed) return
        completed = true
        KeystoreAuth.complete(requestId, allowed)
        finish()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_APP_NAME = "appName"
    }
}
