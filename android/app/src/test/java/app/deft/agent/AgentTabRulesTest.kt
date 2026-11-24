package app.deft.agent

import app.deft.workspace.AgentWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTabRulesTest {
    @Test
    fun homeOnlyTools() {
        assertTrue(AgentTabRules.toolAllowed(AgentWorkspace.HOME_ID, "sessions.list"))
        assertTrue(AgentTabRules.toolAllowed(AgentWorkspace.HOME_ID, "sessions.propose"))
        assertTrue(AgentTabRules.toolAllowed(AgentWorkspace.HOME_ID, "apps.create"))
        assertFalse(AgentTabRules.toolAllowed(AgentWorkspace.HOME_ID, "apps.preview"))
        assertTrue(AgentTabRules.toolAllowed("my-app", "apps.preview"))
        assertTrue(AgentTabRules.toolAllowed(AgentWorkspace.HOME_ID, "canvas.write"))
        assertTrue(AgentTabRules.toolAllowed(AgentWorkspace.HOME_ID, "files.write"))
        assertFalse(AgentTabRules.toolAllowed("notes", "sessions.list"))
        assertFalse(AgentTabRules.toolAllowed("notes", "apps.create"))
        assertFalse(AgentTabRules.toolAllowed("notes", "canvas.write"))
        assertTrue(AgentTabRules.toolAllowed("notes", "files.write"))
        assertTrue(AgentTabRules.toolAllowed("notes", "apps.verify"))
    }

    @Test
    fun proposeDoesNotStartAndRejectsHome() {
        val text = AgentTabRules.proposalFor("notes", null, "  add a clock  ")
        assertEquals("add a clock", text)
        val home = runCatching { AgentTabRules.proposalFor("home", null, "hi") }.exceptionOrNull()
        assertTrue(home is IllegalArgumentException)
        val second = runCatching { AgentTabRules.proposalFor("notes", "already", "hi") }.exceptionOrNull()
        assertTrue(second is IllegalArgumentException)
        assertTrue(second!!.message!!.contains("already"))
    }

    @Test
    fun handoffNeedsIdleMyAppTab() {
        assertEquals("build a timer", AgentTabRules.handoffFor("timer", busy = false, pending = false, prompt = " build a timer "))
        val home = runCatching { AgentTabRules.handoffFor("home", false, false, "hi") }.exceptionOrNull()
        assertTrue(home is IllegalArgumentException)
        val blank = runCatching { AgentTabRules.handoffFor("timer", false, false, "  ") }.exceptionOrNull()
        assertTrue(blank is IllegalArgumentException)
        val busy = runCatching { AgentTabRules.handoffFor("timer", true, false, "hi") }.exceptionOrNull()
        assertTrue(busy!!.message!!.contains("sessions.propose"))
        val pending = runCatching { AgentTabRules.handoffFor("timer", false, true, "hi") }.exceptionOrNull()
        assertTrue(pending is IllegalArgumentException)
    }

    @Test
    fun tabStatePriority() {
        assertEquals("working", AgentTabRules.tabState(busy = true, pending = true, hasProposal = true))
        assertEquals("needs_apply", AgentTabRules.tabState(busy = false, pending = true, hasProposal = true))
        assertEquals("suggestion", AgentTabRules.tabState(busy = false, pending = false, hasProposal = true))
        assertEquals("idle", AgentTabRules.tabState(busy = false, pending = false, hasProposal = false))
    }

    @Test
    fun localRefReachesOnlyKnownApps() {
        val known = setOf("weather")
        assertEquals(AgentTabRules.LocalRef("weather", "main.js"), AgentTabRules.localRef("local/weather/main.js", known))
        assertEquals(AgentTabRules.LocalRef("weather", "."), AgentTabRules.localRef("local/weather", known))
        assertEquals(null, AgentTabRules.localRef("index.html", known))
        assertTrue(runCatching { AgentTabRules.localRef("local/other/main.js", known) }.isFailure)
        assertTrue(runCatching { AgentTabRules.localRef("local/../x", known) }.isFailure)
    }

    @Test
    fun homeEditsOnlyPlacedAppsThatDeclareAWidgetOrThatItCreated() {
        val placed = listOf("myapp:weather", "myapp:sms-tool", "builtin:clock")
        val withWidget = setOf("weather")
        val editable = AgentTabRules.homeEditable(placed, setOf("draft")) { it in withWidget }
        assertEquals(setOf("weather", "draft"), editable)
        assertFalse("sms-tool" in editable)
    }
}
