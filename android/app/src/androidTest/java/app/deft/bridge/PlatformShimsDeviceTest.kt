package app.deft.bridge

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** PARITY Phase A shims in the device's real WebView (no grants: everything must say no). */
@RunWith(AndroidJUnit4::class)
class PlatformShimsDeviceTest {
    @Test
    fun shimsAreInstalledAndRefuseWithoutGrants() {
        assumeTrue(DeftJsBridge.webViewSupported())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(ctx, BridgeHostTestActivity::class.java).putExtra(BridgeHostTestActivity.EXTRA_ALLOWED, "")
        ActivityScenario.launch<BridgeHostTestActivity>(intent).use { scenario ->
            val result = probe(scenario)
            android.util.Log.i("PlatformShims", result.toString())
            assertEquals("", result.optString("error"))
            assertTrue("navigator.share is a function", result.getBoolean("shareFn"))
            assertFalse("vibrate refused without haptics", result.getBoolean("vibrated"))
            assertFalse("vibrate cannot be replaced", result.getBoolean("vibrateWritable"))
            assertEquals("NotAllowedError", result.getString("wakeLock"))
            assertEquals("NotAllowedError", result.getString("clipboard"))
            assertEquals("NotAllowedError", result.getString("orientation"))
            assertEquals("NotAllowedError", result.getString("share"))
            assertFalse("print is the shim", result.getBoolean("printNative"))
        }
    }

    private fun probe(scenario: ActivityScenario<BridgeHostTestActivity>): JSONObject {
        val start = """
            (function(){
              if (!window.deftBridge) return 'no-bridge';
              window.__probe = null;
              var out = { error: '' };
              function name(p) { return p.then(function(){ return 'resolved'; }, function(e){ return (e && e.name) || String(e); }); }
              deftBridge.ready.then(function(){
                out.shareFn = typeof navigator.share === 'function';
                out.shareNative = /\[native code\]/.test(String(navigator.share));
                out.vibrated = navigator.vibrate(10);
                var d = Object.getOwnPropertyDescriptor(Navigator.prototype, 'vibrate');
                out.vibrateWritable = !!(d && (d.writable || d.configurable));
                out.printNative = /\[native code\]/.test(String(window.print));
                return Promise.all([
                  name(navigator.wakeLock.request('screen')),
                  name(navigator.clipboard.readText()),
                  name(screen.orientation.lock('landscape')),
                  name(navigator.share({ text: 'x' })),
                ]);
              }).then(function(r){
                out.wakeLock = r[0]; out.clipboard = r[1]; out.orientation = r[2]; out.share = r[3];
                window.__probe = JSON.stringify(out);
              }, function(e){ out.error = String(e); window.__probe = JSON.stringify(out); });
              return 'started';
            })()
        """.trimIndent()
        val deadline = System.currentTimeMillis() + 10_000
        var started = false
        while (System.currentTimeMillis() < deadline) {
            var activity: BridgeHostTestActivity? = null
            scenario.onActivity { activity = it }
            val a = activity ?: continue
            if (!started) {
                started = a.evalJs(start).contains("started")
            } else {
                val raw = a.evalJs("window.__probe")
                if (raw != "null") {
                    val text = JSONTokener(raw).nextValue() as String
                    return JSONObject(text)
                }
            }
            Thread.sleep(100)
        }
        throw AssertionError("shim probe did not finish")
    }
}
