package app.deft.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsReadArgsTest {
    @Test fun limitAloneStartsAtLineOne() {
        assertEquals(1, AgentTools.readStartLine("", "", 40)!!.getOrThrow())
    }

    @Test fun lineWinsAndOffsetModeIsKept() {
        assertEquals(12, AgentTools.readStartLine("12", "", 5)!!.getOrThrow())
        assertNull(AgentTools.readStartLine("", "200", null))
        assertNull(AgentTools.readStartLine("", "", null))
        assertTrue(AgentTools.readStartLine("x", "", null)!!.isFailure)
        assertThrows(IllegalArgumentException::class.java) { AgentTools.readStartLine("", "200", 5) }
    }

    @Test fun wholeNumbersMaySendAsFloatsButFractionsAndTextAreRefused() {
        assertEquals(40, AgentTools.intArg("40", "limit"))
        assertEquals(40, AgentTools.intArg("40.0", "limit"))
        assertEquals(40, AgentTools.intArg(" 40 ", "limit"))
        assertNull(AgentTools.intArg("", "limit"))
        assertThrows(IllegalArgumentException::class.java) { AgentTools.intArg("40.5", "limit") }
        assertThrows(IllegalArgumentException::class.java) { AgentTools.intArg("forty", "limit") }
        assertThrows(IllegalArgumentException::class.java) { AgentTools.intArg("1e12", "limit") }
        assertThrows(IllegalArgumentException::class.java) { AgentTools.intArg("NaN", "limit") }
        assertEquals(12, AgentTools.readStartLine("12.0", "", null)!!.getOrThrow())
        assertTrue(AgentTools.readStartLine("1.5", "", null)!!.isFailure)
    }

    @Test fun emptyOldStringPointsToWrite() {
        assertTrue(AgentTools.OLD_STRING_REQUIRED.contains("files.write"))
    }
}
