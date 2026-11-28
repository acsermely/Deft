package app.deft.bridge.modules

import android.content.Context
import app.deft.DeftApp
import app.deft.account.DeftAccounts
import app.deft.bridge.AccountsSpec
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.HostSurfaces
import app.deft.data.InstalledWebApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// DEVICE-UNCHECKED (accounts and sync): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: an app registers its own account and a sync job runs.
// Remove this note once checked.
/**
 * `accounts.*`.
 *
 * - `accounts.own` (medium): the app's own accounts under the launcher's account type, shown in
 *   system Settings as "<name> · <App name>", with a sync adapter that wakes the app's background
 *   runner (trigger `sync`). An app only ever sees and changes its own accounts.
 * - `accounts.read` (special): `listAll`, the other accounts on the phone that Android lets the
 *   launcher see. Foreground only.
 */
class AccountsModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = withContext(Dispatchers.IO) {
        if (request.method == "accounts.listAll") return@withContext listAll(request)
        val app = DeftApp.instance.repository.getApp(request.webAppId)
            ?: return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Unknown app")
        when (request.method) {
            "accounts.add" -> add(request, app)
            "accounts.list" -> BridgeOutcome.Ok(
                buildJsonArray {
                    for ((name, period) in DeftAccounts.list(context, app)) {
                        add(buildJsonObject { put("name", name); put("periodicSeconds", period) })
                    }
                },
            )
            "accounts.remove" -> withName(request) { name ->
                if (DeftAccounts.remove(context, app, name)) BridgeOutcome.Ok() else noSuch()
            }
            "accounts.requestSync" -> withName(request) { name ->
                if (DeftAccounts.requestSync(context, app, name)) BridgeOutcome.Ok() else noSuch()
            }
            "accounts.setPeriodicSync" -> withName(request) { name ->
                when (val s = AccountsSpec.parsePeriodicSeconds(request.args)) {
                    is AccountsSpec.Parsed.Bad -> BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, s.message)
                    is AccountsSpec.Parsed.Ok ->
                        if (DeftAccounts.setPeriodicSync(context, app, name, s.value)) BridgeOutcome.Ok() else noSuch()
                }
            }
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private fun noSuch() = BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "no such account")

    private inline fun withName(request: BridgeRequest, block: (String) -> BridgeOutcome): BridgeOutcome =
        when (val n = AccountsSpec.parseName(request.args)) {
            is AccountsSpec.Parsed.Bad -> BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, n.message)
            is AccountsSpec.Parsed.Ok -> block(n.value)
        }

    private fun add(request: BridgeRequest, app: InstalledWebApp): BridgeOutcome = withName(request) { name ->
        when (DeftAccounts.add(context, app, name)) {
            DeftAccounts.AddResult.Added, DeftAccounts.AddResult.Exists -> BridgeOutcome.Ok(
                buildJsonObject {
                    put("name", name)
                    put("shownAs", AccountsSpec.displayName(name, app.name))
                },
            )
            DeftAccounts.AddResult.TooMany ->
                BridgeOutcome.Err(BridgeErrors.IN_USE, "at most ${AccountsSpec.MAX_PER_APP} accounts; remove one first")
            DeftAccounts.AddResult.Taken ->
                BridgeOutcome.Err(BridgeErrors.IN_USE, "Another app already has an account with this name")
            DeftAccounts.AddResult.Failed ->
                BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Android did not add the account")
        }
    }

    private fun listAll(request: BridgeRequest): BridgeOutcome {
        HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        val rows = DeftAccounts.listAll(context)
        return BridgeOutcome.Ok(
            buildJsonArray {
                for ((name, type) in rows) add(buildJsonObject { put("name", name); put("type", type) })
            },
            metadata = """{"count":${rows.size}}""",
        )
    }
}
