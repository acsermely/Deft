package app.deft.ui.agent

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.deft.DeftApp
import app.deft.bridge.HostSurfaces
import app.deft.catalog.Capability
import app.deft.workspace.MyAppsStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The agent's Preview is the app's screen ([HostSurfaces]) while it shows and the activity is resumed. */
@RunWith(AndroidJUnit4::class)
class PreviewHostSurfaceDeviceTest {
    private val slug = "deft-preview-surface"
    private val store get() = DeftApp.instance.myApps
    private val webAppId get() = MyAppsStore.webAppId(slug)

    @Before
    fun setup() {
        runBlocking { wipeProject() }
        val dir = store.projectDir(slug).also { it.mkdirs() }
        File(dir, "index.html").writeText("<!DOCTYPE html><html><body><p>preview</p></body></html>")
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
        runBlocking { wipeProject() }
    }

    @Test
    fun registeredWhileShownAndResumed() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = android.content.Intent(ctx, PreviewHostTestActivity::class.java)
            .putExtra(PreviewHostTestActivity.EXTRA_SLUG, slug)
        ActivityScenario.launch<PreviewHostTestActivity>(intent).use { scenario ->
            assertTrue("registered while shown", waitFor { HostSurfaces.forApp(webAppId)?.isPreview == true })

            scenario.moveToState(Lifecycle.State.STARTED)
            assertTrue("gone while paused", waitFor { HostSurfaces.forApp(webAppId) == null })

            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue("back when resumed", waitFor { HostSurfaces.forApp(webAppId)?.isPreview == true })

            scenario.onActivity { it.shown = false }
            assertTrue("gone when hidden", waitFor { HostSurfaces.forApp(webAppId) == null })
        }
        assertNull(HostSurfaces.forApp(webAppId))
    }

    private fun waitFor(timeoutMs: Long = 10_000, ok: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var result = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { result = ok() }
            if (result) return true
            Thread.sleep(100)
        }
        return false
    }

    private suspend fun wipeProject() {
        if (store.hasProject(slug)) runCatching { store.deleteProject(slug) }
        runCatching { store.projectDir(slug).deleteRecursively() }
    }
}
