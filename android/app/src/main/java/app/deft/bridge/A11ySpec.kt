package app.deft.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * `a11y.*` (capability `accessibility`) rules. Pure, unit tested; the Android side is
 * `a11y.DeftAccessibilityService`, `a11y.A11yGuard` and `bridge.modules.A11yModule`.
 *
 * - One holder (exclusive group [GROUP]); events reach only an open page of it, never queued.
 * - Never read or act on a screen that grants or confirms something: Android's permission
 *   dialogs, the package installer, the VPN dialog and all of Settings ([BLOCKED_PACKAGES]),
 *   every launcher screen except the home screen, the app list and the web app pages
 *   ([READABLE_LAUNCHER_ACTIVITIES]), and the home screen while the AI agent panel is open. An
 *   accessibility holder must not be able to approve its own grants. Such screens are left out
 *   of events and trees, and `perform` / `gesture` are refused while one shows. SystemUI's own
 *   activity windows (screen recording consent, USB debugging, USB device access) are approval
 *   dialogs too: no tree and no acting while one is on screen ([systemUiApprovalWindow]); the
 *   status bar, shade and navigation bar are system windows and stay usable.
 * - Never return the text of a password field (nodes and events).
 * - Events at most [EVENTS_PER_SECOND] a second; noisy kinds use at most half of that.
 */
object A11ySpec {
    const val GROUP = "a11y"
    const val EVENT = "a11y.event"

    const val TEXT_MAX = 1000
    const val CLASS_MAX = 200
    const val VIEW_ID_MAX = 200
    const val SET_TEXT_MAX = 5000
    const val NODE_ID_MAX = 24

    const val MAX_DEPTH = 30
    const val MAX_NODES = 2000
    const val DEFAULT_DEPTH = 30
    const val DEFAULT_NODES = 1000

    const val MAX_POINTS = 50
    const val MAX_DURATION_MS = 10_000
    const val DEFAULT_DURATION_MS = 200
    const val MAX_COORD = 100_000.0

    const val EVENTS_PER_SECOND = 10
    const val NOISY_PER_SECOND = 5

    /** At most one audit row per hour for forwarded events (the 30-day review needs a recent use). */
    const val AUDIT_EVERY_MS = 3_600_000L

    /**
     * Approval surfaces: never read, never touched. Android's permission dialogs (runtime
     * permissions, roles), the package installer (install, uninstall, "unknown sources"), the VPN
     * consent dialog and all of Settings, where special access (accessibility, notification
     * access, device admin, overlays, default apps) is turned on. An accessibility holder
     * therefore cannot automate Settings at all.
     */
    val BLOCKED_PACKAGES: Set<String> = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.vpndialogs",
        "com.android.settings",
    )

    const val SYSTEM_UI = "com.android.systemui"

    /** android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION. */
    const val WINDOW_TYPE_APPLICATION = 1

    /**
     * True for a SystemUI activity window: the screen recording (MediaProjection) consent, USB
     * debugging and USB device access dialogs are activities of SystemUI. The status bar, the
     * notification shade and the navigation bar are system windows and are not matched.
     */
    fun systemUiApprovalWindow(pkg: String?, windowType: Int): Boolean =
        pkg == SYSTEM_UI && windowType == WINDOW_TYPE_APPLICATION

    /** The launcher's code namespace (activity class names; the package may carry `.debug`). */
    const val LAUNCHER_NAMESPACE = "app.deft"

    /**
     * Launcher screens an accessibility holder may read and act on. Everything else in the
     * launcher (consent, confirm, special grant, handler picker, keystore unlock, the dashboard
     * with its grant switches, the pickers that hand files or devices to an app) is guarded.
     * An allow list, so a new launcher screen is guarded until someone decides otherwise.
     */
    val READABLE_LAUNCHER_ACTIVITIES: Set<String> = setOf(
        "$LAUNCHER_NAMESPACE.ui.home.HomeActivity",
        "$LAUNCHER_NAMESPACE.ui.home.MyAppsActivity",
        "$LAUNCHER_NAMESPACE.ui.host.WebViewHostActivity",
    )

    /** True when [className] (an activity of the launcher) is a screen the holder must not see. */
    fun guardedLauncherActivity(className: String): Boolean = className !in READABLE_LAUNCHER_ACTIVITIES

    /**
     * Whether a window or node of [pkg] may be read or acted on. [guardedVisible] is true while
     * a guarded launcher screen is started; then no launcher window is readable at all (a dialog
     * and the page behind it share the package).
     */
    fun packageAllowed(pkg: String?, ownPackage: String, guardedVisible: Boolean): Boolean {
        if (pkg.isNullOrEmpty()) return false
        if (pkg in BLOCKED_PACKAGES) return false
        if (pkg == ownPackage && guardedVisible) return false
        return true
    }

    // ---- events ------------------------------------------------------------------------------

    /** android.view.accessibility.AccessibilityEvent TYPE_* values forwarded, with their names. */
    private val EVENT_NAMES: Map<Int, String> = mapOf(
        0x1 to "viewClicked",
        0x2 to "viewLongClicked",
        0x4 to "viewSelected",
        0x8 to "viewFocused",
        0x10 to "viewTextChanged",
        0x20 to "windowStateChanged",
        0x40 to "notificationStateChanged",
        0x800 to "windowContentChanged",
        0x1000 to "viewScrolled",
        0x2000 to "viewTextSelectionChanged",
        0x4000 to "announcement",
        0x400000 to "windowsChanged",
    )
    const val TYPE_WINDOW_STATE_CHANGED = 0x20
    const val TYPE_NOTIFICATION_STATE_CHANGED = 0x40
    private val NOISY = setOf("windowContentChanged", "viewScrolled", "windowsChanged")

    /** The plain name of an Android event type, or null when that type is not forwarded. */
    fun eventName(type: Int): String? = EVENT_NAMES[type]

    fun noisy(name: String): Boolean = name in NOISY

    /** What the service read from one event, before the rules. */
    data class RawEvent(
        val type: Int,
        val pkg: String?,
        val className: String?,
        val text: List<String?>,
        val contentDescription: String?,
        val password: Boolean,
    )

    /**
     * The `a11y.event` payload `{type, package, className?, text?}`, or null when the event is
     * not forwarded: unknown type, a blocked package, a launcher window while a guarded screen
     * shows or a guarded launcher screen opening, and the launcher's own notifications (the
     * notification listener rule: one web app never reads another's `notify.post`).
     */
    fun shapeEvent(raw: RawEvent, ownPackage: String, guardedVisible: Boolean): JsonObject? {
        val name = eventName(raw.type) ?: return null
        val pkg = raw.pkg
        if (!packageAllowed(pkg, ownPackage, guardedVisible)) return null
        if (pkg == ownPackage) {
            if (raw.type == TYPE_NOTIFICATION_STATE_CHANGED) return null
            val cls = raw.className
            if (raw.type == TYPE_WINDOW_STATE_CHANGED && cls != null &&
                cls.startsWith("$LAUNCHER_NAMESPACE.") && guardedLauncherActivity(cls)
            ) {
                return null
            }
        }
        val text = if (raw.password) {
            null
        } else {
            cap(raw.text.filterNotNull().filter { it.isNotBlank() }.joinToString(" "), TEXT_MAX)
                ?: cap(raw.contentDescription, TEXT_MAX)
        }
        return buildJsonObject {
            put("type", name)
            put("package", pkg)
            cap(raw.className, CLASS_MAX)?.let { put("className", it) }
            text?.let { put("text", it) }
        }
    }

    /**
     * At most [EVENTS_PER_SECOND] events in any second, of which at most [NOISY_PER_SECOND]
     * noisy ones (content changes, scrolling), so window changes still get through. There is
     * one holder, so one window serves it; [reset] when the holder changes. Not thread-safe.
     */
    class Throttle(
        private val perSecond: Int = EVENTS_PER_SECOND,
        private val noisyPerSecond: Int = NOISY_PER_SECOND,
    ) {
        private val sent = ArrayDeque<Pair<Long, Boolean>>()

        fun tryAcquire(now: Long, noisy: Boolean): Boolean {
            while (sent.isNotEmpty() && now - sent.first().first >= 1000L) sent.removeFirst()
            if (sent.size >= perSecond) return false
            if (noisy && sent.count { it.second } >= noisyPerSecond) return false
            sent.addLast(now to noisy)
            return true
        }

        fun reset() = sent.clear()
    }

    fun auditDue(lastAt: Long, now: Long): Boolean = lastAt == 0L || now - lastAt >= AUDIT_EVERY_MS

    // ---- window tree -------------------------------------------------------------------------

    /** What the service read from one node. */
    data class RawNode(
        val className: String?,
        val text: String?,
        val contentDescription: String?,
        val viewId: String?,
        val bounds: IntArray,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val focused: Boolean,
        val password: Boolean,
    )

    fun nodeId(generation: Long, index: Int): String = "$generation:$index"

    /**
     * `{id, parentId?, className, text?, contentDescription?, viewId?, bounds, clickable, editable,
     * scrollable, checked?, focused, password}`. A password field never has `text`.
     */
    fun shapeNode(raw: RawNode, id: String, parentId: String?): JsonObject = buildJsonObject {
        put("id", id)
        parentId?.let { put("parentId", it) }
        put("className", cap(raw.className, CLASS_MAX) ?: "")
        if (!raw.password) cap(raw.text, TEXT_MAX)?.let { put("text", it) }
        cap(raw.contentDescription, TEXT_MAX)?.let { put("contentDescription", it) }
        cap(raw.viewId, VIEW_ID_MAX)?.let { put("viewId", it) }
        put("bounds", buildJsonArray { raw.bounds.take(4).forEach { add(JsonPrimitive(it)) } })
        put("clickable", raw.clickable)
        put("editable", raw.editable)
        put("scrollable", raw.scrollable)
        if (raw.checkable) put("checked", raw.checked)
        put("focused", raw.focused)
        put("password", raw.password)
    }

    /** One node of a walk: [parent] is the index of its parent in the result, or -1 for the root. */
    data class Visit<N>(val node: N, val parent: Int, val depth: Int)

    data class Walk<N>(val visits: List<Visit<N>>, val truncated: Boolean)

    /**
     * Depth-first, parents before children. [include] false leaves a node and its subtree out
     * (not visible to the user). The root has depth 0; nodes deeper than [maxDepth] and nodes
     * past [maxNodes] are left out and [Walk.truncated] is set.
     */
    fun <N> walk(
        root: N,
        childCount: (N) -> Int,
        child: (N, Int) -> N?,
        include: (N) -> Boolean,
        maxDepth: Int,
        maxNodes: Int,
    ): Walk<N> {
        val out = ArrayList<Visit<N>>()
        var truncated = false
        val stack = ArrayDeque<Visit<N>>()
        stack.addLast(Visit(root, -1, 0))
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (!include(v.node)) continue
            if (out.size >= maxNodes) {
                truncated = true
                break
            }
            val index = out.size
            out += v
            val n = childCount(v.node)
            if (n <= 0) continue
            if (v.depth >= maxDepth) {
                truncated = true
                continue
            }
            // Push in reverse so children come out in order.
            for (i in n - 1 downTo 0) {
                val c = child(v.node, i) ?: continue
                stack.addLast(Visit(c, index, v.depth + 1))
            }
        }
        return Walk(out, truncated)
    }

    // ---- arguments ---------------------------------------------------------------------------

    sealed class Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>()
        data class Bad(val message: String) : Parsed<Nothing>()
    }

    data class TreeArgs(val maxDepth: Int, val maxNodes: Int)

    enum class Action(val wire: String) {
        CLICK("click"), LONG_CLICK("longClick"), SET_TEXT("setText"),
        SCROLL_FORWARD("scrollForward"), SCROLL_BACKWARD("scrollBackward"), FOCUS("focus"),
        ;

        companion object {
            fun of(wire: String): Action? = entries.firstOrNull { it.wire == wire }
        }
    }

    data class NodeRef(val generation: Long, val index: Int)

    data class PerformArgs(val node: NodeRef, val action: Action, val text: String?)

    data class GestureArgs(val points: List<Pair<Float, Float>>, val durationMs: Long)

    enum class Global(val wire: String) {
        BACK("back"), HOME("home"), RECENTS("recents"), NOTIFICATIONS("notifications"),
        QUICK_SETTINGS("quickSettings"), LOCK_SCREEN("lockScreen"),
        ;

        companion object {
            fun of(wire: String): Global? = entries.firstOrNull { it.wire == wire }
        }
    }

    private fun wholeNumber(args: JsonObject, key: String): Double? {
        val p = args[key] as? JsonPrimitive ?: return null
        if (p.isString) return Double.NaN
        val d = p.doubleOrNull ?: return Double.NaN
        return if (d == Math.floor(d)) d else Double.NaN
    }

    private fun intIn(args: JsonObject, key: String, min: Int, max: Int, default: Int): Parsed<Int> {
        if (args[key] == null || args[key] is JsonNull) return Parsed.Ok(default)
        val d = wholeNumber(args, key)
        if (d == null || d.isNaN() || d < min || d > max) return Parsed.Bad("$key must be a whole number from $min to $max")
        return Parsed.Ok(d.toInt())
    }

    /** `{maxDepth?, maxNodes?}`: 0 to [MAX_DEPTH] (default [DEFAULT_DEPTH]), 1 to [MAX_NODES] (default [DEFAULT_NODES]). */
    fun parseTree(args: JsonObject): Parsed<TreeArgs> {
        val depth = when (val d = intIn(args, "maxDepth", 0, MAX_DEPTH, DEFAULT_DEPTH)) {
            is Parsed.Bad -> return d
            is Parsed.Ok -> d.value
        }
        val nodes = when (val n = intIn(args, "maxNodes", 1, MAX_NODES, DEFAULT_NODES)) {
            is Parsed.Bad -> return n
            is Parsed.Ok -> n.value
        }
        return Parsed.Ok(TreeArgs(depth, nodes))
    }

    private val NODE_ID = Regex("^([0-9]{1,15}):([0-9]{1,4})$")

    fun parseNodeId(id: String): NodeRef? {
        if (id.length > NODE_ID_MAX) return null
        val m = NODE_ID.matchEntire(id) ?: return null
        val gen = m.groupValues[1].toLongOrNull() ?: return null
        val index = m.groupValues[2].toIntOrNull() ?: return null
        if (index >= MAX_NODES) return null
        return NodeRef(gen, index)
    }

    /** `{nodeId, action, text?}`; `text` (0 to [SET_TEXT_MAX] characters) is required for `setText` only. */
    fun parsePerform(args: JsonObject): Parsed<PerformArgs> {
        val idp = args["nodeId"] as? JsonPrimitive
        if (idp == null || !idp.isString) return Parsed.Bad("nodeId must be a string from a11y.windowTree")
        val ref = parseNodeId(idp.content) ?: return Parsed.Bad("nodeId is not one a11y.windowTree returned")
        val ap = args["action"] as? JsonPrimitive
        val action = ap?.takeIf { it.isString }?.contentOrNull?.let { Action.of(it) }
            ?: return Parsed.Bad("action must be one of ${Action.entries.joinToString(", ") { it.wire }}")
        val tp = args["text"]
        val text = if (action == Action.SET_TEXT) {
            val p = tp as? JsonPrimitive
            if (p == null || !p.isString) return Parsed.Bad("setText needs text")
            if (p.content.length > SET_TEXT_MAX) return Parsed.Bad("text must be at most $SET_TEXT_MAX characters")
            p.content
        } else {
            if (tp != null && tp !is JsonNull) return Parsed.Bad("text is only for setText")
            null
        }
        return Parsed.Ok(PerformArgs(ref, action, text))
    }

    /** `{path: [[x, y], …], durationMs?}`: 1 to [MAX_POINTS] points in screen pixels, 1 to [MAX_DURATION_MS] ms. */
    fun parseGesture(args: JsonObject): Parsed<GestureArgs> {
        val path = args["path"] as? JsonArray ?: return Parsed.Bad("path must be an array of [x, y] points")
        if (path.isEmpty() || path.size > MAX_POINTS) return Parsed.Bad("path must have 1 to $MAX_POINTS points")
        val points = ArrayList<Pair<Float, Float>>(path.size)
        for (p in path) {
            val pair = p as? JsonArray
            if (pair == null || pair.size != 2) return Parsed.Bad("each point must be [x, y]")
            val xy = pair.map { (it as? JsonPrimitive)?.takeIf { v -> !v.isString }?.doubleOrNull }
            val x = xy[0]
            val y = xy[1]
            if (x == null || y == null || !x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x > MAX_COORD || y > MAX_COORD) {
                return Parsed.Bad("x and y must be numbers from 0 to ${MAX_COORD.toInt()}")
            }
            points += x.toFloat() to y.toFloat()
        }
        val duration = when (val d = intIn(args, "durationMs", 1, MAX_DURATION_MS, DEFAULT_DURATION_MS)) {
            is Parsed.Bad -> return d
            is Parsed.Ok -> d.value
        }
        return Parsed.Ok(GestureArgs(points, duration.toLong()))
    }

    fun parseGlobal(args: JsonObject): Parsed<Global> {
        val p = args["action"] as? JsonPrimitive
        val g = p?.takeIf { it.isString }?.contentOrNull?.let { Global.of(it) }
            ?: return Parsed.Bad("action must be one of ${Global.entries.joinToString(", ") { it.wire }}")
        return Parsed.Ok(g)
    }

    // ---- system switch -----------------------------------------------------------------------

    /**
     * Whether Android's `enabled_accessibility_services` setting ([setting], `:`-separated
     * `package/class` entries, class possibly starting with `.`) turns on exactly the service
     * [className] of [pkg].
     */
    fun enabledIn(setting: String?, pkg: String, className: String): Boolean =
        setting.orEmpty().split(':').any { entry ->
            val slash = entry.indexOf('/')
            if (slash <= 0) return@any false
            val p = entry.substring(0, slash).trim()
            var c = entry.substring(slash + 1).trim()
            if (c.startsWith(".")) c = p + c
            p == pkg && c == className
        }

    /** [s] trimmed and cut to [max] characters (never in the middle of a surrogate pair); blank is null. */
    fun cap(s: String?, max: Int): String? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.length <= max) return t
        val end = if (t[max - 1].isHighSurrogate()) max - 1 else max
        return t.substring(0, end)
    }
}
