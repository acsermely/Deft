package app.deft.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import app.deft.bridge.AccountsSpec
import app.deft.catalog.Capability
import app.deft.catalog.isGranted
import app.deft.data.AppRepository
import app.deft.data.InstalledWebApp

// DEVICE-UNCHECKED (accounts and sync): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: an app registers its own account and a sync job runs.
// Remove this note once checked.
/**
 * The launcher's own Android accounts, one type for all apps
 * (`<applicationId>.account`, so the debug build has its own). No passwords or tokens are
 * stored; an app keeps its own sign-in data in its storage. See [AccountsSpec] for the rules.
 */
object DeftAccounts {
    fun accountType(context: Context): String = context.packageName + ".account"

    fun syncAuthority(context: Context): String = context.packageName + ".sync"

    /** The key an app's accounts are filed under: its origin id, as for keystore keys. */
    fun owner(app: InstalledWebApp): String = app.originId ?: app.id

    private fun manager(context: Context): AccountManager = AccountManager.get(context)

    private fun all(context: Context): List<Pair<Account, AccountsSpec.Stored>> {
        val am = manager(context)
        return am.getAccountsByType(accountType(context)).map { acc ->
            acc to AccountsSpec.Stored(
                androidName = acc.name,
                origin = am.getUserData(acc, AccountsSpec.KEY_ORIGIN),
                name = am.getUserData(acc, AccountsSpec.KEY_NAME),
            )
        }
    }

    private fun accountOf(context: Context, origin: String, name: String): Account? {
        val all = all(context)
        val stored = AccountsSpec.find(all.map { it.second }, origin, name) ?: return null
        return all.firstOrNull { it.second === stored }?.first
    }

    sealed class AddResult {
        data object Added : AddResult()
        data object Exists : AddResult()
        data object TooMany : AddResult()
        data object Taken : AddResult()
        data object Failed : AddResult()
    }

    @Synchronized
    fun add(context: Context, app: InstalledWebApp, name: String): AddResult {
        val origin = owner(app)
        val androidName = AccountsSpec.displayName(name, app.name)
        when (AccountsSpec.checkAdd(all(context).map { it.second }, origin, name, androidName)) {
            AccountsSpec.AddCheck.Exists -> return AddResult.Exists
            AccountsSpec.AddCheck.TooMany -> return AddResult.TooMany
            AccountsSpec.AddCheck.Taken -> return AddResult.Taken
            AccountsSpec.AddCheck.Ok -> Unit
        }
        val account = Account(androidName, accountType(context))
        val data = Bundle().apply {
            putString(AccountsSpec.KEY_ORIGIN, origin)
            putString(AccountsSpec.KEY_NAME, name)
        }
        if (!manager(context).addAccountExplicitly(account, null, data)) return AddResult.Failed
        // Syncs run only when the app asks (requestSync) or sets a period (setPeriodicSync).
        ContentResolver.setIsSyncable(account, syncAuthority(context), 1)
        ContentResolver.setSyncAutomatically(account, syncAuthority(context), false)
        return AddResult.Added
    }

    /** `[{name, periodicSeconds}]` of the app's accounts; 0 when no periodic sync is set. */
    fun list(context: Context, app: InstalledWebApp): List<Pair<String, Long>> {
        val origin = owner(app)
        val authority = syncAuthority(context)
        return all(context)
            .filter { it.second.origin == origin && it.second.name != null }
            .map { (acc, stored) ->
                val period = runCatching { ContentResolver.getPeriodicSyncs(acc, authority).firstOrNull()?.period }
                    .getOrNull() ?: 0L
                stored.name!! to period
            }
            .sortedBy { it.first }
    }

    @Synchronized
    fun remove(context: Context, app: InstalledWebApp, name: String): Boolean {
        val acc = accountOf(context, owner(app), name) ?: return false
        return manager(context).removeAccountExplicitly(acc)
    }

    fun requestSync(context: Context, app: InstalledWebApp, name: String): Boolean {
        val acc = accountOf(context, owner(app), name) ?: return false
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(acc, syncAuthority(context), extras)
        return true
    }

    /** [seconds] 0 removes the periodic sync. Periodic syncs run only with automatic sync on. */
    fun setPeriodicSync(context: Context, app: InstalledWebApp, name: String, seconds: Long): Boolean {
        val acc = accountOf(context, owner(app), name) ?: return false
        val authority = syncAuthority(context)
        for (p in ContentResolver.getPeriodicSyncs(acc, authority)) {
            ContentResolver.removePeriodicSync(acc, authority, p.extras)
        }
        if (seconds > 0) {
            ContentResolver.setSyncAutomatically(acc, authority, true)
            ContentResolver.addPeriodicSync(acc, authority, Bundle.EMPTY, seconds)
        } else {
            ContentResolver.setSyncAutomatically(acc, authority, false)
        }
        return true
    }

    /** The owner origin id and app-chosen name of one of the launcher's accounts, or null. */
    fun describe(context: Context, account: Account): AccountsSpec.Stored? {
        if (account.type != accountType(context)) return null
        return all(context).firstOrNull { it.first == account }?.second
    }

    /** Removes every account of the app with origin id [origin] (uninstall, `accounts.own` revoked). */
    @Synchronized
    fun forgetOwner(context: Context, origin: String) {
        val am = manager(context)
        for ((acc, stored) in all(context)) {
            if (stored.origin == origin) runCatching { am.removeAccountExplicitly(acc) }
        }
    }

    /** Called whenever a grant of the app goes: without `accounts.own` its accounts go too. */
    suspend fun recheckGrants(context: Context, repository: AppRepository, webAppId: String) {
        val app = repository.getApp(webAppId) ?: return
        if (!repository.grantLevel(webAppId, Capability.ACCOUNTS_OWN).isGranted()) forgetOwner(context, owner(app))
    }

    /** `accounts.listAll`: every account visible to the launcher, without the launcher's own. */
    fun listAll(context: Context): List<Pair<String, String>> {
        val own = accountType(context)
        return manager(context).accounts
            .filter { it.type != own }
            .map { it.name to it.type }
    }
}
