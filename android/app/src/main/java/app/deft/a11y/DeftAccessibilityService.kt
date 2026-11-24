package app.deft.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.deft.DeftApp
import app.deft.bridge.A11ySpec
import app.deft.bridge.BridgeService
import app.deft.catalog.Capability
import app.deft.catalog.isGranted
import app.deft.special.SpecialUse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * PARITY G9: the launcher's accessibility service (capability `accessibility`). Android binds it
 * once the user turns it on under Accessibility; the FL grant decides which one web app (the
 * holder of exclusive group `a11y`) gets anything.
 *
 * - The holder is read again for every forwarded event and every call, so revoking the grant or
 *   uninstalling the holder stops it at once. With no holder, events are read and dropped.
 * - Events reach only an open page of the holder and are never queued: screen content is not
 *   stored by the launcher. At most 10 a second ([A11ySpec.Throttle]).
 * - Android's approval surfaces ([A11ySpec.BLOCKED_PACKAGES], SystemUI approval dialogs) and the
 *   launcher's grant and confirm screens and agent panel ([A11yGuard]) are never read or acted
 *   on. Password text is never returned.
 * - While a holder exists and the service is connected, "<App> is using: See and control your
 *   screen" shows ([SpecialUse]).
 *
 * Screen text is never logged.
 */
class DeftAccessibilityService : AccessibilityService() {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val throttle = A11ySpec.Throttle()

    @Volatile private var connected = false

    @Volatile private var usingFor: String? = null
    private var lastAudit = 0L
    private var throttleHolder: String? = null

    /** The last window tree: node ids are indexes into [snapshotNodes] of generation [generation]. */
    private val snapshotLock = Any()
    private var generation = 0L
    private var snapshotOwner: String? = null
    private var snapshotNodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        connected = true
        instance = this
        scope.launch { syncUse(currentHolder()) }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun disconnect() {
        connected = false
        if (instance === this) instance = null
        clearSnapshot()
        usingFor?.let { SpecialUse.stop(this, it, CAP) }
        usingFor = null
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !connected) return
        val name = A11ySpec.eventName(event.eventType) ?: return
        // Read everything now: Android reuses the event after this returns.
        val raw = runCatching {
            A11ySpec.RawEvent(
                type = event.eventType,
                pkg = event.packageName?.toString(),
                className = event.className?.toString(),
                text = event.text.orEmpty().map { it?.toString() },
                contentDescription = event.contentDescription?.toString(),
                password = event.isPassword,
            )
        }.getOrNull() ?: return
        val shaped = A11ySpec.shapeEvent(raw, packageName, A11yGuard.guardedVisible()) ?: return
        val now = System.currentTimeMillis()
        if (!synchronized(throttle) { throttle.tryAcquire(now, A11ySpec.noisy(name)) }) return
        scope.launch { deliver(shaped) }
    }

    private suspend fun currentHolder(): String? =
        DeftApp.instance.repository.specialHolder(A11ySpec.GROUP)

    private suspend fun deliver(payload: JsonObject) {
        val repo = DeftApp.instance.repository
        val holder = currentHolder()
        syncUse(holder)
        holderChanged(holder)
        if (holder == null || !repo.grantLevel(holder, CAP).isGranted()) return
        val svc = BridgeService.instanceOrNull() ?: return
        if (svc.emitLive(holder, A11ySpec.EVENT, payload.toString())) audit(holder)
    }

    /** A new holder starts with an empty event window and no window snapshot. Runs on [scope]. */
    private fun holderChanged(holder: String?) {
        if (holder == throttleHolder) return
        throttleHolder = holder
        synchronized(throttle) { throttle.reset() }
        clearSnapshot()
    }

    private suspend fun audit(holder: String) {
        val now = System.currentTimeMillis()
        if (!A11ySpec.auditDue(lastAudit, now)) return
        lastAudit = now
        DeftApp.instance.repository.log(holder, "a11y", CAP.id, A11ySpec.EVENT, "ok")
    }

    /** Shows or moves the "in use" notification to match [holder] while connected. */
    private suspend fun syncUse(holder: String?) {
        val want = if (connected) holder else null
        val cur = usingFor
        if (want == cur && (want == null || SpecialUse.isActive(want, CAP))) return
        if (cur != null && cur != want) SpecialUse.stop(this, cur, CAP)
        usingFor = if (want != null && SpecialUse.start(this, want, CAP)) want else null
    }

    // ---- calls from A11yModule ---------------------------------------------------------------

    private fun allowed(pkg: CharSequence?): Boolean =
        A11ySpec.packageAllowed(pkg?.toString(), packageName, A11yGuard.guardedVisible())

    private fun clearSnapshot() = synchronized(snapshotLock) {
        snapshotNodes = emptyList()
        snapshotOwner = null
    }

    sealed class Tree {
        data class Ok(val json: JsonObject, val count: Int) : Tree()
        data object Blocked : Tree()
        data object NoWindow : Tree()
    }

    /** Reads the active window for [webAppId]; a new snapshot replaces the last one. */
    fun windowTree(webAppId: String, args: A11ySpec.TreeArgs): Tree {
        val root = rootInActiveWindow
        val gen: Long
        synchronized(snapshotLock) {
            generation += 1
            gen = generation
            snapshotNodes = emptyList()
            snapshotOwner = webAppId
        }
        if (root == null) return Tree.NoWindow
        val pkg = root.packageName?.toString()
        if (!allowed(pkg) || approvalWindowShown()) return Tree.Blocked
        val walk = A11ySpec.walk(
            root = root,
            childCount = { it.childCount },
            child = { n, i -> runCatching { n.getChild(i) }.getOrNull() },
            include = { it.isVisibleToUser && allowed(it.packageName) },
            maxDepth = args.maxDepth,
            maxNodes = args.maxNodes,
        )
        val nodes = walk.visits.mapIndexed { i, v ->
            val parentId = if (v.parent >= 0) A11ySpec.nodeId(gen, v.parent) else null
            A11ySpec.shapeNode(raw(v.node), A11ySpec.nodeId(gen, i), parentId)
        }
        // The guarded check again: a guarded screen may have opened while reading.
        if (!allowed(pkg)) return Tree.Blocked
        synchronized(snapshotLock) {
            if (generation == gen && snapshotOwner == webAppId) snapshotNodes = walk.visits.map { it.node }
        }
        val json = buildJsonObject {
            put("package", pkg)
            put("truncated", walk.truncated)
            put("nodes", kotlinx.serialization.json.JsonArray(nodes))
        }
        return Tree.Ok(json, nodes.size)
    }

    private fun raw(n: AccessibilityNodeInfo): A11ySpec.RawNode {
        val r = Rect()
        n.getBoundsInScreen(r)
        return A11ySpec.RawNode(
            className = n.className?.toString(),
            text = if (n.isPassword) null else n.text?.toString(),
            contentDescription = n.contentDescription?.toString(),
            viewId = n.viewIdResourceName,
            bounds = intArrayOf(r.left, r.top, r.right, r.bottom),
            clickable = n.isClickable,
            editable = n.isEditable,
            scrollable = n.isScrollable,
            checkable = n.isCheckable,
            checked = n.isChecked,
            focused = n.isFocused,
            password = n.isPassword,
        )
    }

    enum class ActResult { OK, FAILED, STALE, GONE, BLOCKED }

    fun perform(webAppId: String, p: A11ySpec.PerformArgs): ActResult {
        val node = synchronized(snapshotLock) {
            if (snapshotOwner != webAppId || p.node.generation != generation) return ActResult.STALE
            snapshotNodes.getOrNull(p.node.index) ?: return ActResult.STALE
        }
        if (!runCatching { node.refresh() }.getOrDefault(false)) return ActResult.GONE
        if (!allowed(node.packageName) || !screenAllowed()) return ActResult.BLOCKED
        val ok = runCatching {
            when (p.action) {
                A11ySpec.Action.CLICK -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                A11ySpec.Action.LONG_CLICK -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                A11ySpec.Action.SCROLL_FORWARD -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                A11ySpec.Action.SCROLL_BACKWARD -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                A11ySpec.Action.FOCUS -> node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                A11ySpec.Action.SET_TEXT -> node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, p.text.orEmpty())
                    },
                )
            }
        }.getOrDefault(false)
        return if (ok) ActResult.OK else ActResult.FAILED
    }

    /**
     * False while any window on screen belongs to a blocked package or is a SystemUI approval
     * dialog, or a guarded launcher screen shows.
     */
    private fun screenAllowed(): Boolean {
        if (A11yGuard.guardedVisible()) return false
        val ws = runCatching { windows }.getOrNull().orEmpty()
        for (w in ws) {
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: continue
            if (pkg in A11ySpec.BLOCKED_PACKAGES) return false
            if (A11ySpec.systemUiApprovalWindow(pkg, w.type)) return false
        }
        return activeWindowAllowed()
    }

    /** True while a SystemUI approval dialog (screen recording, USB debugging, USB access) is on screen. */
    private fun approvalWindowShown(): Boolean =
        runCatching { windows }.getOrNull().orEmpty().any { w ->
            A11ySpec.systemUiApprovalWindow(runCatching { w.root?.packageName?.toString() }.getOrNull(), w.type)
        }

    private fun activeWindowAllowed(): Boolean {
        val pkg = rootInActiveWindow?.packageName ?: return true
        return allowed(pkg)
    }

    /** Dispatches the stroke; null when refused (a guarded screen shows), else whether it completed. */
    suspend fun gesture(g: A11ySpec.GestureArgs): Boolean? {
        if (!screenAllowed()) return null
        val path = Path()
        val (x0, y0) = g.points.first()
        path.moveTo(x0, y0)
        for ((x, y) in g.points.drop(1)) path.lineTo(x, y)
        val stroke = runCatching { GestureDescription.StrokeDescription(path, 0, g.durationMs) }.getOrNull() ?: return false
        val result = withTimeoutOrNull(g.durationMs + 3_000) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()
                    A11yGuard.gestureStarted(g.durationMs)
                    val sent = runCatching {
                        dispatchGesture(
                            gesture,
                            object : GestureResultCallback() {
                                override fun onCompleted(gestureDescription: GestureDescription?) {
                                    A11yGuard.gestureEnded()
                                    if (cont.isActive) cont.resume(true)
                                }

                                override fun onCancelled(gestureDescription: GestureDescription?) {
                                    A11yGuard.gestureEnded()
                                    if (cont.isActive) cont.resume(false)
                                }
                            },
                            null,
                        )
                    }.getOrDefault(false)
                    if (!sent) {
                        A11yGuard.gestureEnded()
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
        }
        return result ?: false
    }

    fun global(g: A11ySpec.Global): Boolean = performGlobalAction(
        when (g) {
            A11ySpec.Global.BACK -> GLOBAL_ACTION_BACK
            A11ySpec.Global.HOME -> GLOBAL_ACTION_HOME
            A11ySpec.Global.RECENTS -> GLOBAL_ACTION_RECENTS
            A11ySpec.Global.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            A11ySpec.Global.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            A11ySpec.Global.LOCK_SCREEN -> GLOBAL_ACTION_LOCK_SCREEN
        },
    )

    companion object {
        private val CAP = Capability.ACCESSIBILITY

        @Volatile var instance: DeftAccessibilityService? = null
            private set

        fun component(context: Context) = ComponentName(context, DeftAccessibilityService::class.java)

        /** After the grant changed: show or move the "in use" notification. */
        fun syncUse(@Suppress("UNUSED_PARAMETER") context: Context) {
            val s = instance ?: return
            s.scope.launch {
                val holder = s.currentHolder()
                s.holderChanged(holder)
                s.syncUse(holder)
            }
        }
    }
}
