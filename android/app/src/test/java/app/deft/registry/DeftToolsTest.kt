package app.deft.registry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeftToolsTest {
    private fun obj(raw: String) = Json.parseToJsonElement(raw) as JsonObject

    private fun parse(raw: String) = DeftToolsParser.parse(Json.parseToJsonElement(raw))

    private fun refused(raw: String): String {
        try {
            parse(raw)
        } catch (e: IllegalArgumentException) {
            return e.message.orEmpty()
        }
        fail("expected a refusal for $raw")
        return ""
    }

    private val ok = """{"name":"convert","description":"Convert a length.","params":{"type":"object","properties":{"value":{"type":"number"},"to":{"type":"string","enum":["km","mi"]}},"required":["value"]},"handler":"./tools.js#convert"}"""

    @Test
    fun parsesPageAndIsolateTools() {
        val tools = parse("""[$ok,{"name":"calc","description":"Add.","handler":{"isolate":"./calc.js#add"}}]""")
        assertEquals(listOf("convert", "calc"), tools.map { it.name })
        assertEquals("/tools.js", tools[0].handlerPath)
        assertEquals("convert", tools[0].fn)
        assertFalse(tools[0].isolate)
        assertTrue(tools[1].isolate)
        assertEquals("/calc.js", tools[1].handlerPath)
        // No params: an empty object schema.
        assertEquals("object", (tools[1].params["type"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun nullOrMissingToolsIsEmpty() {
        assertTrue(DeftToolsParser.parse(null).isEmpty())
        assertTrue(DeftManifestParser.parse("""{"deft":1,"id":"app-x","name":"X","start_url":"./index.html"}""").tools.isEmpty())
    }

    @Test
    fun namesFollowTheRule() {
        for (good in listOf("a", "convert", "a_1", "a".repeat(32))) assertTrue(good, DeftToolsParser.validName(good))
        for (bad in listOf("", "A", "1a", "a-b", "a.b", "a b", "a".repeat(33), "é")) assertFalse(bad, DeftToolsParser.validName(bad))
        assertTrue(refused(ok.replace("convert\",\"desc", "Convert\",\"desc").let { "[$it]" }).contains("name"))
    }

    @Test
    fun descriptionAndCountLimits() {
        assertTrue(refused("""[{"name":"a","description":"","handler":"./t.js#a"}]""").contains("description"))
        val long = "x".repeat(301)
        assertTrue(refused("""[{"name":"a","description":"$long","handler":"./t.js#a"}]""").contains("description"))
        val max = "x".repeat(300)
        assertEquals(1, parse("""[{"name":"a","description":"$max","handler":"./t.js#a"}]""").size)
        val nine = (0 until 9).joinToString(",") { """{"name":"t$it","description":"d","handler":"./t.js#f"}""" }
        assertTrue(refused("[$nine]").contains("at most"))
        assertTrue(refused("[$ok,$ok]").contains("duplicate"))
    }

    @Test
    fun handlerRefs() {
        assertEquals("/a/b.mjs" to "run_1", DeftToolsParser.parseHandlerRef("./a/b.mjs#run_1"))
        assertEquals("/t.js" to "\$f", DeftToolsParser.parseHandlerRef("/t.js#\$f"))
        for (bad in listOf("t.js#f", "./t.js", "./t.js#", "./t.css#f", "./../t.js#f", "./a//t.js#f", "/_deft/t.js#f", "./t.js#1f", "./t.js#f g")) {
            assertNull(bad, DeftToolsParser.parseHandlerRef(bad))
        }
        assertTrue(refused("""[{"name":"a","description":"d","handler":{"other":"./t.js#f"}}]""").contains("handler"))
        assertTrue(refused("""[{"name":"a","description":"d","handler":5}]""").contains("handler"))
    }

    @Test
    fun schemaSubset() {
        assertNull(DeftToolsParser.schemaError(obj("""{"type":"object","properties":{"a":{"type":"array","items":{"type":"integer"}},"b":{"type":"string","maxLength":9},"c":{"type":"integer","minimum":0,"maximum":5}},"required":["a"]}""")))
        val bad = listOf(
            """{"type":"array"}""",
            """{"type":"object","properties":{"a":{"type":"object"}}}""",
            """{"type":"object","properties":{"a":{"type":"string","pattern":"x"}}}""",
            """{"type":"object","properties":{"a":{"type":"array"}}}""",
            """{"type":"object","properties":{"a":{"type":"array","items":{"type":"array"}}}}""",
            """{"type":"object","properties":{"a b":{"type":"string"}}}""",
            """{"type":"object","properties":{"a":{"type":"string"}},"required":["b"]}""",
            """{"type":"object","properties":{"a":{"type":"string","enum":[1]}}}""",
            """{"type":"object","properties":{"a":{"type":"string","minimum":1}}}""",
            """{"type":"object","properties":{"a":{"type":"number","maxLength":3}}}""",
            """{"type":"object","anyOf":[]}""",
        )
        for (raw in bad) assertTrue(raw, DeftToolsParser.schemaError(obj(raw)) != null)
        val many = (0 until 13).joinToString(",") { "\"p$it\":{\"type\":\"string\"}" }
        assertTrue(DeftToolsParser.schemaError(obj("""{"type":"object","properties":{$many}}"""))!!.contains("at most"))
        val big = "x".repeat(2100)
        assertTrue(DeftToolsParser.schemaError(obj("""{"type":"object","description":"$big"}"""))!!.contains("larger"))
    }

    private val schema = obj(
        """{"type":"object","properties":{"value":{"type":"number","minimum":0,"maximum":100},"to":{"type":"string","enum":["km","mi"],"maxLength":2},"n":{"type":"integer"},"flag":{"type":"boolean"},"ids":{"type":"array","items":{"type":"integer"}}},"required":["value"]}""",
    )

    @Test
    fun validatesArguments() {
        assertNull(DeftToolsParser.argsError(schema, obj("""{"value":5,"to":"km","n":3,"flag":true,"ids":[1,2]}""")))
        assertNull(DeftToolsParser.argsError(schema, obj("""{"value":0}""")))
        assertEquals("missing required argument 'value'", DeftToolsParser.argsError(schema, obj("{}")))
        assertTrue(DeftToolsParser.argsError(schema, obj("""{"value":"5"}"""))!!.contains("must be a number"))
        assertTrue(DeftToolsParser.argsError(schema, obj("""{"value":5,"x":1}"""))!!.contains("unknown argument 'x'"))
        assertTrue(DeftToolsParser.argsError(schema, obj("""{"value":5,"to":"ft"}"""))!!.contains("one of km, mi"))
        assertTrue(DeftToolsParser.argsError(schema, obj("""{"value":5,"n":1.5}"""))!!.contains("must be a integer"))
        assertTrue(DeftToolsParser.argsError(schema, obj("""{"value":101}"""))!!.contains("at most"))
        assertTrue(DeftToolsParser.argsError(schema, obj("""{"value":-1}"""))!!.contains("at least"))
        assertTrue(DeftToolsParser.argsError(schema, obj("""{"value":1,"flag":"yes"}"""))!!.contains("boolean"))
        assertTrue(DeftToolsParser.argsError(schema, obj("""{"value":1,"ids":[1,"a"]}"""))!!.contains("array of integer"))
        assertTrue(DeftToolsParser.argsError(schema, obj("""{"value":1,"ids":5}"""))!!.contains("array"))
        assertTrue(DeftToolsParser.argsError(schema, obj("""{"value":1,"to":"abc"}"""))!!.contains("one of"))
        val tooMany = (0 until 101).joinToString(",")
        assertTrue(DeftToolsParser.argsError(schema, obj("""{"value":1,"ids":[$tooMany]}"""))!!.contains("more than"))
    }

    @Test
    fun consentFingerprintChangesWithAnyPartOfTheTool() {
        val base = parse("[$ok]")[0]
        assertEquals(base.consentKey(), parse("[$ok]")[0].consentKey())
        assertTrue(base.consentKey().startsWith("convert@"))
        assertNotEquals(base.consentKey(), parse("[${ok.replace("Convert a length.", "Convert, then send it away.")}]")[0].consentKey())
        assertNotEquals(base.consentKey(), parse("[${ok.replace("./tools.js#convert", "./other.js#convert")}]")[0].consentKey())
        assertNotEquals(base.consentKey(), parse("[${ok.replace("\"km\",\"mi\"", "\"km\"")}]")[0].consentKey())
        assertNotEquals(base.consentKey(), parse("[${ok.replace("\"./tools.js#convert\"", "{\"isolate\":\"./tools.js#convert\"}")}]")[0].consentKey())
    }

    @Test
    fun canvasProfileCannotDeclareTools() {
        val raw = """{"deft":1,"id":"canvas-x","name":"X","profile":"canvas","start_url":"./index.html","tools":[$ok]}"""
        try {
            DeftManifestParser.parse(raw)
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("canvas forbids tools"))
        }
    }

    private fun files(vararg pairs: Pair<String, String>): Map<String, java.io.File> {
        val dir = java.nio.file.Files.createTempDirectory("fltools").toFile()
        return pairs.associate { (rel, text) ->
            rel to java.io.File(dir, rel.replace('/', '_')).also { it.writeText(text) }
        }
    }

    @Test
    fun consentKeyChangesWhenTheHandlerCodeChanges() {
        val tool = parse("[$ok]").single()
        val a = DeftToolCode.hash(tool, files("tools.js" to "export function convert(){return 1}"))
        val b = DeftToolCode.hash(tool, files("tools.js" to "export function convert(){return fetch('//x')}"))
        assertNotEquals(a, b)
        assertNotEquals(tool.withCode(a).consentKey(), tool.withCode(b).consentKey())
        assertEquals(tool.withCode(a).consentKey(), tool.withCode(DeftToolCode.hash(tool, files("tools.js" to "export function convert(){return 1}"))).consentKey())
    }

    @Test
    fun aPageHandlerAlsoCoversTheScriptsItCouldImportButNotOtherFiles() {
        val tool = parse("[$ok]").single()
        val base = DeftToolCode.hash(tool, files("tools.js" to "import './h.js'", "h.js" to "1", "a.css" to "x"))
        assertNotEquals(base, DeftToolCode.hash(tool, files("tools.js" to "import './h.js'", "h.js" to "2", "a.css" to "x")))
        assertEquals(base, DeftToolCode.hash(tool, files("tools.js" to "import './h.js'", "h.js" to "1", "a.css" to "changed")))
    }

    @Test
    fun anIsolateHandlerIsOnlyItsOwnFile() {
        val isolate = parse("""[{"name":"c","description":"d","handler":{"isolate":"./tools.js#convert"}}]""").single()
        val base = DeftToolCode.hash(isolate, files("tools.js" to "function convert(){}", "main.js" to "1"))
        assertEquals(base, DeftToolCode.hash(isolate, files("tools.js" to "function convert(){}", "main.js" to "2")))
        assertNotEquals(base, DeftToolCode.hash(isolate, files("main.js" to "1")))
    }

    @Test
    fun aChangedDependencyPinIsAnotherFingerprintForPageHandlersOnly() {
        val page = parse("[$ok]").single()
        val f = files("tools.js" to "import 'lib'")
        val a = listOf(DeftDependency("https://cdn.example/x.js", "sha384-AAA", "lib"))
        val b = listOf(DeftDependency("https://cdn.example/x.js", "sha384-BBB", "lib"))
        val none = DeftToolCode.hash(page, f)
        assertNotEquals(none, DeftToolCode.hash(page, f, a))
        assertNotEquals(DeftToolCode.hash(page, f, a), DeftToolCode.hash(page, f, b))
        assertEquals(DeftToolCode.hash(page, f, a), DeftToolCode.hash(page, f, a.reversed()))
        assertNotEquals(page.withCode(none).consentKey(), page.withCode(DeftToolCode.hash(page, f, a)).consentKey())
        val isolate = parse("""[{"name":"c","description":"d","handler":{"isolate":"./tools.js#convert"}}]""").single()
        assertEquals(DeftToolCode.hash(isolate, f), DeftToolCode.hash(isolate, f, a))
    }

    @Test
    fun handlerPathsAreNormalisedBeforeTheFlCheck() {
        fun path(ref: String) = DeftToolsParser.parseHandlerRef(ref)?.first
        assertEquals("/a/tools.js", path("./a/./tools.js#f"))
        assertEquals("/tools.js", path("/./tools.js#f"))
        assertEquals(null, path("/./_deft/v1/store.js#f"))
        assertEquals(null, path("./a/../_deft/x.js#f"))
        assertEquals(null, path("./_deft/x.js#f"))
        assertEquals(null, path("/_DEFT/x.js#f"))
        assertEquals(null, path("./%5fdeft/x.js#f"))
        assertEquals(null, path("./%2e%2e/x.js#f"))
        assertEquals(false, DeftManifestParser.validBackgroundModule("/./_deft/x.js"))
        assertEquals(true, DeftManifestParser.validBackgroundModule("./a/./b.js"))
    }
}
