package app.deft.ui.agent

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.deft.DeftApp
import app.deft.agent.PreviewRun
import app.deft.catalog.Capability
import app.deft.webview.VerifyScript
import app.deft.workspace.MyAppsStore
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the agent Preview's `ask` banner on the emulator: expanded banner, Skip -> note -> Send, Back,
 * Hide question -> bubble (top-right) -> expand with drafts kept, and the outcome delivered to the run.
 * Finds nodes through the accessibility tree; screenshots go to /data/local/tmp and are pulled by the caller.
 */
@RunWith(AndroidJUnit4::class)
class PreviewAskBannerDeviceTest {
    private val slug = "deft-preview-ask"
    private val store get() = DeftApp.instance.myApps
    private val webAppId get() = MyAppsStore.webAppId(slug)
    private val inst get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setup() {
        runBlocking { wipeProject() }
        val dir = store.projectDir(slug).also { it.mkdirs() }
        File(dir, "index.html").writeText("<!DOCTYPE html><html><body><p>counter 2</p></body></html>")
        File(dir, "manifest.json").writeText(
            """{"deft":1,"id":"$slug","name":"$slug","version":"0.1.0","profile":"myapp","start_url":"./index.html","capabilities":[],"network":[]}""" + "\n",
        )
        runBlocking {
            val repo = DeftApp.instance.repository
            repo.ensureWorkspaceApp(slug)
            val none = emptyList<Capability>()
            repo.applyWorkspaceConsent(slug, none, none)
        }
    }

    @After
    fun teardown() {
        PreviewLink.ask.value?.let { PreviewLink.endAsk(it) }
        runBlocking { wipeProject() }
    }

    @Test
    fun bannerHideBubbleDraftsAndAnswer() {
        launch().use {
            val ask = PreviewLink.startAsk(slug, VerifyScript.AskSpec("What number is under the dial?", input = "number"))
            assertNotNull("ask started", ask)
            assertTrue("banner shows", waitUntil { has("The agent asks") && has("What number is under the dial?") })
            assertTrue("Skip shown", has("Skip"))
            setText("4")
            shot("deft-ask-expanded.png")

            click("Hide question")
            assertTrue("banner gone when hidden", waitUntil { !has("The agent asks") })
            assertTrue("bubble shown", waitUntil { bubble() != null })
            val b = bubbleBounds()
            val m = inst.targetContext.resources.displayMetrics
            assertTrue("bubble at top-right: $b", b.centerX() > m.widthPixels * 0.75f && b.centerY() < m.heightPixels * 0.25f)
            assertTrue("bubble not answered", ask!!.answer.isActive)
            shot("deft-ask-collapsed.png")

            click("Show the agent's question")
            assertTrue("banner back on tap", waitUntil { has("The agent asks") })
            assertTrue("bubble removed on expand", waitUntil { bubble() == null })
            assertEquals("draft kept after collapse", "4", editableText())

            click("Send")
            assertTrue("answer delivered", waitUntil { ask!!.answer.isCompleted })
            PreviewLink.endAsk(ask!!) // what PreviewRun.ask does in its finally
            assertEquals(AskAnswer.Answered("4"), ask!!.answer.getCompleted())
            assertTrue("banner removed after answer", waitUntil { !has("The agent asks") && bubble() == null })
            assertNull("ask cleared", PreviewLink.ask.value)
        }
    }

    @Test
    fun skipNoteBackAndDoneViaBubble() {
        launch().use {
            val first = PreviewLink.startAsk(slug, VerifyScript.AskSpec("Is the header ember now?", listOf("Yes", "No"), expect = "Yes"))
            assertNotNull(first)
            assertTrue("options shown", waitUntil { has("Yes") && has("No") && has("Skip") })

            click("Skip")
            assertTrue("note field shown", waitUntil { has("Back") && has("Send") && !has("Yes") })
            click("Back")
            assertTrue("options back after Back", waitUntil { has("Yes") && !has("Back") })

            click("Skip")
            assertTrue("note field again", waitUntil { has("Back") })
            setText("header looks grey")
            click("Hide question")
            assertTrue("bubble after hiding while skipping", waitUntil { bubble() != null })
            click("Show the agent's question")
            assertTrue("note draft kept", waitUntil { editableText() == "header looks grey" })
            click("Send")
            assertTrue("skip delivered", waitUntil { first!!.answer.isCompleted })
            PreviewLink.endAsk(first!!)
            assertEquals(AskAnswer.Skipped("header looks grey"), first!!.answer.getCompleted())
            assertTrue("banner gone after skip", waitUntil { !has("The agent asks") })

            val second = PreviewLink.startAsk(slug, VerifyScript.AskSpec("Tilt the phone left"))
            assertNotNull(second)
            assertTrue("plain ask shows Done", waitUntil { has("Done") && has("Skip") })
            click("Hide question")
            assertTrue("bubble for plain ask", waitUntil { bubble() != null })
            click("Show the agent's question")
            assertTrue("Done back", waitUntil { has("Done") })
            click("Done")
            assertTrue("done delivered", waitUntil { second!!.answer.isCompleted })
            PreviewLink.endAsk(second!!)
            assertEquals(AskAnswer.Done, second!!.answer.getCompleted())
            assertTrue("bubble gone after answer", waitUntil { bubble() == null })
        }
    }

    @Test
    fun runOutcomeMatchesAnswer() {
        launch().use {
            val steps = VerifyScript.parseSteps(
                Json.parseToJsonElement("""[{"ask":"Is the header ember now?","options":["Yes","No"],"expect":"Yes"}]"""),
                VerifyScript.Mode.PREVIEW,
            )!!
            var report: String? = null
            val run = Thread { report = runBlocking { PreviewRun.run(slug, steps) } }
            run.start()
            assertTrue("ask shown for run", waitUntil { PreviewLink.ask.value != null })
            click("No")
            run.join(20_000)
            assertTrue("run finished", report != null)
            assertTrue("fail reason: $report", report!!.contains("user answered \"No\", expected \"Yes\""))

            val skipSteps = VerifyScript.parseSteps(
                Json.parseToJsonElement("""[{"ask":"Is the header ember now?","options":["Yes","No"]}]"""),
                VerifyScript.Mode.PREVIEW,
            )!!
            var skipReport: String? = null
            val skipRun = Thread { skipReport = runBlocking { PreviewRun.run(slug, skipSteps) } }
            skipRun.start()
            assertTrue("ask shown for skip run", waitUntil { PreviewLink.ask.value != null })
            click("Skip")
            assertTrue("note field", waitUntil { has("Send") })
            setText("nope")
            click("Send")
            skipRun.join(20_000)
            assertTrue("skip report: $skipReport", skipReport?.contains("user tapped Skip") == true && skipReport!!.contains("\"nope\""))
        }
    }

    private fun launch(): ActivityScenario<PreviewHostTestActivity> {
        val ctx = inst.targetContext
        val intent = android.content.Intent(ctx, PreviewHostTestActivity::class.java)
            .putExtra(PreviewHostTestActivity.EXTRA_SLUG, slug)
        val scenario = ActivityScenario.launch<PreviewHostTestActivity>(intent)
        assertTrue("preview page attached", waitUntil(20_000) { PreviewLink.live(slug) != null })
        assertTrue("resumed", scenario.state == Lifecycle.State.RESUMED)
        Thread.sleep(500)
        return scenario
    }

    // --- UI helpers (accessibility tree) ---

    private fun root(): AccessibilityNodeInfo? = inst.uiAutomation.rootInActiveWindow

    private fun nodes(pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (pred(n)) out += n
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root())
        return out
    }

    private fun has(label: String): Boolean = nodes { it.text?.toString() == label || it.contentDescription?.toString() == label }.isNotEmpty()

    private fun bubble(): AccessibilityNodeInfo? =
        nodes { it.contentDescription?.toString() == "Show the agent's question" }.firstOrNull()?.let { clickableOf(it) }

    private fun bubbleBounds(): Rect = Rect().also { bubble()!!.getBoundsInScreen(it) }

    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur: AccessibilityNodeInfo = n
        while (!cur.isClickable) cur = cur.parent ?: return n
        return cur
    }

    private fun click(label: String) {
        val target = nodes { it.text?.toString() == label || it.contentDescription?.toString() == label }
            .map { clickableOf(it) }
            .firstOrNull { it.isClickable }
        assertNotNull("clickable '$label'", target)
        assertTrue("click '$label'", target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(300)
    }

    private fun editable(): AccessibilityNodeInfo? = nodes { it.isEditable }.firstOrNull()

    private fun editableText(): String? = editable()?.text?.toString()

    private fun setText(value: String) {
        val field = editable()
        assertNotNull("text field", field)
        field!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        assertTrue("set text", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        Thread.sleep(300)
    }

    private fun waitUntil(timeoutMs: Long = 10_000, ok: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ok()) return true
            Thread.sleep(200)
        }
        return ok()
    }

    private fun shot(name: String) {
        inst.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/$name").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
    }

    private suspend fun wipeProject() {
        if (store.hasProject(slug)) runCatching { store.deleteProject(slug) }
        runCatching { store.projectDir(slug).deleteRecursively() }
    }
}
