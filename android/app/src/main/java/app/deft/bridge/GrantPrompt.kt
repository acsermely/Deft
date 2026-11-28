package app.deft.bridge

import android.content.Context
import android.content.Intent
import app.deft.data.InstalledWebApp
import app.deft.ui.confirm.ConfirmActivity
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Per-action confirmation for confirm-each capabilities. Grants themselves come
 * from the consent screen; this only confirms one specific send or call the user did not
 * just tap for. The dialog always names the app.
 */
object GrantPrompt {
    suspend fun ask(
        context: Context,
        app: InstalledWebApp,
        title: String,
        message: String,
        biometric: Boolean = false,
    ): ConfirmChoice {
        val id = UUID.randomUUID().toString()
        val deferred = BridgeConfirms.register(id, owner = app.id)
        val intent = Intent(context, ConfirmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ConfirmActivity.EXTRA_REQUEST_ID, id)
            .putExtra(ConfirmActivity.EXTRA_APP_NAME, app.name)
            .putExtra(ConfirmActivity.EXTRA_TITLE, title)
            .putExtra(ConfirmActivity.EXTRA_MESSAGE, message)
            .putExtra(ConfirmActivity.EXTRA_USE_BIOMETRIC, biometric)
            .putExtra(ConfirmActivity.EXTRA_WEB_APP_ID, app.id)
        context.startActivity(intent)
        return withTimeoutOrNull(120_000) { deferred.await() } ?: ConfirmChoice.DENY
    }

    /**
     * Screen-lock check (fingerprint, face or device PIN/pattern/password) before a
     * special-access grant. Without a screen lock the answer is always DENY. Does not reopen
     * the app afterwards.
     */
    suspend fun authenticate(
        context: Context,
        appName: String,
        title: String,
        message: String,
    ): ConfirmChoice {
        val id = UUID.randomUUID().toString()
        val deferred = BridgeConfirms.register(id)
        val intent = Intent(context, ConfirmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ConfirmActivity.EXTRA_REQUEST_ID, id)
            .putExtra(ConfirmActivity.EXTRA_APP_NAME, appName)
            .putExtra(ConfirmActivity.EXTRA_TITLE, title)
            .putExtra(ConfirmActivity.EXTRA_MESSAGE, message)
            .putExtra(ConfirmActivity.EXTRA_REQUIRE_AUTH, true)
        context.startActivity(intent)
        return withTimeoutOrNull(120_000) { deferred.await() } ?: ConfirmChoice.DENY
    }
}
