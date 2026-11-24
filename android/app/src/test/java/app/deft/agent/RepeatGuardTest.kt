package app.deft.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatGuardTest {
    private val verifyOut = "ERROR console [uncaught] unhandled AbortError: Transition was skipped."

    @Test
    fun theMeasuringTapeLoopIsStoppedOnTheFourthIdenticalVerify() {
        // The thread: verify, clear, verify, clear, … with the same findings every time.
        val guard = RepeatGuard()
        assertEquals(RepeatGuard.Verdict.Ok, guard.check("apps.verify", "{}", verifyOut))
        assertEquals(RepeatGuard.Verdict.Ok, guard.check("apps.debug.clear", "{}", "cleared"))
        assertTrue(guard.check("apps.verify", "{}", verifyOut) is RepeatGuard.Verdict.Note)
        assertTrue(guard.check("apps.debug.clear", "{}", "cleared") is RepeatGuard.Verdict.Note)
        assertTrue(guard.check("apps.verify", "{}", verifyOut) is RepeatGuard.Verdict.Note)
        val stop = guard.check("apps.verify", "{}", verifyOut)
        assertTrue(stop is RepeatGuard.Verdict.Stop)
        assertTrue((stop as RepeatGuard.Verdict.Stop).text.contains("apps.verify"))
    }

    @Test
    fun editsInBetweenDoNotResetButAChangedResultDoes() {
        val guard = RepeatGuard()
        guard.check("apps.verify", "{}", verifyOut)
        guard.check("files.write", """{"path":"a.js","content":"x"}""", "wrote a.js")
        assertTrue(guard.check("apps.verify", "{}", verifyOut) is RepeatGuard.Verdict.Note)
        assertEquals(RepeatGuard.Verdict.Ok, guard.check("apps.verify", "{}", "ok, no findings"))
        assertTrue(guard.check("apps.verify", "{}", "ok, no findings") is RepeatGuard.Verdict.Note)
    }

    @Test
    fun differentArgumentsAreDifferentCallsAndWhitespaceIsIgnored() {
        val guard = RepeatGuard()
        guard.check("files.read", """{"path":"a.js"}""", "same")
        assertEquals(RepeatGuard.Verdict.Ok, guard.check("files.read", """{"path":"b.js"}""", "same"))
        assertTrue(guard.check("files.read", """{ "path": "a.js" }""", "same") is RepeatGuard.Verdict.Note)
        assertTrue(guard.check("apps.verify", "", verifyOut) is RepeatGuard.Verdict.Ok)
        assertTrue(guard.check("apps.verify", "{}", verifyOut) is RepeatGuard.Verdict.Note)
    }
}
