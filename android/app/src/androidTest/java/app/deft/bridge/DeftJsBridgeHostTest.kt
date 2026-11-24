package app.deft.bridge

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeftJsBridgeHostTest {
    @Test
    fun firstLoadAndInterceptedDocument() {
        assumeSupported()
        launch().use { scenario ->
            val state = waitForTest(scenario, timeoutMs = 8_000)
            assertTrue(state.optBoolean("hasBridge"))
            assertTrue(state.optBoolean("hasPort"))
            assertEquals(true, state.opt("ping"))
            assertEquals("bridge_not_ready", state.optString("invokeCode"))
        }
    }

    @Test
    fun delayedReadyQueuesPing() {
        assumeSupported()
        val t0 = System.currentTimeMillis()
        launch(delayMs = 1_200).use { scenario ->
            val state = waitForTest(scenario, timeoutMs = 8_000)
            assertTrue(System.currentTimeMillis() - t0 >= 1_000)
            assertEquals(true, state.opt("ping"))
            assertEquals("", state.optString("error"))
        }
    }

    @Test
    fun emptyGrantsRejectInvoke() {
        assumeSupported()
        launch(allowed = "").use { scenario ->
            val state = waitForTest(scenario)
            assertEquals(true, state.opt("ping"))
            assertEquals("not_granted", state.optString("invokeCode"))
        }
    }

    @Test
    fun otherOriginHasNoBridge() {
        assumeSupported()
        launch(listenerOrigin = "https://app.example").use { scenario ->
            val state = waitForTest(scenario)
            assertFalse(state.optBoolean("hasBridge"))
            assertFalse(state.optBoolean("hasPort"))
            assertEquals("no-bridge", state.optString("error"))
            assertNotEquals(true, state.opt("ping"))
        }
    }

    private fun assumeSupported() {
        assumeTrue("WebView message listener required", DeftJsBridge.webViewSupported())
    }

    private fun launch(
        allowed: String? = CapabilityContacts,
        delayMs: Long = 0,
        listenerOrigin: String? = null,
    ): ActivityScenario<BridgeHostTestActivity> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(ctx, BridgeHostTestActivity::class.java).apply {
            if (allowed != null) putExtra(BridgeHostTestActivity.EXTRA_ALLOWED, allowed)
            if (delayMs > 0) putExtra(BridgeHostTestActivity.EXTRA_DELAY_READY_MS, delayMs)
            if (listenerOrigin != null) putExtra(BridgeHostTestActivity.EXTRA_LISTENER_ORIGIN, listenerOrigin)
        }
        return ActivityScenario.launch(intent)
    }

    private fun waitForTest(
        scenario: ActivityScenario<BridgeHostTestActivity>,
        timeoutMs: Long = 6_000,
    ): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = JSONObject()
        while (System.currentTimeMillis() < deadline) {
            var activity: BridgeHostTestActivity? = null
            scenario.onActivity { activity = it }
            val raw = activity?.evalJs(READ_TEST) ?: "null"
            val json = unwrap(raw) ?: JSONObject()
            last = json
            if (json.optBoolean("done")) return json
            Thread.sleep(50)
        }
        throw AssertionError("bridge fixture did not finish: $last")
    }

    private fun unwrap(raw: String): JSONObject? {
        if (raw == "null") return null
        val value = JSONTokener(raw).nextValue()
        val text = value as? String ?: return null
        return JSONObject(text)
    }

    companion object {
        private const val CapabilityContacts = "contacts.read"
        private const val READ_TEST =
            "(function(){return window.__deftTest ? JSON.stringify(window.__deftTest) : null;})()"
    }
}
