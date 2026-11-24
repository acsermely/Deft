package app.deft.webview

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.deft.DeftApp
import app.deft.catalog.Capability
import app.deft.ui.host.WebViewHostActivity
import app.deft.workspace.MyAppsStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyAppPreviewDebugSmokeTest {
    private val slug = "deft-smoke"
    private val store get() = DeftApp.instance.myApps

    @Before
    fun setup() {
        MyAppDebugLog.resetForTests()
        runBlocking { wipeProject() }
    }

    @After
    fun teardown() {
        runBlocking { wipeProject() }
        MyAppDebugLog.resetForTests()
    }

    @Test
    fun verifyReportsMissingJsAndCssNotFavicon() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, slug).apply {
            deleteRecursively()
            mkdirs()
        }
        writeBrokenApp(dir)
        val dump = runBlocking {
            AppVerifier.verify(
                InstrumentationRegistry.getInstrumentation().targetContext,
                slug,
                dir,
                "./",
            )
        }
        assertTrue("verify should flag issues:\n$dump", dump.startsWith("issues"))
        assertTrue("missing JS:\n$dump", dump.contains("store.js"))
        assertTrue("missing CSS:\n$dump", dump.contains("missing.css"))
        assertFalse("favicon should be skipped:\n$dump", dump.contains("favicon.ico"))
    }

    @Test
    fun previewDebugCapturesConsoleAndFailedLoads() {
        writeBrokenApp(store.projectDir(slug).also { it.mkdirs() })
        File(store.projectDir(slug), "manifest.json").writeText(
            """{"deft":1,"id":"deft-smoke","name":"deft-smoke","version":"0.1.0","profile":"myapp","start_url":"./index.html","capabilities":[],"network":[]}""" + "\n",
        )
        runBlocking {
            val repo = DeftApp.instance.repository
            repo.ensureWorkspaceApp(slug)
            val caps = emptyList<Capability>()
            repo.applyWorkspaceConsent(slug, caps, caps)
        }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<WebViewHostActivity>(
            WebViewHostActivity.intent(ctx, MyAppsStore.webAppId(slug)),
        ).use {
            val dump = waitForDump { it.contains("smoke-console") && it.contains("store.js") }
            assertTrue("console.error:\n$dump", dump.contains("smoke-console"))
            assertTrue("missing JS:\n$dump", dump.contains("store.js"))
            assertTrue("missing CSS:\n$dump", dump.contains("missing.css"))
            assertFalse("favicon should be skipped:\n$dump", dump.contains("favicon.ico"))
        }
    }

    private fun waitForDump(ok: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + 12_000
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            last = MyAppDebugLog.dump(slug)
            if (ok(last)) return last
            Thread.sleep(200)
        }
        return last
    }

    private fun writeBrokenApp(dir: File) {
        dir.mkdirs()
        File(dir, "index.html").writeText(
            """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
              <link rel="stylesheet" href="./missing.css" />
              <link rel="icon" href="./favicon.ico" />
              <script type="module" src="./store.js"></script>
              <script type="module" src="./main.js"></script>
            </head>
            <body><div id="app"></div></body>
            </html>
            """.trimIndent(),
        )
        File(dir, "styles.css").writeText("body{margin:0}")
        File(dir, "main.js").writeText("console.error('smoke-console');\n")
        File(dir, "manifest.json").writeText(
            """{"deft":1,"id":"deft-smoke","name":"deft-smoke","version":"0.1.0","profile":"myapp","start_url":"./index.html","capabilities":[],"network":[]}""",
        )
    }

    private suspend fun wipeProject() {
        if (store.hasProject(slug)) {
            runCatching { store.deleteProject(slug) }
        }
        runCatching { store.projectDir(slug).deleteRecursively() }
    }

}
