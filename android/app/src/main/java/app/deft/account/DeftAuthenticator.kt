package app.deft.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast

/**
 * PARITY G10: the authenticator behind the launcher's account type. Accounts are added only by
 * apps through `accounts.add`; "Add account" in system Settings explains that and adds nothing.
 * There are no passwords or auth tokens: an app keeps its own sign-in data. Removing an account
 * in Settings is allowed. Android lets only the system call this binder (ACCOUNT_MANAGER).
 */
class DeftAuthenticator(private val context: Context) : AbstractAccountAuthenticator(context) {
    private fun unsupported(): Bundle = Bundle().apply {
        putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
        putString(AccountManager.KEY_ERROR_MESSAGE, ADD_FROM_APP)
    }

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, ADD_FROM_APP, Toast.LENGTH_LONG).show()
        }
        return unsupported()
    }

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle = unsupported()

    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle =
        unsupported()

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = unsupported()

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = unsupported()

    override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, features.isNullOrEmpty()) }

    companion object {
        const val ADD_FROM_APP = "Add accounts from the app itself"
    }
}

/** PARITY G10: binds [DeftAuthenticator] for the system's AccountManager. */
class DeftAuthenticatorService : Service() {
    private val authenticator by lazy { DeftAuthenticator(this) }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == AccountManager.ACTION_AUTHENTICATOR_INTENT) authenticator.iBinder else null
}
