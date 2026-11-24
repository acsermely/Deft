package app.deft.bridge

import android.content.Context
import android.util.Log
import app.deft.catalog.Capability
import app.deft.catalog.Risk
import app.deft.catalog.isGranted
import app.deft.data.AppRepository
import app.deft.special.SystemAccess
import app.deft.ui.consent.ConsentActivity
import app.deft.ui.special.SpecialGrantActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A capability a bridge call needs but cannot use yet: the launcher grant is missing, or (special only) the Android
 * access behind it is off. A special capability opens the launcher's own sheet ([SpecialGrantActivity]), where the
 * screen lock is the grant. A plain launcher capability (for example `location`) opens the consent screen
 * ([ConsentActivity]) restricted to that one capability, where the user's tap on Allow is the grant. Both run on the
 * same gates as [OsPermissions], and the call re-reads the state after the screen closes.
 */
object SpecialAsk {
    /** Screen lock and Android's own settings take time, so the wait is long. */
    const val SHEET_TIMEOUT_MS = 120_000L

    const val HINT = "Turn it on when asked, or from the dashboard"

    private val cooldown = DenyCooldown()

    /** Debug reset: the app's refusals no longer repeat from the cooldown. */
    fun forgetApp(webAppId: String) = cooldown.clear(webAppId)

    /** What [ensure] decided. */
    sealed interface Outcome {
        /** Nothing ran: the grant (and for [needSystem] the Android access) was already in place. */
        data object Satisfied : Outcome

        /** The sheet ran and was closed; the grant (and for [needSystem] the access) is now in place. */
        data object SheetRan : Outcome

        /** The call must return [err]. */
        data class Refused(val err: BridgeOutcome.Err) : Outcome
    }

    /**
     * [code] is the error the call would have returned without the sheet. Shares the host's one-request slot
     * ([HostSurfaces.dialogSlot]) with the permission dialogs, so the two are never open together.
     */
    suspend fun ensure(
        context: Context,
        repository: AppRepository,
        webAppId: String,
        capability: Capability,
        background: Boolean,
        needSystem: Boolean,
        code: String,
        message: String,
    ): Outcome = ensureWith(
        SpecialAndroidEnv(context, repository, HostSurfaces.dialogSlot),
        cooldown,
        webAppId,
        capability,
        background,
        needSystem,
        code,
        message,
    )

    /**
     * Several plain capabilities one request needs at once (a page asking for camera and microphone). One consent
     * screen lists every missing one; the user ticks or unticks each. The request goes on only when all are granted.
     * Refused without a screen when any missing one is special, undeclared, or explicitly denied.
     */
    suspend fun ensureConsent(
        context: Context,
        repository: AppRepository,
        webAppId: String,
        capabilities: List<Capability>,
        background: Boolean,
        code: String,
        message: String,
    ): Outcome = ensureConsentWith(
        SpecialAndroidEnv(context, repository, HostSurfaces.dialogSlot),
        cooldown,
        webAppId,
        capabilities,
        background,
        code,
        message,
    )

    /** The consent-set decision, with every platform call behind [env] so it can be tested. */
    internal suspend fun ensureConsentWith(
        env: SpecialEnv,
        cooldown: DenyCooldown,
        webAppId: String,
        capabilities: List<Capability>,
        background: Boolean,
        code: String,
        message: String,
    ): Outcome {
        fun decide(reason: String) =
            env.log("consent app=$webAppId capabilities=${capabilities.joinToString { it.id }} reason=$reason")
        val states = capabilities.associateWith { env.state(webAppId, it) }
        val missing = capabilities.filter { !states.getValue(it).granted }
        if (missing.isEmpty()) {
            decide("satisfied")
            return Outcome.Satisfied
        }
        val reason = when {
            background -> "background"
            missing.any { it.risk == Risk.SPECIAL } -> "special"
            missing.any { !states.getValue(it).declared } -> "undeclared"
            missing.any { states.getValue(it).deniedByUser } -> "denied by user"
            missing.any { states.getValue(it).heldByOther } -> "held by other app"
            else -> null
        }
        if (reason != null) {
            decide(reason)
            return refused(plain(code, message))
        }
        val hinted = hinted(code, message)
        if (!env.onScreen(webAppId)) {
            decide("not on screen")
            return refused(hinted)
        }
        if (!env.tapRecent(webAppId)) {
            decide("no tap")
            return refused(hinted)
        }
        val ids = missing.map { it.id }
        if (cooldown.lastRefusal(webAppId, ids, env.nowMs()) != null) {
            decide("cooldown")
            return refused(hinted)
        }
        if (!env.spendTapForAsk(webAppId, UserGesture.AskKind.CONSENT)) {
            decide("tap spent")
            return refused(hinted)
        }
        if (!env.openConsentSheet(webAppId, missing)) {
            decide("sheet not started")
            return refused(hinted)
        }
        if (capabilities.all { env.state(webAppId, it).granted }) {
            env.refreshGesture(webAppId)
            decide("sheet ran")
            return Outcome.SheetRan
        }
        // Unticked ones are DENIED rows now (a user denial), so the next request does not ask for them again.
        cooldown.record(webAppId, ids, RuntimePermissions.Answer.DENIED, env.nowMs())
        decide("denied by user")
        return refused(hinted)
    }

    /** The decision, with every platform call behind [env] so it can be tested. */
    internal suspend fun ensureWith(
        env: SpecialEnv,
        cooldown: DenyCooldown,
        webAppId: String,
        capability: Capability,
        background: Boolean,
        needSystem: Boolean,
        code: String,
        message: String,
    ): Outcome {
        val special = capability.risk == Risk.SPECIAL
        val kindName = if (special) "special" else "consent"
        val askKind = if (special) UserGesture.AskKind.SPECIAL else UserGesture.AskKind.CONSENT
        fun decide(reason: String) = env.log("$kindName app=$webAppId capability=${capability.id} reason=$reason")
        val before = env.state(webAppId, capability)
        if (before.satisfied(needSystem)) {
            decide("satisfied")
            return Outcome.Satisfied
        }
        // Plain errors: a background run cannot show a screen, an undeclared or user-denied capability is
        // not asked again, and another app holding the exclusive group would refuse the sheet anyway.
        if (background || !before.declared || before.deniedByUser || before.heldByOther) {
            decide(
                when {
                    background -> "background"
                    !before.declared -> "undeclared"
                    before.deniedByUser -> "denied by user"
                    else -> "held by other app"
                },
            )
            return refused(plain(code, message))
        }
        if (!env.onScreen(webAppId)) {
            decide("not on screen")
            return refused(hinted(code, message))
        }
        if (!env.tapRecent(webAppId)) {
            decide("no tap")
            return refused(hinted(code, message))
        }
        if (cooldown.lastRefusal(webAppId, listOf(capability.id), env.nowMs()) != null) {
            decide("cooldown")
            return refused(hinted(code, message))
        }
        if (!env.spendTapForAsk(webAppId, askKind)) {
            decide("tap spent")
            return refused(hinted(code, message))
        }
        // False when the sheet cannot start (no resumed screen, or another sheet is open): no cooldown for that.
        if (!env.openSheet(webAppId, capability, needSystem)) {
            decide("sheet not started")
            return refused(hinted(code, message))
        }
        if (env.state(webAppId, capability).satisfied(needSystem)) {
            // The user allowed it on the sheet: the Allow tap is a new gesture for the rest of this chain.
            env.refreshGesture(webAppId)
            decide("sheet ran")
            return Outcome.SheetRan
        }
        cooldown.record(webAppId, listOf(capability.id), RuntimePermissions.Answer.DENIED, env.nowMs())
        decide("denied by user")
        return refused(hinted(code, message))
    }

    private fun refused(err: BridgeOutcome.Err) = Outcome.Refused(err)
    private fun plain(code: String, message: String) = BridgeOutcome.Err(code, message)
    private fun hinted(code: String, message: String) = BridgeOutcome.Err(code, "$message. $HINT.")
}

/** What the bridge knows about one special capability of one app, read fresh. */
internal data class SpecialState(
    /** The app's current manifest declares the capability. */
    val declared: Boolean,
    /** The launcher grant is held (not DENIED). */
    val granted: Boolean,
    /** The user turned the grant off (see [AppRepository.deniedByUser]); a never-asked grant is false. */
    val deniedByUser: Boolean,
    /** An exclusive group this capability belongs to is held by another app. */
    val heldByOther: Boolean,
    /** Android's own access for this capability is off (false when there is none to turn on). */
    val systemOff: Boolean,
) {
    fun satisfied(needSystem: Boolean): Boolean = granted && (!needSystem || !systemOff)
}

/** Everything [SpecialAsk] needs from the repository, Android and the host screen. */
internal interface SpecialEnv {
    suspend fun state(webAppId: String, capability: Capability): SpecialState
    fun onScreen(webAppId: String): Boolean
    fun tapRecent(webAppId: String): Boolean
    fun spendTapForAsk(webAppId: String, kind: UserGesture.AskKind): Boolean
    /** The user allowed the sheet: the Allow tap becomes a new gesture for the app. */
    fun refreshGesture(webAppId: String)
    fun nowMs(): Long
    /** One decision line for the "DeftAsk" log. Never carries argument values. */
    fun log(line: String)

    /** Shows the sheet on the app's screen and returns once it is closed (or timed out). False when it cannot be shown. */
    suspend fun openSheet(webAppId: String, capability: Capability, needSystem: Boolean): Boolean

    /** Shows one consent screen for plain [capabilities] (never special) and returns once it is closed. False when it cannot be shown. */
    suspend fun openConsentSheet(webAppId: String, capabilities: List<Capability>): Boolean
}

private class SpecialAndroidEnv(
    private val context: Context,
    private val repository: AppRepository,
    private val slot: RequestSlot,
) : SpecialEnv {
    override suspend fun state(webAppId: String, capability: Capability): SpecialState {
        val access = SystemAccess.forAsk(capability)
        return withContext(Dispatchers.IO) {
            SpecialState(
                declared = capability in repository.declaredCapabilities(webAppId),
                granted = repository.grantLevel(webAppId, capability).isGranted(),
                deniedByUser = repository.deniedByUser(webAppId, capability),
                heldByOther = capability.exclusiveGroup?.let { repository.specialHolder(it) }
                    ?.let { it != webAppId } == true,
                systemOff = access != null && !access.isGranted(context),
            )
        }
    }

    override fun onScreen(webAppId: String): Boolean = HostSurfaces.forApp(webAppId) != null
    override fun tapRecent(webAppId: String): Boolean = UserGesture.recent(webAppId)
    override fun spendTapForAsk(webAppId: String, kind: UserGesture.AskKind): Boolean =
        UserGesture.spendForAsk(webAppId, kind)
    override fun refreshGesture(webAppId: String) = UserGesture.refreshAfterAllow(webAppId)
    override fun nowMs(): Long = android.os.SystemClock.uptimeMillis()
    override fun log(line: String) {
        Log.i("DeftAsk", line)
    }

    override suspend fun openSheet(webAppId: String, capability: Capability, needSystem: Boolean): Boolean {
        // A plain capability is granted on the consent screen, not the special sheet.
        if (capability.risk != Risk.SPECIAL) return openConsentSheet(webAppId, listOf(capability))
        val surface = HostSurfaces.forApp(webAppId) ?: return false
        return slot.tryRun {
            val token = UUID.randomUUID().toString()
            val closed = SpecialAsks.open(token)
            try {
                val intent = SpecialGrantActivity.intent(
                    context,
                    webAppId,
                    capability,
                    requestToken = token,
                    fromApp = true,
                    systemOnly = needSystem,
                )
                val started = withContext(Dispatchers.Main) { surface.openSpecialSheet(intent) }
                // The sheet's close (any way it ends, or the timeout) is the answer; the call then re-reads the state.
                if (started) withTimeoutOrNull(SpecialAsk.SHEET_TIMEOUT_MS) { closed.await() }
                started
            } finally {
                SpecialAsks.forget(token)
            }
        } ?: false
    }

    override suspend fun openConsentSheet(webAppId: String, capabilities: List<Capability>): Boolean {
        val surface = HostSurfaces.forApp(webAppId) ?: return false
        // The consent screen needs the app's own record to open.
        val app = repository.getApp(webAppId) ?: return false
        return slot.tryRun {
            val token = UUID.randomUUID().toString()
            val closed = SpecialAsks.open(token)
            try {
                val intent = ConsentActivity.pointOfUseIntent(context, app, capabilities, token)
                val started = intent != null && withContext(Dispatchers.Main) { surface.openSpecialSheet(intent) }
                if (started) withTimeoutOrNull(SpecialAsk.SHEET_TIMEOUT_MS) { closed.await() }
                started
            } finally {
                SpecialAsks.forget(token)
            }
        } ?: false
    }
}

/**
 * The waiting side of a sheet opened by a bridge call. [SpecialGrantActivity] reports its close here by the
 * request token it was given. Nothing is granted here: the caller re-reads the grant after the close.
 */
object SpecialAsks {
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    internal fun open(token: String): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { waiting[token] = it }

    /** Called when the sheet ends for good (not on a rotation). Unknown tokens are ignored. */
    fun closed(token: String) {
        waiting.remove(token)?.complete(Unit)
    }

    internal fun forget(token: String) {
        waiting.remove(token)?.cancel()
    }
}
