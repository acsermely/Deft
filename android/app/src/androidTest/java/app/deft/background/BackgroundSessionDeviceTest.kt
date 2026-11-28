package app.deft.background

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.deft.bridge.DeftJsBridge
import app.deft.catalog.Capability
import app.deft.data.InstalledWebApp
import app.deft.webview.CspPolicy
import app.deft.webview.LocalOriginPathHandler
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A background module runs in its off-screen page, gets the trigger, and the page is
 * destroyed when the budget runs out. Runs without the launcher's database or service.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundSessionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun moduleRunsAndIsDestroyedAfterTheBudget() {
        assumeTrue("WebView message listener required", DeftJsBridge.webViewSupported())
        val dir = appDir()
        val outcome = AtomicReference<String?>(null)
        val ended = CountDownLatch(1)
        lateinit var session: BackgroundSession
        instrumentation.runOnMainSync {
            session = BackgroundSession(
                context = context.applicationContext,
                app = app(),
                assets = LocalOriginPathHandler.loader(dir, HOST),
                policy = CspPolicy(),
                grantedPrefixes = setOf(Capability.BACKGROUND.id),
                service = null,
                budgetMs = BUDGET_MS,
                debugSlug = null,
            ) { _, result ->
                outcome.set(result)
                ended.countDown()
            }
            assertTrue(session.start(BackgroundTrigger("manual", """{"n":7}""", System.currentTimeMillis())))
        }
        val t0 = System.currentTimeMillis()
        // The module saw the trigger and its payload.
        var seen: String? = null
        while (System.currentTimeMillis() - t0 < BUDGET_MS - 500 && seen?.contains("manual:7") != true) {
            seen = eval(session, "String(globalThis.__bgTrigger)")
            Thread.sleep(100)
        }
        assertEquals("\"manual:7\"", seen)
        assertFalse(session.ended)
        // Nothing calls background.done (no service here), so the budget ends the run.
        assertTrue("run not ended after the budget", ended.await(BUDGET_MS + 5_000, TimeUnit.MILLISECONDS))
        assertTrue(System.currentTimeMillis() - t0 >= BUDGET_MS - 500)
        assertEquals(BackgroundRunner.OUTCOME_TIMEOUT, outcome.get())
        assertTrue(session.ended)
        assertNull("page must be destroyed", eval(session, "1"))
        dir.deleteRecursively()
    }

    @Test
    fun endIsIdempotentAndReportsOnce() {
        assumeTrue("WebView message listener required", DeftJsBridge.webViewSupported())
        val dir = appDir()
        val calls = mutableListOf<String>()
        instrumentation.runOnMainSync {
            val session = BackgroundSession(
                context.applicationContext, app(), LocalOriginPathHandler.loader(dir, HOST), CspPolicy(),
                setOf(Capability.BACKGROUND.id), null, BUDGET_MS, null,
            ) { _, result -> calls += result }
            assertTrue(session.start(BackgroundTrigger("manual", "{}", 0)))
            session.end(BackgroundRunner.OUTCOME_CANCELLED)
            session.end(BackgroundRunner.OUTCOME_OK)
        }
        Thread.sleep(BUDGET_MS + 1_000)
        assertEquals(listOf(BackgroundRunner.OUTCOME_CANCELLED), calls)
        dir.deleteRecursively()
    }

    private fun eval(session: BackgroundSession, js: String): String? {
        val result = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            session.evaluate(js) {
                result.set(it)
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        return result.get()
    }

    private fun app() = InstalledWebApp(
        id = "bg-test",
        name = "Bg test",
        startUrl = "https://$HOST/index.html",
        origin = "https://$HOST",
        manifestUrl = "",
        requiredCapabilities = listOf(Capability.BACKGROUND),
        filesAllAccess = false,
        filesTreeUri = null,
        confirmBiometric = false,
        workspaceSlug = null,
        originId = "bgtest",
    )

    private fun appDir(): File {
        val dir = File(context.cacheDir, "bg-test-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "manifest.json").writeText(
            """{"deft":1,"id":"bg-test","name":"Bg test","version":"1.0.0",
              "capabilities":[{"id":"background","reason":"Runs the test module when triggered"}],
              "background":{"module":"./bg.js","triggers":["periodic:15"]}}""",
        )
        File(dir, "index.html").writeText("<!doctype html><title>x</title>")
        File(dir, "bg.js").writeText(
            """
            deftBridge.subscribe('background.trigger', function (e) {
              globalThis.__bgTrigger = e.trigger + ':' + (e.payload && e.payload.n);
            });
            """.trimIndent(),
        )
        return dir
    }

    companion object {
        private const val HOST = "bgtest.app.deft.internal"
        private const val BUDGET_MS = 4_000L
    }
}
