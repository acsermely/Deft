package app.deft.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLogNoteTest {
    @Test
    fun errorsKeepTheirTextUpTo200Chars() {
        val note = AgentLog.toolNote("files.read", "error: not found\n" + "x".repeat(500))!!
        assertEquals(200, note.length)
        assertTrue(note.startsWith("error: not found x"))
    }

    @Test
    fun verifyAndDebugToolsKeepOnlyWarnAndErrorLines() {
        val out = "loaded ok\nSECRET file body\nWARN widget deft-widget app=\"myapp:s\" needs setup\nERROR console boom @ main.js:3\nINFO fine"
        val note = AgentLog.toolNote("canvas.verify", out)!!
        assertTrue(note.contains("needs setup") && note.contains("boom"))
        assertTrue(!note.contains("SECRET") && !note.contains("INFO"))
        assertEquals(400, AgentLog.toolNote("apps.debug", "WARN " + "y".repeat(900))!!.length)
        assertNull(AgentLog.toolNote("apps.verify", "all clean"))
    }

    @Test
    fun otherToolsNeverLogTheirResults() {
        assertNull(AgentLog.toolNote("files.read", "WARN secret contents"))
        assertNull(AgentLog.toolNote("web.fetch", "ERROR from a page"))
    }

    @Test
    fun urlsLoseTheirQueryStringAndFragment() {
        val note = AgentLog.toolNote("apps.debug", "ERROR fetch https://api.example.com/v1/x?token=abc&q=1#frag failed")!!
        assertEquals("ERROR fetch https://api.example.com/v1/x failed", note)
        assertEquals("error: bad https://a.b/c", AgentLog.toolNote("files.read", "error: bad https://a.b/c?k=secret"))
    }
}
