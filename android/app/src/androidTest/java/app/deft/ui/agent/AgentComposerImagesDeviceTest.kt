package app.deft.ui.agent

import android.graphics.Bitmap
import android.graphics.Color
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.deft.agent.AgentAttachment
import app.deft.agent.AgentAttachments
import app.deft.agent.AgentLine
import app.deft.ui.theme.AppDeftTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the agent composer's attachments on the emulator: the "Attach files" button, the preview row with its
 * "Remove attachment" buttons (image thumbnails, text and PDF tiles), the attachment cap, and the send action with
 * an empty draft.
 * AgentPanel is hosted in [PreviewHostTestActivity] (debug manifest) by replacing its content after launch, so no
 * extra activity is needed. Finds nodes through the accessibility tree; the screenshot goes to /data/local/tmp.
 */
@RunWith(AndroidJUnit4::class)
class AgentComposerImagesDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<PreviewHostTestActivity>? = null

    @After
    fun teardown() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun attachButtonAndRemoveImages() {
        val removed = AtomicLong(-1)
        val attachments = listOf(image(1, Color.RED, 160, 120), image(2, Color.BLUE, 120, 160))
        show(attachments = attachments, onRemoveAttachment = { removed.set(it) })

        assertTrue("Attach images shown", waitUntil { has("Attach files") })
        assertTrue("Attach images enabled", enabled("Attach files"))
        assertEquals("two Remove attachment buttons", 2, count("Remove attachment"))
        shot("composer-images.png")

        click("Remove attachment")
        assertTrue("onRemoveAttachment called with first id", waitUntil { removed.get() == 1L })
        assertEquals(1L, removed.get())
    }

    @Test
    fun textAndPdfTilesInThePreviewRow() {
        val removed = AtomicLong(-1)
        val notes = AgentAttachment.Text(id = 11, name = "notes.md", text = "todo", truncated = false)
        val report = AgentAttachment.Pdf(id = 12, name = "report.pdf", dataUrl = "data:application/pdf;base64,QQ==", bytes = 1)
        show(attachments = listOf(notes, report), onRemoveAttachment = { removed.set(it) })

        assertTrue("Attach images shown", waitUntil { has("Attach files") })
        assertEquals("two Remove attachment buttons", 2, count("Remove attachment"))
        assertTrue("text tile shows its name", waitUntil { hasNodeText("notes.md") })
        assertTrue("PDF tile shows its name", hasNodeText("report.pdf"))
        shot("composer-files.png")

        click("Remove attachment")
        assertTrue("onRemoveAttachment called with the text file's id", waitUntil { removed.get() == 11L })
    }

    @Test
    fun attachMenuOffersPhotosAndFiles() {
        show(attachments = listOf(image(1, Color.RED, 160, 120)))
        assertTrue("Attach images shown", waitUntil { has("Attach files") })
        val ua = inst.uiAutomation
        ua.serviceInfo = ua.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        click("Attach files")
        Thread.sleep(800)
        shot("composer-menu.png")
        assertTrue("Photos item shown", waitUntil { hasText("Photos") })
        assertTrue("Files item shown", hasText("Files"))
        inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }

    @Test
    fun attachDisabledAtCap() {
        val attachments = (1L..AgentAttachments.MAX_ATTACHMENTS.toLong()).map { image(it, Color.GREEN, 100, 100) }
        assertEquals(4, attachments.size)
        show(attachments = attachments)

        assertTrue("Attach images shown", waitUntil { has("Attach files") })
        assertFalse("Attach images disabled with 4 attachments", enabled("Attach files"))
        assertEquals("four Remove attachment buttons", 4, count("Remove attachment"))
    }

    @Test
    fun sendWithImagesAndEmptyDraft() {
        val sent = AtomicInteger(0)
        show(attachments = listOf(image(7, Color.MAGENTA, 90, 90)), draft = "", onSend = { sent.incrementAndGet() })

        assertTrue("Send shown with empty draft and an image", waitUntil { has("Send") })
        click("Send")
        assertTrue("onSend called", waitUntil { sent.get() == 1 })
        assertEquals(1, sent.get())
    }

    // --- host ---

    private fun show(
        attachments: List<AgentAttachment> = emptyList(),
        draft: String = "",
        onSend: () -> Unit = {},
        onRemoveAttachment: (Long) -> Unit = {},
    ) {
        val ctx = inst.targetContext
        val intent = android.content.Intent(ctx, PreviewHostTestActivity::class.java)
            .putExtra(PreviewHostTestActivity.EXTRA_SLUG, "deft-composer-images")
        val sc = ActivityScenario.launch<PreviewHostTestActivity>(intent)
        scenario = sc
        assertTrue("resumed", sc.state == Lifecycle.State.RESUMED)
        val red = solid(Color.RED, 160, 120)
        val blue = solid(Color.BLUE, 120, 160)
        val lines = listOf(
            AgentLine(
                id = "u1",
                role = "user",
                text = "What is in these?",
                attachments = listOf(picture(20, "red.png", red), picture(21, "blue.png", blue)),
            ),
            AgentLine(id = "a1", role = "assistant", text = "A red square and a blue square."),
        )
        sc.onActivity { activity ->
            activity.setContent {
                AppDeftTheme {
                    AgentPanel(
                        hasKey = true,
                        keyDraft = "",
                        onKeyDraft = {},
                        onSaveKey = {},
                        lines = lines,
                        draft = draft,
                        onDraft = {},
                        busy = false,
                        pending = emptyList(),
                        error = null,
                        onSend = onSend,
                        attachments = attachments,
                        onPickAttachments = {},
                        onRemoveAttachment = onRemoveAttachment,
                        onApply = {},
                        onReject = {},
                        onStop = {},
                        onClose = {},
                    )
                }
            }
        }
        Thread.sleep(500)
    }

    private fun image(id: Long, color: Int, w: Int, h: Int): AgentAttachment.Image =
        picture(id, "image-$id.png", solid(color, w, h))

    private fun picture(id: Long, name: String, thumb: ImageBitmap): AgentAttachment.Image =
        AgentAttachment.Image(id = id, name = name, dataUrl = "data:image/jpeg;base64,AA", thumb = thumb)

    private fun solid(color: Int, w: Int, h: Int): ImageBitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap()

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

    private fun described(label: String) = nodes { it.contentDescription?.toString() == label }

    private fun has(label: String): Boolean = described(label).isNotEmpty()

    private fun hasText(label: String): Boolean = inst.uiAutomation.windows.any { w ->
        var found = false
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || found) return
            if (n.text?.toString() == label) found = true
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(w.root)
        found
    }

    private fun count(label: String): Int = described(label).size

    private fun hasNodeText(label: String): Boolean = nodes { it.text?.toString() == label }.isNotEmpty()

    /** Enabled state of the clickable control that carries [label]. */
    private fun enabled(label: String): Boolean {
        val n = described(label).firstOrNull() ?: return false
        var cur: AccessibilityNodeInfo = n
        while (!cur.isClickable) cur = cur.parent ?: return n.isEnabled
        return cur.isEnabled
    }

    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur: AccessibilityNodeInfo = n
        while (!cur.isClickable) cur = cur.parent ?: return n
        return cur
    }

    private fun click(label: String) {
        val target = described(label).map { clickableOf(it) }.firstOrNull { it.isClickable }
        assertNotNull("clickable '$label'", target)
        assertTrue("click '$label'", target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
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
}
