package app.deft.ui.confirm

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.deft.bridge.BridgeConfirms
import app.deft.bridge.ConfirmChoice
import app.deft.ui.OverlayGuard
import app.deft.ui.host.WebViewHostActivity
import app.deft.ui.theme.AppDeftTheme

class ConfirmActivity : FragmentActivity() {
    private var requestId: String = ""
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayGuard.protect(this)
        enableEdgeToEdge()
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
        // The window title names the requesting app, never just "Deft".
        intent.getStringExtra(EXTRA_APP_NAME)?.takeIf { it.isNotBlank() }?.let { setTitle(it) }
        // PARITY G0: special access must be confirmed with the screen lock; no plain Allow button.
        val requireAuth = intent.getBooleanExtra(EXTRA_REQUIRE_AUTH, false)
        val useBiometric = requireAuth || intent.getBooleanExtra(EXTRA_USE_BIOMETRIC, false)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canBio = useBiometric &&
            BiometricManager.from(this).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
        if (canBio) {
            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        finishWith(ConfirmChoice.ALLOW)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        finishWith(ConfirmChoice.DENY)
                    }
                },
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title.ifBlank { getString(android.R.string.ok) })
                    .setDescription(message)
                    .setAllowedAuthenticators(authenticators)
                    .build(),
            )
            return
        }
        if (requireAuth) {
            setContent {
                AppDeftTheme {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Set a screen lock (PIN, pattern, password or fingerprint) on this phone first. " +
                                    "Deft asks for it before giving an app this permission.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Button(onClick = { finishWith(ConfirmChoice.DENY) }) { Text("Close") }
                            }
                        }
                    }
                }
            }
            return
        }
        setContent {
            AppDeftTheme {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { finishWith(ConfirmChoice.DENY) }) { Text("Deny") }
                            Button(onClick = { finishWith(ConfirmChoice.ALLOW) }) { Text("Allow") }
                        }
                    }
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWith(ConfirmChoice.DENY)
    }

    override fun onDestroy() {
        if (!completed && requestId.isNotBlank()) {
            BridgeConfirms.complete(requestId, ConfirmChoice.DENY)
        }
        super.onDestroy()
    }

    private fun finishWith(choice: ConfirmChoice) {
        if (completed) return
        completed = true
        BridgeConfirms.complete(requestId, choice)
        val webAppId = intent.getStringExtra(EXTRA_WEB_APP_ID)
        if (!webAppId.isNullOrBlank()) {
            startActivity(
                WebViewHostActivity.intent(this, webAppId)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_USE_BIOMETRIC = "useBiometric"
        const val EXTRA_WEB_APP_ID = "webAppId"
        const val EXTRA_APP_NAME = "appName"
        const val EXTRA_REQUIRE_AUTH = "requireAuth"
    }
}
