package app.deft.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Runtime permissions for bridge calls and web requests. One gate: never from a background run, only on
 * screen, only right after a tap (one tap buys one dialog), only for permissions the manifest declares,
 * and not again for [DenyCooldown.WINDOW_MS] after a refusal. Android's own permission dialog is the only prompt (see the host activity).
 */
object OsPermissions {
    /** Android's dialog gets this long before the ask counts as refused. */
    const val SYSTEM_TIMEOUT_MS = 60_000L

    /** Safety net around the whole ask in [ensure]. */
    const val ASK_TIMEOUT_MS = SYSTEM_TIMEOUT_MS + 30_000L

    private val cooldown = DenyCooldown()
    @Volatile private var declaredCache: Set<String>? = null

    /** Debug reset: the app's refusals no longer repeat from the cooldown. */
    fun forgetApp(webAppId: String) = cooldown.clear(webAppId)

    /** The runtime permissions the launcher's manifest asks for (read once per process). */
    fun declared(context: Context): Set<String> =
        declaredCache ?: context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet().also { declaredCache = it }

    /**
     * Null when every permission is held, or the user has just granted the missing ones. Otherwise the error to
     * return: [message], with the app-settings hint when Android will not ask again.
     */
    suspend fun ensure(
        context: Context,
        webAppId: String,
        background: Boolean,
        permissions: List<String>,
        message: String,
    ): BridgeOutcome.Err? = ensureWith(AndroidEnv(context), cooldown, webAppId, background, permissions, message, ASK_TIMEOUT_MS)

    /** The decision, with every platform call behind [env] so it can be tested. */
    internal suspend fun ensureWith(
        env: PermissionEnv,
        cooldown: DenyCooldown,
        webAppId: String,
        background: Boolean,
        permissions: List<String>,
        message: String,
        timeoutMs: Long,
    ): BridgeOutcome.Err? {
        val missing = permissions.filterNot { env.held(it) }
        if (missing.isEmpty()) {
            env.log("system app=$webAppId permission=${permissions.joinToString()} reason=satisfied")
            return null
        }
        fun refuse(reason: String, text: String = message): BridgeOutcome.Err {
            env.log("system app=$webAppId permission=${missing.joinToString()} reason=$reason")
            return refused(text)
        }
        if (background) return refuse("background")
        val declared = env.declared()
        val onScreen = env.onScreen(webAppId)
        val tap = env.tapRecent(webAppId)
        if (!RuntimePermissions.canAsk(missing, declared, onScreen, tap)) {
            return refuse(
                when {
                    missing.any { it !in declared } -> "undeclared"
                    !onScreen -> "not on screen"
                    else -> "no tap"
                },
            )
        }
        cooldown.lastRefusal(webAppId, missing, env.nowMs())?.let {
            return refuse("cooldown", RuntimePermissions.refusalMessage(message, it))
        }
        if (!env.spendTapForAsk(webAppId, UserGesture.AskKind.SYSTEM)) return refuse("tap spent")
        // Null covers a request that could not start (another one is open, or no resumed screen). A timeout is separate.
        var started: RuntimePermissions.Answer? = null
        withTimeoutOrNull(timeoutMs) {
            started = env.askOnScreen(webAppId, missing)
            true
        } ?: return refuse("timeout")
        val answer = started ?: return refuse("slot busy")
        if (answer == RuntimePermissions.Answer.GRANTED) {
            env.refreshGesture(webAppId)
            env.log("system app=$webAppId permission=${missing.joinToString()} reason=allowed")
            return null
        }
        cooldown.record(webAppId, missing, answer, env.nowMs())
        val reason = if (answer == RuntimePermissions.Answer.BLOCKED) "blocked in settings" else "denied by user"
        return refuse(reason, RuntimePermissions.refusalMessage(message, answer))
    }

    private fun refused(message: String) = BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, message)
}

/** Everything [OsPermissions] needs from Android and the host screen. */
internal interface PermissionEnv {
    fun held(permission: String): Boolean
    fun declared(): Set<String>
    fun onScreen(webAppId: String): Boolean
    fun tapRecent(webAppId: String): Boolean
    fun spendTapForAsk(webAppId: String, kind: UserGesture.AskKind): Boolean
    /** The user allowed an ask: the Allow tap becomes a new gesture for the app. */
    fun refreshGesture(webAppId: String)
    fun nowMs(): Long
    /** One decision line for the "DeftAsk" log. Never carries argument values. */
    fun log(line: String)

    /** Shows Android's dialog on the app's screen. Null when that cannot start. */
    suspend fun askOnScreen(webAppId: String, permissions: List<String>): RuntimePermissions.Answer?
}

private class AndroidEnv(private val context: Context) : PermissionEnv {
    override fun held(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun declared(): Set<String> = OsPermissions.declared(context)
    override fun onScreen(webAppId: String): Boolean = HostSurfaces.forApp(webAppId) != null
    override fun tapRecent(webAppId: String): Boolean = UserGesture.recent(webAppId)
    override fun spendTapForAsk(webAppId: String, kind: UserGesture.AskKind): Boolean =
        UserGesture.spendForAsk(webAppId, kind)
    override fun refreshGesture(webAppId: String) = UserGesture.refreshAfterAllow(webAppId)
    override fun nowMs(): Long = SystemClock.uptimeMillis()
    override fun log(line: String) {
        Log.i("DeftAsk", line)
    }

    override suspend fun askOnScreen(webAppId: String, permissions: List<String>): RuntimePermissions.Answer? {
        val surface = HostSurfaces.forApp(webAppId) ?: return null
        return withContext(Dispatchers.Main) { surface.requestPermissions(permissions) }
    }
}

/** After a refusal, the same permission is not asked again for [windowMs]; the refusal is repeated without a dialog. */
internal class DenyCooldown(private val windowMs: Long = WINDOW_MS) {
    private val entries = ConcurrentHashMap<String, Pair<Long, RuntimePermissions.Answer>>()

    fun record(webAppId: String, permissions: List<String>, answer: RuntimePermissions.Answer, now: Long) {
        permissions.forEach { entries[key(webAppId, it)] = (now + windowMs) to answer }
    }

    /** The refusal to repeat for [permissions] while the window is open, or null when asking is allowed. */
    fun lastRefusal(webAppId: String, permissions: List<String>, now: Long): RuntimePermissions.Answer? =
        permissions.firstNotNullOfOrNull { p ->
            entries[key(webAppId, p)]?.takeIf { it.first > now }?.second
        }

    /** Forgets every refusal of [webAppId], so its next ask is shown again (debug reset). */
    fun clear(webAppId: String) {
        val prefix = "$webAppId|"
        entries.keys.filter { it.startsWith(prefix) }.forEach { entries.remove(it) }
    }

    private fun key(webAppId: String, permission: String) = "$webAppId|$permission"

    companion object {
        const val WINDOW_MS = 30_000L
    }
}

/**
 * Lets one runtime-permission request run on a host screen at a time. The slot is released when the request
 * ends, fails or is cancelled (for example by the timeout in [OsPermissions.ensure]). Null when it is busy.
 */
internal class RequestSlot {
    private val owner = AtomicReference<Any?>(null)

    val busy: Boolean get() = owner.get() != null

    suspend fun <T> tryRun(block: suspend () -> T): T? {
        val token = Any()
        if (!owner.compareAndSet(null, token)) return null
        try {
            return block()
        } finally {
            owner.compareAndSet(token, null)
        }
    }
}
