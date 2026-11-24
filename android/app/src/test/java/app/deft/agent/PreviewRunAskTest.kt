package app.deft.agent

import app.deft.ui.agent.AskAnswer
import app.deft.webview.VerifyScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewRunAskTest {
    private val plain = VerifyScript.AskSpec("Tilt the phone left")
    private val choice = VerifyScript.AskSpec("Is the header ember now?", listOf("Yes", "No"))
    private val expecting = VerifyScript.AskSpec("Is the header ember now?", listOf("Yes", "No"), expect = "Yes")

    @Test
    fun doneLabelsTheSecondsAndPasses() {
        val out = PreviewRun.askOutcome(plain, AskAnswer.Done, 4_100)
        assertEquals("ask: user tapped Done after 4.1 s", out.label)
        assertNull(out.fail)
        assertNull(out.stopped)
    }

    @Test
    fun answerWithoutExpectPasses() {
        val out = PreviewRun.askOutcome(choice, AskAnswer.Answered("No"), 3_000)
        assertEquals("ask: user answered \"No\" after 3.0 s", out.label)
        assertNull(out.fail)
        assertNull(out.stopped)
    }

    @Test
    fun answerEqualToExpectPasses() {
        val out = PreviewRun.askOutcome(expecting, AskAnswer.Answered("Yes"), 1_200)
        assertEquals("ask: user answered \"Yes\" after 1.2 s", out.label)
        assertNull(out.fail)
        assertNull(out.stopped)
    }

    @Test
    fun answerOtherThanExpectFailsWithBothValues() {
        val out = PreviewRun.askOutcome(expecting, AskAnswer.Answered("No"), 2_000)
        assertEquals("user answered \"No\", expected \"Yes\"", out.fail)
        assertNull(out.stopped)
    }

    @Test
    fun skipWithNoteStopsWithoutFailure() {
        val out = PreviewRun.askOutcome(choice, AskAnswer.Skipped("the header is gone"), 500)
        assertNull(out.fail)
        assertEquals(
            "user tapped Skip: this ask did not apply (\"the header is gone\"); " +
                "reword or drop the ask, do not change app code for it",
            out.stopped,
        )
    }

    @Test
    fun skipWithoutNoteSaysNoNote() {
        val out = PreviewRun.askOutcome(plain, AskAnswer.Skipped(null), 500)
        assertNull(out.fail)
        assertEquals(
            "user tapped Skip: this ask did not apply (no note); " +
                "reword or drop the ask, do not change app code for it",
            out.stopped,
        )
    }

    @Test
    fun valueWithDoubleQuoteIsJsonEscaped() {
        val out = PreviewRun.askOutcome(choice, AskAnswer.Answered("say \"hi\""), 1_000)
        assertEquals("ask: user answered \"say \\\"hi\\\"\" after 1.0 s", out.label)
    }

    @Test
    fun noAnswerInTimeStopsWithoutFailure() {
        val out = PreviewRun.askOutcome(plain, null, 180_000)
        assertNull(out.fail)
        assertEquals("no answer in 3 min; ask the user in chat", out.stopped)
    }
}
