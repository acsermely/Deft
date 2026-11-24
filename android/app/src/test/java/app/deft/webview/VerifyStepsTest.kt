package app.deft.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VerifyStepsTest {
    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun ask(s: String): VerifyScript.AskSpec = VerifyScript.parseSteps(j(s), VerifyScript.Mode.PREVIEW)!!.single().ask!!

    private fun rejects(s: String, part: String, mode: VerifyScript.Mode = VerifyScript.Mode.PREVIEW) {
        try {
            VerifyScript.parseSteps(j(s), mode)
            fail("accepted $s")
        } catch (e: IllegalArgumentException) {
            assertTrue("${e.message} lacks $part", e.message.orEmpty().contains(part))
        }
    }

    @Test
    fun plainAskStillParses() {
        val s = ask("""[{"ask":"  Tilt   the phone left "}]""")
        assertEquals(VerifyScript.AskSpec("Tilt the phone left"), s)
        val step = VerifyScript.parseSteps(j("""[{"ask":"Tilt"}]"""), VerifyScript.Mode.PREVIEW)!!.single()
        assertEquals("ask", step.label)
        assertNull(VerifyScript.parseSteps(j("""[{"click":"#a"}]"""), VerifyScript.Mode.PREVIEW)!!.single().ask)
    }

    @Test
    fun optionsParseAndNormalize() {
        val s = ask("""[{"ask":"Is it ember?","options":["  Yes ","No\tthanks","Maybe"],"expect":"Yes"}]""")
        assertEquals(listOf("Yes", "No thanks", "Maybe"), s.options)
        assertEquals("Yes", s.expect)
        assertNull(s.input)
        assertEquals(emptyList<String>(), ask("""[{"ask":"Tilt"}]""").options)
    }

    @Test
    fun optionsWithoutExpectParse() {
        val s = ask("""[{"ask":"Which value?","options":["0","1","2","More"]}]""")
        assertEquals(listOf("0", "1", "2", "More"), s.options)
        assertNull(s.expect)
    }

    @Test
    fun expectMustBeOneOfTheOptions() {
        rejects("""[{"ask":"Pick","options":["Yes","No"],"expect":"Maybe"}]""", "is not one of the options")
        rejects("""[{"ask":"Pick","options":["Yes","No"],"expect":"yes"}]""", "is not one of the options")
    }

    @Test
    fun expectWithoutOptionsIsRejected() {
        rejects("""[{"ask":"Pick","expect":"Yes"}]""", "expect needs options")
    }

    @Test
    fun inputTextAndNumberParse() {
        assertEquals("number", ask("""[{"ask":"What number?","input":"number"}]""").input)
        assertEquals("text", ask("""[{"ask":"What word?","input":"text"}]""").input)
    }

    @Test
    fun inputCannotCombineWithOptions() {
        rejects("""[{"ask":"Pick","input":"text","options":["a","b"]}]""", "cannot be combined")
    }

    @Test
    fun badInputValueIsRejected() {
        rejects("""[{"ask":"Pick","input":"email"}]""", "input must be")
        rejects("""[{"ask":"Pick","input":3}]""", "input must be")
    }

    @Test
    fun optionCountIsTwoToSix() {
        rejects("""[{"ask":"Pick","options":["only"]}]""", "2..6 choices")
        rejects("""[{"ask":"Pick","options":["a","b","c","d","e","f","g"]}]""", "2..6 choices")
        assertEquals(6, ask("""[{"ask":"Pick","options":["a","b","c","d","e","f"]}]""").options.size)
    }

    @Test
    fun duplicateOptionsIgnoringCaseAreRejected() {
        rejects("""[{"ask":"Pick","options":["Red","red"]}]""", "listed twice")
        rejects("""[{"ask":"Pick","options":["A","b ","  a"]}]""", "listed twice")
    }

    @Test
    fun optionLengthIsOneToForty() {
        rejects("""[{"ask":"Pick","options":["${"x".repeat(41)}","b"]}]""", "1..40 characters")
        rejects("""[{"ask":"Pick","options":["   ","b"]}]""", "1..40 characters")
        assertEquals("x".repeat(40), ask("""[{"ask":"Pick","options":["${"x".repeat(40)}","b"]}]""").options[0])
    }

    @Test
    fun escapeLabelsAreRejected() {
        rejects("""[{"ask":"Pick","options":["Skip","b"]}]""", "launcher always adds a Skip button")
        rejects("""[{"ask":"Pick","options":["a","can’t tell"]}]""", "is not needed")
        rejects("""[{"ask":"Pick","options":["N/A","b"]}]""", "is not needed")
        rejects("""[{"ask":"Pick","options":["a","None of these."]}]""", "is not needed")
        rejects("""[{"ask":"Pick","options":["DON'T KNOW","b"]}]""", "is not needed")
    }

    @Test
    fun unknownKeyIsRejected() {
        rejects("""[{"ask":"Pick","options":["a","b"],"hint":"x"}]""", "unknown key")
        rejects("""[{"ask":"Pick","choices":["a","b"]}]""", "unknown key")
    }

    @Test
    fun askIsRejectedInVerifyMode() {
        rejects("""[{"ask":"Pick","options":["a","b"],"expect":"a"}]""", "only in apps.preview", VerifyScript.Mode.VERIFY)
    }
}
