package app.deft.bridge

import app.deft.background.BackgroundPolicy
import app.deft.bridge.A11ySpec as S
import app.deft.catalog.BridgeCatalog
import app.deft.catalog.Capability
import app.deft.catalog.Risk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class A11ySpecTest {
    private fun obj(json: String): JsonObject = BridgeJson.parseToJsonElement(json).jsonObject

    private val own = "app.deft.debug"
    private val ns = S.LAUNCHER_NAMESPACE

    private fun ok(p: S.Parsed<*>): Any? = (p as? S.Parsed.Ok<*>)?.value ?: error("expected Ok, got $p")

    private fun isBad(p: S.Parsed<*>) = p is S.Parsed.Bad

    // ---- capability wiring -------------------------------------------------------------------

    @Test
    fun capabilityIsSpecialExclusivePersistentAndLive() {
        val cap = Capability.ACCESSIBILITY
        assertEquals("accessibility", cap.id)
        assertEquals(Risk.SPECIAL, cap.risk)
        assertEquals(S.GROUP, cap.exclusiveGroup)
        assertTrue(cap.persistentWhileActive)
        assertTrue(cap.available)
        for (m in listOf("a11y.windowTree", "a11y.perform", "a11y.gesture", "a11y.global")) {
            assertEquals(m, cap, BridgeCatalog.capabilityFor(m))
            assertFalse("$m must work in background runs", BackgroundPolicy.needsForeground(m, JsonObject(emptyMap()), cap))
        }
    }

    // ---- blocked screens ---------------------------------------------------------------------

    @Test
    fun grantAndConfirmScreensAreGuarded() {
        for (cls in listOf(
            "ui.consent.ConsentActivity", "ui.confirm.ConfirmActivity", "ui.special.SpecialGrantActivity",
            "ui.handlers.HandlerPickerActivity", "ui.keystore.KeystoreAuthActivity", "ui.dashboard.DashboardActivity",
            "ui.files.TreePickActivity", "ui.files.MediaPickActivity", "ui.usb.UsbPermissionActivity",
        )) {
            assertTrue(cls, S.guardedLauncherActivity("$ns.$cls"))
        }
        for (cls in listOf("ui.home.HomeActivity", "ui.home.MyAppsActivity", "ui.host.WebViewHostActivity")) {
            assertFalse(cls, S.guardedLauncherActivity("$ns.$cls"))
        }
    }

    @Test
    fun readableActivitiesExistAndEveryOtherManifestActivityIsGuarded() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val names = Regex("""<activity\s[^>]*?android:name="\.([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest).map { "$ns.${it.groupValues[1]}" }.toList()
        assertTrue(names.size > 10)
        for (readable in S.READABLE_LAUNCHER_ACTIVITIES) assertTrue("$readable is in the manifest", readable in names)
        val guarded = names.filter { S.guardedLauncherActivity(it) }
        assertEquals(names.size - S.READABLE_LAUNCHER_ACTIVITIES.size, guarded.size)
        assertTrue("$ns.ui.consent.ConsentActivity" in guarded)
    }

    @Test
    fun permissionControllerAndGuardedLauncherAreNotAllowed() {
        assertFalse(S.packageAllowed("com.android.permissioncontroller", own, guardedVisible = false))
        assertFalse(S.packageAllowed("com.google.android.permissioncontroller", own, guardedVisible = false))
        assertFalse(S.packageAllowed(own, own, guardedVisible = true))
        assertTrue(S.packageAllowed(own, own, guardedVisible = false))
        assertTrue(S.packageAllowed("com.example.mail", own, guardedVisible = true))
        assertFalse(S.packageAllowed(null, own, guardedVisible = false))
        assertFalse(S.packageAllowed("", own, guardedVisible = false))
    }

    @Test
    fun installerVpnDialogAndSettingsAreNotAllowed() {
        for (pkg in listOf(
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.vpndialogs", "com.android.settings",
        )) {
            assertFalse(pkg, S.packageAllowed(pkg, own, guardedVisible = false))
            assertNull(pkg, S.shapeEvent(ev(pkg = pkg), own, guardedVisible = false))
        }
    }

    @Test
    fun systemUiActivityWindowsAreApprovalDialogs() {
        // Screen recording consent, USB debugging: SystemUI activities (TYPE_APPLICATION = 1).
        assertTrue(S.systemUiApprovalWindow("com.android.systemui", 1))
        // Status bar, shade, navigation bar: TYPE_SYSTEM = 3.
        assertFalse(S.systemUiApprovalWindow("com.android.systemui", 3))
        assertFalse(S.systemUiApprovalWindow("com.example.mail", 1))
        assertFalse(S.systemUiApprovalWindow(null, 1))
    }

    // ---- events ------------------------------------------------------------------------------

    private fun ev(
        type: Int = 0x1,
        pkg: String? = "com.example.mail",
        cls: String? = "android.widget.Button",
        text: List<String?> = listOf("Send"),
        desc: String? = null,
        password: Boolean = false,
    ) = S.RawEvent(type, pkg, cls, text, desc, password)

    @Test
    fun eventShape() {
        val e = S.shapeEvent(ev(text = listOf("Send", null, " ", "now")), own, false)!!
        assertEquals("viewClicked", e["type"]!!.jsonPrimitive.content)
        assertEquals("com.example.mail", e["package"]!!.jsonPrimitive.content)
        assertEquals("android.widget.Button", e["className"]!!.jsonPrimitive.content)
        assertEquals("Send now", e["text"]!!.jsonPrimitive.content)
        val d = S.shapeEvent(ev(text = emptyList(), desc = "Compose", cls = null), own, false)!!
        assertEquals("Compose", d["text"]!!.jsonPrimitive.content)
        assertNull(d["className"])
        val long = S.shapeEvent(ev(text = listOf("x".repeat(5000))), own, false)!!
        assertEquals(S.TEXT_MAX, long["text"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun passwordEventsHaveNoText() {
        val e = S.shapeEvent(ev(type = 0x10, text = listOf("hunter2"), desc = "hunter2", password = true), own, false)!!
        assertEquals("viewTextChanged", e["type"]!!.jsonPrimitive.content)
        assertNull(e["text"])
        assertFalse(e.toString().contains("hunter2"))
    }

    @Test
    fun blockedEventsAreDropped() {
        assertNull(S.shapeEvent(ev(pkg = "com.android.permissioncontroller"), own, false))
        assertNull(S.shapeEvent(ev(pkg = "com.google.android.permissioncontroller", type = 0x20), own, false))
        // Any launcher window while a guarded screen shows.
        assertNull(S.shapeEvent(ev(pkg = own), own, guardedVisible = true))
        // A guarded launcher screen opening, even before the guard noticed it.
        assertNull(S.shapeEvent(ev(pkg = own, type = 0x20, cls = "$ns.ui.consent.ConsentActivity"), own, false))
        // The launcher's own notifications (other web apps' notify.post).
        assertNull(S.shapeEvent(ev(pkg = own, type = 0x40), own, false))
        // Unknown and unforwarded types.
        assertNull(S.shapeEvent(ev(type = 0x80), own, false))
        // The home screen and web app pages are readable.
        assertNotNull(S.shapeEvent(ev(pkg = own, type = 0x20, cls = "$ns.ui.home.HomeActivity"), own, false))
        assertNotNull(S.shapeEvent(ev(pkg = own), own, false))
    }

    @Test
    fun throttleTenPerSecondNoisyAtMostFive() {
        val t = S.Throttle()
        var sent = 0
        for (i in 0 until 50) if (t.tryAcquire(1000L + i, noisy = false)) sent++
        assertEquals(S.EVENTS_PER_SECOND, sent)
        assertFalse(t.tryAcquire(1999L, noisy = false))
        assertTrue(t.tryAcquire(2000L, noisy = false))

        val n = S.Throttle()
        var noisy = 0
        for (i in 0 until 20) if (n.tryAcquire(10_000L + i, noisy = true)) noisy++
        assertEquals(S.NOISY_PER_SECOND, noisy)
        var other = 0
        for (i in 0 until 20) if (n.tryAcquire(10_100L + i, noisy = false)) other++
        assertEquals(S.EVENTS_PER_SECOND - S.NOISY_PER_SECOND, other)
        n.reset()
        assertTrue(n.tryAcquire(10_200L, noisy = true))
        assertTrue(S.noisy("windowContentChanged"))
        assertFalse(S.noisy("windowStateChanged"))
    }

    @Test
    fun auditHourly() {
        assertTrue(S.auditDue(0L, 5L))
        assertFalse(S.auditDue(1_000L, 1_000L + S.AUDIT_EVERY_MS - 1))
        assertTrue(S.auditDue(1_000L, 1_000L + S.AUDIT_EVERY_MS))
    }

    // ---- nodes -------------------------------------------------------------------------------

    private fun node(
        text: String? = "OK",
        password: Boolean = false,
        checkable: Boolean = false,
    ) = S.RawNode(
        className = "android.widget.EditText",
        text = text,
        contentDescription = null,
        viewId = "com.example:id/field",
        bounds = intArrayOf(1, 2, 3, 4),
        clickable = true,
        editable = true,
        scrollable = false,
        checkable = checkable,
        checked = true,
        focused = false,
        password = password,
    )

    @Test
    fun nodeShape() {
        val n = S.shapeNode(node(), "7:3", "7:0")
        assertEquals("7:3", n["id"]!!.jsonPrimitive.content)
        assertEquals("7:0", n["parentId"]!!.jsonPrimitive.content)
        assertEquals("OK", n["text"]!!.jsonPrimitive.content)
        assertEquals(listOf(1, 2, 3, 4), n["bounds"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals("com.example:id/field", n["viewId"]!!.jsonPrimitive.content)
        assertTrue(n["clickable"]!!.jsonPrimitive.boolean)
        assertNull("checked only on checkable nodes", n["checked"])
        assertNull(n["contentDescription"])
        assertTrue(S.shapeNode(node(checkable = true), "1:0", null)["checked"]!!.jsonPrimitive.boolean)
        assertNull(S.shapeNode(node(), "1:0", null)["parentId"])
    }

    @Test
    fun passwordNodeTextIsNeverReturned() {
        val n = S.shapeNode(node(text = "hunter2", password = true), "1:0", null)
        assertNull(n["text"])
        assertTrue(n["password"]!!.jsonPrimitive.boolean)
        assertFalse(n.toString().contains("hunter2"))
    }

    private data class T(val name: String, val kids: List<T> = emptyList(), val visible: Boolean = true)

    private fun walk(root: T, depth: Int = 30, nodes: Int = 2000) =
        S.walk(root, { it.kids.size }, { n, i -> n.kids[i] }, { it.visible }, depth, nodes)

    @Test
    fun walkOrderParentsDepthAndLimits() {
        val tree = T("r", listOf(T("a", listOf(T("a1"), T("a2"))), T("hidden", listOf(T("h1")), visible = false), T("b")))
        val w = walk(tree)
        assertEquals(listOf("r", "a", "a1", "a2", "b"), w.visits.map { it.node.name })
        assertEquals(listOf(-1, 0, 1, 1, 0), w.visits.map { it.parent })
        assertFalse(w.truncated)

        val shallow = walk(tree, depth = 1)
        assertEquals(listOf("r", "a", "b"), shallow.visits.map { it.node.name })
        assertTrue(shallow.truncated)

        val few = walk(tree, nodes = 2)
        assertEquals(listOf("r", "a"), few.visits.map { it.node.name })
        assertTrue(few.truncated)

        // A deep chain stops at maxDepth.
        var deep = T("leaf")
        repeat(100) { deep = T("n$it", listOf(deep)) }
        assertEquals(S.MAX_DEPTH + 1, walk(deep, depth = S.MAX_DEPTH).visits.size)
        assertTrue(walk(T("x", visible = false)).visits.isEmpty())
    }

    // ---- arguments ---------------------------------------------------------------------------

    @Test
    fun treeArgs() {
        assertEquals(S.TreeArgs(S.DEFAULT_DEPTH, S.DEFAULT_NODES), ok(S.parseTree(obj("{}"))))
        assertEquals(S.TreeArgs(0, 2000), ok(S.parseTree(obj("""{"maxDepth":0,"maxNodes":2000}"""))))
        for (bad in listOf(
            """{"maxDepth":31}""", """{"maxDepth":-1}""", """{"maxDepth":1.5}""", """{"maxDepth":"3"}""",
            """{"maxNodes":0}""", """{"maxNodes":2001}""",
        )) {
            assertTrue(bad, isBad(S.parseTree(obj(bad))))
        }
    }

    @Test
    fun nodeIds() {
        assertEquals(S.NodeRef(12, 3), S.parseNodeId(S.nodeId(12, 3)))
        for (bad in listOf("", "3", ":3", "3:", "a:1", "1:-1", "1:2000", "1:2:3", "1 :2", "9".repeat(30) + ":1")) {
            assertNull(bad, S.parseNodeId(bad))
        }
    }

    @Test
    fun performArgs() {
        val p = ok(S.parsePerform(obj("""{"nodeId":"4:2","action":"click"}"""))) as S.PerformArgs
        assertEquals(S.NodeRef(4, 2), p.node)
        assertEquals(S.Action.CLICK, p.action)
        assertNull(p.text)
        val t = ok(S.parsePerform(obj("""{"nodeId":"4:2","action":"setText","text":""}"""))) as S.PerformArgs
        assertEquals("", t.text)
        for (a in listOf("longClick", "scrollForward", "scrollBackward", "focus")) {
            assertTrue(a, S.parsePerform(obj("""{"nodeId":"1:0","action":"$a"}""")) is S.Parsed.Ok)
        }
        for (bad in listOf(
            """{"nodeId":4,"action":"click"}""",
            """{"nodeId":"x","action":"click"}""",
            """{"nodeId":"1:0","action":"tap"}""",
            """{"nodeId":"1:0"}""",
            """{"nodeId":"1:0","action":"setText"}""",
            """{"nodeId":"1:0","action":"setText","text":5}""",
            """{"nodeId":"1:0","action":"click","text":"a"}""",
            """{"nodeId":"1:0","action":"setText","text":"${"a".repeat(S.SET_TEXT_MAX + 1)}"}""",
        )) {
            assertTrue(bad.take(80), isBad(S.parsePerform(obj(bad))))
        }
    }

    @Test
    fun gestureArgs() {
        val tap = ok(S.parseGesture(obj("""{"path":[[10,20]]}"""))) as S.GestureArgs
        assertEquals(listOf(10f to 20f), tap.points)
        assertEquals(S.DEFAULT_DURATION_MS.toLong(), tap.durationMs)
        val swipe = ok(S.parseGesture(obj("""{"path":[[0,0],[100.5,200]],"durationMs":10000}"""))) as S.GestureArgs
        assertEquals(2, swipe.points.size)
        assertEquals(10_000L, swipe.durationMs)
        val fifty = (1..50).joinToString(",") { "[$it,$it]" }
        assertTrue(S.parseGesture(obj("""{"path":[$fifty]}""")) is S.Parsed.Ok)
        val fiftyOne = (1..51).joinToString(",") { "[$it,$it]" }
        for (bad in listOf(
            """{"path":[]}""", """{"path":[$fiftyOne]}""", """{"path":"0,0"}""", """{}""",
            """{"path":[[1]]}""", """{"path":[[1,2,3]]}""", """{"path":[[-1,2]]}""", """{"path":[["1",2]]}""",
            """{"path":[[1,200001]]}""", """{"path":[[1,2]],"durationMs":0}""", """{"path":[[1,2]],"durationMs":10001}""",
            """{"path":[[1,2]],"durationMs":1.5}""", """{"path":[1,2]}""",
        )) {
            assertTrue(bad.take(80), isBad(S.parseGesture(obj(bad))))
        }
    }

    @Test
    fun globalArgs() {
        for (g in S.Global.entries) assertEquals(g, ok(S.parseGlobal(obj("""{"action":"${g.wire}"}"""))))
        for (bad in listOf("""{"action":"power"}""", """{"action":1}""", """{}""", """{"action":"Back"}""")) {
            assertTrue(bad, isBad(S.parseGlobal(obj(bad))))
        }
    }

    @Test
    fun bridgeArgsLimits() {
        assertNull(BridgeArgs.validate("a11y.perform", obj("""{"nodeId":"1:0","action":"click"}""")))
        assertNotNull(BridgeArgs.validate("a11y.perform", obj("""{"action":"click"}""")))
        val many = (1..51).joinToString(",") { "[$it,$it]" }
        assertNotNull(BridgeArgs.validate("a11y.gesture", obj("""{"path":[$many]}""")))
        assertNotNull(BridgeArgs.validate("a11y.global", obj("{}")))
    }

    // ---- system switch -----------------------------------------------------------------------

    @Test
    fun exactServiceComponentIsChecked() {
        val cls = "app.deft.a11y.DeftAccessibilityService"
        assertTrue(S.enabledIn("com.x/.Y:$own/$cls", own, cls))
        assertTrue(S.enabledIn("app.deft/.a11y.DeftAccessibilityService", "app.deft", cls))
        assertFalse(S.enabledIn("$own/app.deft.Other", own, cls))
        assertFalse(S.enabledIn("app.deft/$cls", own, cls))
        assertFalse(S.enabledIn(null, own, cls))
        assertFalse(S.enabledIn("", own, cls))
        assertFalse(S.enabledIn("/$cls", own, cls))
    }
}
