package app.deft.account

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProvider
import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import app.deft.DeftApp
import app.deft.background.BackgroundTriggers
import app.deft.background.BackgroundWake
import app.deft.bridge.AccountsSpec
import app.deft.catalog.Capability
import app.deft.catalog.isGranted
import kotlinx.coroutines.runBlocking

// DEVICE-UNCHECKED (accounts and sync): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: an app registers its own account and a sync job runs.
// Remove this note once checked.
/**
 * One sync adapter for all apps' accounts (authority `<applicationId>.sync`). A sync
 * Android runs for an account wakes the owning app's background runner with trigger `sync`
 * `{account}`, if the app declares that trigger and holds `background` and `accounts.own`. The
 * sync counts as done once the run is handed over; the run itself follows the runner's rules.
 * An account whose app is gone, or lost `accounts.own`, is removed.
 */
class DeftSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true, false) {
    override fun onPerformSync(
        account: Account,
        extras: Bundle?,
        authority: String?,
        provider: ContentProviderClient?,
        syncResult: SyncResult,
    ) {
        val ctx = context.applicationContext
        if (authority != DeftAccounts.syncAuthority(ctx)) return
        val stored = DeftAccounts.describe(ctx, account) ?: return
        val origin = stored.origin ?: return
        val name = stored.name ?: return
        runBlocking {
            val repo = DeftApp.instance.repository
            val app = repo.allApps().firstOrNull { DeftAccounts.owner(it) == origin }
            if (app == null) {
                DeftAccounts.forgetOwner(ctx, origin)
                return@runBlocking
            }
            if (!repo.grantLevel(app.id, Capability.ACCOUNTS_OWN).isGranted()) {
                DeftAccounts.forgetOwner(ctx, origin)
                return@runBlocking
            }
            val woken = BackgroundWake.targets(repo, BackgroundTriggers.SYNC, only = app.id).isNotEmpty()
            repo.log(app.id, "sync", Capability.ACCOUNTS_OWN.id, "accounts.sync", if (woken) "ok" else "not_declared")
            if (woken) BackgroundWake.wake(ctx, BackgroundTriggers.SYNC, AccountsSpec.syncPayload(name), only = app.id)
        }
    }
}

/**
 * Binds [DeftSyncAdapter] for the system's SyncManager. The service must be exported
 * for SyncManager, so the binder answers only calls from the system (SyncManager runs in
 * system_server; the launcher's own `requestSync` goes through it too).
 */
class DeftSyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = SystemOnlyBinder(adapter(this).syncAdapterBinder)

    /** Forwards every transaction from [SYSTEM_UID][Process.SYSTEM_UID] to [delegate]; drops the rest. */
    private class SystemOnlyBinder(private val delegate: IBinder) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val uid = Binder.getCallingUid()
            if (!allowedCaller(uid)) {
                Log.w("DeftSyncService", "sync adapter call from uid $uid refused")
                return false
            }
            return delegate.transact(code, data, reply, flags)
        }
    }

    companion object {
        @Volatile private var instance: DeftSyncAdapter? = null

        /** Only the system's SyncManager may drive the sync adapter. */
        fun allowedCaller(uid: Int): Boolean = uid == Process.SYSTEM_UID

        private fun adapter(context: Context): DeftSyncAdapter =
            instance ?: synchronized(this) {
                instance ?: DeftSyncAdapter(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * A sync adapter needs a content authority; this provider holds no data. Not
 * exported, so only the launcher and the system see it.
 */
class DeftSyncStubProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
