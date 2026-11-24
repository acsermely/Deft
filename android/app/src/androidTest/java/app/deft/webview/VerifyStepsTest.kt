package app.deft.webview

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.deft.agent.AgentTools
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** apps.verify behaviour tests in a real WebView: steps drive a routes() sensor app, bridge mocks answer its calls. */
@RunWith(AndroidJUnit4::class)
class VerifyStepsTest {
    private val slug = "deft-verify-steps"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun setup() {
        dir = File(context.cacheDir, slug).apply {
            deleteRecursively()
            mkdirs()
        }
        writeSensorApp(dir)
    }

    @After
    fun teardown() {
        dir.deleteRecursively()
    }

    @Test
    fun passingScriptReportsOkAndBridgeCalls() {
        val dump = verify(
            mocks = """{"sensors.subscribe":{"id":"s1"}}""",
            steps = """[
              {"assert":"#has","text":"has sensors"},
              {"click":"#go"},
              {"assert":"#w","text":"360"},
              {"click":"#start"},
              {"assert":"#status","text":"on s1"},
              {"emit":"sensors.reading","data":[{"id":"s1","values":[0.5,0,0]},{"id":"s1","values":[0.25,0,0]},{"id":"s1","values":[1,0,0]}]},
              {"assert":"#readout","contains":"1.75"},
              {"type":"#name","text":"Ada"},
              {"assert":"#name","value":"Ada"},
              {"assert":"#hello","contains":"Ada"},
              {"assert":"#start","attr":{"data-kind":"primary"}},
              {"assert":"#missing","exists":false},
              {"scroll":"#readout"},
              {"wait":20},
              {"assert":"body","attr":{"data-raf":"1"}}
            ]""",
        )
        assertTrue("steps section:\n$dump", dump.contains("\nsteps:\nok 1 assert #has text \"has sensors\""))
        assertTrue("all 14 steps ok:\n$dump", dump.contains("\nok 14 wait 20"))
        assertFalse("no FAIL:\n$dump", dump.contains("FAIL"))
        assertTrue("subscribe logged:\n$dump", Regex("bridge calls: sensors\\.subscribe \\{").containsMatchIn(dump))
        assertFalse("steps replace the smoke test:\n$dump", dump.contains("smoke"))
    }

    @Test
    fun wrongAssertFailsWithGotText() {
        val dump = verify(
            mocks = """{"sensors.subscribe":{"id":"s1"}}""",
            steps = """[
              {"click":"#go"},
              {"click":"#start"},
              {"emit":"sensors.reading","data":[{"id":"s1","values":[0.5,0,0]},{"id":"s1","values":[0.25,0,0]}]},
              {"assert":"#readout","text":"9"},
              {"assert":"#status","text":"on s1"},
              {"wait":10}
            ]""",
        )
        assertTrue("FAIL line:\n$dump", dump.contains("FAIL 4 assert #readout text \"9\": got \"0.75\""))
        assertTrue("rest skipped:\n$dump", dump.contains("\nskipped 5..6"))
        assertTrue("issue header:\n$dump", dump.startsWith("issues") && dump.contains("step 4 failed"))
    }

    @Test
    fun mockedRejectionReachesThePage() {
        val dump = verify(
            mocks = """{"sensors.subscribe":{"error":{"code":"unsupported","message":"The app is not on screen"}}}""",
            steps = """[{"click":"#go"},{"click":"#start"},{"assert":"#status","text":"error unsupported: The app is not on screen"}]""",
        )
        assertTrue("rejection shown:\n$dump", dump.contains("ok 3 assert #status"))
        assertFalse("no FAIL:\n$dump", dump.contains("FAIL"))
    }

    @Test
    fun hasIsFalseWithoutTheCapabilityAndEmitNeedsAListener() {
        val dump = verify(
            caps = emptyList(),
            steps = """[{"assert":"#has","text":"no sensors"},{"emit":"sensors.reading","data":{"values":[1]}}]""",
        )
        assertTrue("has() false:\n$dump", dump.contains("ok 1 assert #has text \"no sensors\""))
        assertTrue("emit without on():\n$dump", dump.contains("FAIL 2 emit sensors.reading: nothing listens for sensors.reading"))
        assertTrue("no calls:\n$dump", dump.contains("bridge calls: none"))
    }

    @Test
    fun blockingHandlerReportsWhereTheRunStopped() {
        val dump = verify(steps = """[{"click":"#go"},{"click":"#hang"},{"wait":10}]""")
        assertTrue("hang reported:\n$dump", dump.contains("ok 1 click #go\nFAIL 2 click #hang: page stopped responding"))
    }

    @Test
    fun plainVerifyStillRunsTheSmokeTest() {
        // AgentTools always passes a bridge config for my-apps; without steps the blind smoke test runs.
        val dump = verify()
        assertTrue("smoke ran:\n$dump", dump.contains("button#noop") && dump.contains("click changed nothing"))
        assertFalse("no steps section:\n$dump", dump.contains("steps:"))
        assertFalse("no call log without mocks:\n$dump", dump.contains("bridge calls:"))
        val bare = runBlocking { AppVerifier.verify(context, slug, dir, "./") }
        assertTrue("smoke ran without a bridge config:\n$bare", bare.contains("button#noop"))
    }

    private fun verify(caps: List<String> = listOf("sensors"), mocks: String? = null, steps: String? = null): String {
        val parsedMocks = VerifyScript.parseBridge(mocks?.let { Json.parseToJsonElement(it) })
        val parsedSteps = VerifyScript.parseSteps(steps?.let { Json.parseToJsonElement(it) })
        val dump = runBlocking {
            AppVerifier.verify(
                context,
                slug,
                dir,
                "./",
                bridgeConfig = VerifyScript.bridgeConfigJs(AgentTools.grantedMethods(caps), parsedMocks),
                steps = parsedSteps,
                showCalls = parsedMocks != null,
            )
        }
        Log.i("VerifyStepsTest", dump)
        return dump
    }

    private fun writeSensorApp(dir: File) {
        File(dir, "index.html").writeText(
            """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
              <link rel="stylesheet" href="/_deft/v1/base.css" />
              <script type="module" src="./main.js"></script>
            </head>
            <body><main id="app"></main></body>
            </html>
            """.trimIndent(),
        )
        File(dir, "main.js").writeText(
            """
            import {routes} from '/_deft/v1/route.js';
            import {html} from '/_deft/v1/dom.js';
            import {signal} from '/_deft/v1/core.js';
            import {call, on, has} from '/_deft/v1/bridge.js';

            const sum = signal(0), status = signal('idle'), name = signal('');
            async function start() {
              try {
                const sub = await call('sensors.subscribe', {type: 'linearAcceleration', rateHz: 50});
                status.value = 'on ' + sub.id;
                on('sensors.reading', r => { sum.value = Math.round((sum.value + r.values[0]) * 1000) / 1000; });
              } catch (e) { status.value = 'error ' + e.code + ': ' + e.message; }
            }
            const home = () => html`<section><p id="has">${'$'}{has('sensors') ? 'has sensors' : 'no sensors'}</p>
              <button id="noop">Noop</button><a id="go" href="#/sensor">Sensor</a></section>`;
            const sensor = () => html`<section>
              <button id="start" data-kind="primary" @click=${'$'}{start}>Start</button>
              <p id="status">${'$'}{status.value}</p><p id="readout">${'$'}{sum.value}</p>
              <input id="name" .value=${'$'}{name.value} @input=${'$'}{e => name.value = e.target.value}>
              <p id="hello">Hi ${'$'}{name.value}</p><p id="w">${'$'}{innerWidth}</p>
              <button id="hang" @click=${'$'}{() => { const t = Date.now() + 16000; while (Date.now() < t); }}>Hang</button></section>`;
            requestAnimationFrame(() => document.body.dataset.raf = '1');
            routes({'/': home, '/sensor': sensor}, document.getElementById('app'));
            """.trimIndent(),
        )
    }
}
