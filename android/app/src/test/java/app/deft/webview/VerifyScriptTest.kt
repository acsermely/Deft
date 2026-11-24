package app.deft.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VerifyScriptTest {
    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun steps(s: String) = VerifyScript.parseSteps(j(s))!!

    private fun rejects(s: String, part: String) {
        try {
            VerifyScript.parseSteps(j(s))
            fail("accepted $s")
        } catch (e: IllegalArgumentException) {
            assertTrue("${e.message} lacks $part", e.message.orEmpty().contains(part))
        }
    }

    /** The JSON a `JSON.parse("...")` expression yields; fails unless the expression is exactly one string literal. */
    private fun unwrap(js: String): JsonElement {
        assertTrue(js, js.startsWith("JSON.parse(\"") && js.endsWith("\")"))
        val lit = Json.parseToJsonElement(js.removePrefix("JSON.parse(").removeSuffix(")")) as JsonPrimitive
        assertTrue(lit.isString)
        return Json.parseToJsonElement(lit.content)
    }

    @Test
    fun everyStepKindParsesWithLabels() {
        val s = steps(
            """[{"click":"#start"},{"scroll":".list li:last-child"},{"type":"#name","text":"Bob"},
               {"emit":"sensors.reading","data":[{"id":"s1"},{"id":"s1"}]},{"emit":"media.action"},{"wait":200},
               {"assert":"#out","contains":"1.0"},{"assert":"#out","text":"0.0 cm"},{"assert":"dialog","visible":false},
               {"assert":"#x","exists":true},{"assert":"input","value":"3"},{"assert":"#b","attr":{"aria-pressed":"true"}},
               {"assert":"#b","attr":{"disabled":null}}]""",
        )
        assertEquals(
            listOf(
                "click #start", "scroll .list li:last-child", "type #name \"Bob\"", "emit sensors.reading ×2", "emit media.action",
                "wait 200", "assert #out contains \"1.0\"", "assert #out text \"0.0 cm\"", "assert dialog hidden", "assert #x exists",
                "assert input value \"3\"", "assert #b attr aria-pressed=\"true\"", "assert #b attr disabled=absent",
            ),
            s.map { it.label },
        )
        assertEquals(j("""{"op":"emit","event":"sensors.reading","data":[{"id":"s1"},{"id":"s1"}],"ms":20}"""), s[3].json)
        assertEquals(j("""{"op":"emit","event":"media.action","data":{},"ms":20}"""), s[4].json)
        assertEquals(j("""{"op":"assert","sel":"#b","check":"attr","name":"aria-pressed","want":"true"}"""), s[11].json)
        assertEquals(j("""{"op":"assert","sel":"dialog","check":"visible","want":false}"""), s[8].json)
    }

    @Test
    fun aJsonStringIsAcceptedAndEmptyMeansAbsent() {
        assertEquals("click #a", VerifyScript.parseSteps(JsonPrimitive("""[{"click":"#a"}]"""))!!.single().label)
        assertNull(VerifyScript.parseSteps(null))
        assertNull(VerifyScript.parseSteps(JsonPrimitive("")))
        assertNull(VerifyScript.parseSteps(j("[]")))
        assertNull(VerifyScript.parseBridge(JsonPrimitive(" ")))
    }

    @Test
    fun badStepsAreRejectedWithTheStepNumber() {
        rejects("""[{"click":"#a"},{"click":"#b","selector":"x"}]""", "step 2: unknown key \"selector\" for click")
        rejects("""[{"click":"#a","wait":5}]""", "step 1: needs exactly one of")
        rejects("""[{"tap":"#a"}]""", "needs exactly one of")
        rejects("""[{"assert":"#a"}]""", "assert needs exactly one of")
        rejects("""[{"assert":"#a","text":"x","visible":true}]""", "assert needs exactly one of")
        rejects("""[{"assert":"#a","equals":"x"}]""", "unknown key \"equals\" for assert")
        rejects("""[{"assert":"#a","visible":"maybe"}]""", "visible must be true or false")
        rejects("""[{"assert":"#a","attr":{"a":"1","b":"2"}}]""", "attr must be one")
        rejects("""[{"wait":5000}]""", "wait needs milliseconds 0..3000")
        rejects("""[{"emit":"bad event!"}]""", "emit needs an event name")
        rejects("""[{"emit":"e","data":5}]""", "object or an array")
        rejects("""[{"emit":"e","data":{},"ms":99999}]""", "emit ms must be")
        rejects("""[{"type":"#a"}]""", "type needs \"text\"")
        rejects("""[{"click":""}]""", "selector must be")
        rejects("""[{"click":{"x":1}}]""", "needs a CSS selector")
        rejects("""[5]""", "step 1: must be an object")
        rejects("""{"click":"#a"}""", "steps must be a JSON array")
        rejects("[" + List(41) { """{"wait":1}""" }.joinToString(",") + "]", "at most 40 steps")
        rejects("""[{"type":"#a","text":"${"x".repeat(33_000)}"}]""", "too large")
        try {
            VerifyScript.parseSteps(JsonPrimitive("[{"))
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.startsWith("steps is not valid JSON"))
        }
    }

    @Test
    fun bridgeMocksNormalizeValuesAndErrors() {
        val m = VerifyScript.parseBridge(
            j(
                """{"sensors.list":[{"type":"linearAcceleration","maxRange":78}],"sensors.subscribe":{"id":"s1"},
                   "clipboard.read":{"error":{"code":"unsupported","message":"not on screen"}},"nfc.read":{"error":"busy"},
                   "app.ok":{"error":"x","more":1}}""",
            ),
        )!!
        assertEquals(j("""{"value":[{"type":"linearAcceleration","maxRange":78}]}"""), m["sensors.list"])
        assertEquals(j("""{"error":{"code":"unsupported","message":"not on screen"}}"""), m["clipboard.read"])
        assertEquals(j("""{"error":{"code":"busy","message":"busy"}}"""), m["nfc.read"])
        // An object with more than the error key is a plain value.
        assertEquals(j("""{"value":{"error":"x","more":1}}"""), m["app.ok"])
        for ((raw, part) in listOf(
            """{"bad name":1}""" to "is not a method name",
            """{"__proto__":1}""" to "is not a method name",
            """{"a.b":{"error":{"message":"m"}}}""" to "error needs a code",
            """[1]""" to "bridge must be a JSON object",
        )) {
            try {
                VerifyScript.parseBridge(j(raw))
                fail("accepted $raw")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message, e.message!!.contains(part))
            }
        }
    }

    @Test
    fun injectedJsCarriesAgentTextOnlyAsAJsonStringLiteral() {
        val evil = "\"); alert(1); (\"</script> "
        val mocks = VerifyScript.parseBridge(j("""{"a.b":${JsonPrimitive(evil)}}"""))
        val cfg = VerifyScript.bridgeConfigJs(listOf("sensors.list", "net.fetch"), mocks)
        assertTrue(!cfg.contains(" "))
        assertEquals(
            j("""{"allowed":["sensors.list","net.fetch"],"mocks":{"a.b":{"value":${JsonPrimitive(evil)}}}}"""),
            unwrap(cfg),
        )
        assertEquals(j("""{"allowed":[],"mocks":{}}"""), unwrap(VerifyScript.bridgeConfigJs(emptyList(), null)))

        val s = steps("""[{"click":${JsonPrimitive(evil)}},{"assert":"#a","text":"${'$'}{x}"}]""")
        val js = VerifyScript.runJs(s)
        val arg = js.substringAfterLast("})(").removeSuffix(",${VerifyScript.SCRIPT_MS})")
        val parsed = unwrap(arg) as JsonArray
        assertEquals(evil, parsed[0].jsonObject["sel"]!!.jsonPrimitive.content)
        assertEquals("\${x}", parsed[1].jsonObject["want"]!!.jsonPrimitive.content)
    }

    @Test
    fun reportListsPassedFailedAndSkippedSteps() {
        val s = steps("""[{"click":"#start"},{"emit":"sensors.reading","data":[{},{}]},{"assert":"#readout","contains":"1.0"},{"wait":1},{"wait":2},{"wait":3}]""")
        val raw = """{"r":{"done":2,"fail":"got \"0.0 cm\"","end":true},"c":["sensors.list {}","sensors.subscribe {\"type\":\"linearAcceleration\",\"rateHz\":50}"],"more":0}"""
        assertTrue(VerifyScript.ended(raw))
        val r = VerifyScript.report(s, raw)
        assertEquals(3, r.failedStep)
        assertEquals(
            """
            steps:
            ok 1 click #start
            ok 2 emit sensors.reading ×2
            FAIL 3 assert #readout contains "1.0": got "0.0 cm"
            skipped 4..6
            bridge calls: sensors.list {}, sensors.subscribe {"type":"linearAcceleration","rateHz":50}
            """.trimIndent(),
            r.text,
        )
    }

    @Test
    fun reportEdgeCases() {
        val s = steps("""[{"click":"#a"},{"click":"#b"}]""")
        val ok = VerifyScript.report(s, """{"r":{"done":2,"fail":null,"end":true},"c":[],"more":0}""")
        assertNull(ok.failedStep)
        assertEquals("steps:\nok 1 click #a\nok 2 click #b\nbridge calls: none", ok.text)

        val last = VerifyScript.report(s, """{"r":{"done":1,"fail":"no element matches","end":true},"c":[]}""")
        assertEquals("steps:\nok 1 click #a\nFAIL 2 click #b: no element matches\nbridge calls: none", last.text)

        val hung = VerifyScript.report(steps("""[{"click":"#a"},{"click":"#b"},{"wait":1}]"""), """{"r":{"done":1,"end":false},"c":[]}""", "page stopped responding")
        assertEquals(2, hung.failedStep)
        assertTrue(hung.text, hung.text.contains("FAIL 2 click #b: page stopped responding\nskipped 3\n"))
        assertTrue(!VerifyScript.ended("""{"r":{"done":1,"end":false}}"""))

        val never = VerifyScript.report(s, "", "page did not finish loading")
        assertEquals(1, never.failedStep)
        assertTrue(never.text.contains("FAIL 1 click #a: page did not finish loading\nskipped 2"))

        val calls = JsonArray(List(25) { JsonPrimitive("m$it {}") })
        val only = VerifyScript.report(null, JsonObject(mapOf("c" to calls, "more" to JsonPrimitive(3))).toString())
        assertNull(only.failedStep)
        assertTrue(only.text, only.text.startsWith("bridge calls: m0 {}, m1 {}"))
        assertTrue(only.text, only.text.endsWith("m19 {}, … +8 more"))
    }
    private fun previewRejects(s: String, part: String) {
        try {
            VerifyScript.parseSteps(j(s), VerifyScript.Mode.PREVIEW)
            fail("accepted $s")
        } catch (e: IllegalArgumentException) {
            assertTrue("${e.message} lacks $part", e.message.orEmpty().contains(part))
        }
    }

    @Test
    fun previewParsesAskAndRejectsEmit() {
        val s = VerifyScript.parseSteps(j("""[{"click":"#go"},{"ask":"  Move the phone\n50 cm right, then tap Done "},{"assert":"#d","contains":"50"}]"""), VerifyScript.Mode.PREVIEW)!!
        assertEquals(listOf("click #go", "ask", "assert #d contains \"50\""), s.map { it.label })
        assertEquals("Move the phone 50 cm right, then tap Done", s[1].ask?.text)
        assertNull(s[0].ask)
        previewRejects("""[{"emit":"sensors.reading","data":{}}]""", "works only in apps.verify")
        previewRejects("""[{"ask":""}]""", "1..200")
        previewRejects("""[{"ask":"${"x".repeat(201)}"}]""", "1..200")
        previewRejects("""[{"ask":"a","ms":3}]""", "unknown key")
        rejects("""[{"ask":"move it"}]""", "only in apps.preview")
    }

    @Test
    fun stepLinesSupportLabelsAndStops() {
        val labels = listOf("click #a", "ask: user tapped Done after 6.2 s", "ask", "click #b")
        val (ok, f0) = VerifyScript.stepLines(labels.take(2), 2, null)
        assertNull(f0)
        assertEquals("steps:\nok 1 click #a\nok 2 ask: user tapped Done after 6.2 s\n", ok)
        val (failed, f) = VerifyScript.stepLines(labels, 2, "user tapped Can't")
        assertEquals(3, f)
        assertTrue(failed, failed.contains("FAIL 3 ask: user tapped Can't\nskipped 4\n"))
        val (gone, g) = VerifyScript.stepLines(labels, 1, null, "Preview closed or reloaded during the run")
        assertNull(g)
        assertTrue(gone, gone.endsWith("ok 1 click #a\nskipped 2..4: Preview closed or reloaded during the run\n"))
        assertEquals(VerifyScript.Progress(1, "no element matches"), VerifyScript.progress(3, """{"r":{"done":1,"fail":"no element matches","end":true}}"""))
        assertEquals(VerifyScript.Progress(0, "lost"), VerifyScript.progress(2, "", "lost"))
        assertEquals(VerifyScript.Progress(2, null), VerifyScript.progress(2, """{"r":{"done":2,"end":true}}"""))
    }
}
