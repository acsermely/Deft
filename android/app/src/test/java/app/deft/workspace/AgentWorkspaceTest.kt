package app.deft.workspace

import app.deft.agent.AgentContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentWorkspaceTest {
    @Test
    fun homeSlugs() {
        assertTrue(AgentWorkspace.isHomeSlug("home"))
        assertTrue(AgentWorkspace.isHomeSlug("home-canvas"))
        assertTrue(AgentWorkspace.isHomeSlug("HOME"))
        assertEquals(false, AgentWorkspace.isHomeSlug("notes"))
        assertEquals(false, AgentWorkspace.isHomeSlug(null))
    }

    @Test
    fun reservedMyAppSlugs() {
        val err = runCatching { MyAppsStore.requireSlug("home") }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertTrue(err!!.message!!.contains("reserved"))
        assertEquals("notes", MyAppsStore.requireSlug("notes"))
    }

    @Test
    fun workspaceLineForHome() {
        val line = AgentContext.workspaceLine("home")
        assertTrue(line.contains("Home"))
        assertTrue(line.contains("home-canvas"))
    }

    @Test
    fun workspaceLinePinsMyApp() {
        val line = AgentContext.workspaceLine("notes")
        assertTrue(line.contains("notes"))
        assertTrue(line.contains("this folder"))
        assertEquals(false, line.contains("Download"))
    }

    @Test
    fun homeIsAlwaysOpenAndCannotClose() {
        val ws = AgentWorkspace()
        assertEquals(listOf(AgentWorkspace.HOME_ID), ws.tabs.value.map { it.id })
        assertEquals(AgentWorkspace.HOME_ID, ws.selected.value)
        assertTrue(ws.isHome())
        val notes = ws.openWorkspace("notes")
        ws.openWorkspace("notes")
        assertEquals(listOf(AgentWorkspace.HOME_ID, notes), ws.tabs.value.map { it.id })
        assertEquals("notes", ws.tab(notes)?.workspaceId)
        ws.select(notes)
        assertEquals(notes, ws.selected.value)
        ws.close(notes)
        assertEquals(listOf(AgentWorkspace.HOME_ID), ws.tabs.value.map { it.id })
        assertEquals(AgentWorkspace.HOME_ID, ws.selected.value)
        val closeHome = runCatching { ws.close("home") }.exceptionOrNull()
        assertTrue(closeHome is IllegalArgumentException)
    }

    @Test
    fun plusOpensUntitledThenBind() {
        val ws = AgentWorkspace()
        val id = ws.openEmpty()
        assertTrue(id.startsWith(AgentWorkspace.EMPTY_PREFIX))
        assertNull(ws.tab(id)?.workspaceId)
        assertEquals(id, ws.openEmpty())
        ws.bind(id, "notes")
        assertEquals("notes", ws.tab(id)?.workspaceId)
        assertEquals(listOf("notes"), ws.recents())
        ws.bind(id, "timer")
        assertEquals("notes", ws.tab(id)?.workspaceId)
    }

    @Test
    fun plusBringsUntitledToEnd() {
        val ws = AgentWorkspace()
        val empty = ws.openEmpty()
        val notes = ws.openWorkspace("notes")
        assertEquals(listOf(AgentWorkspace.HOME_ID, empty, notes), ws.tabs.value.map { it.id })
        assertEquals(empty, ws.openEmpty())
        assertEquals(listOf(AgentWorkspace.HOME_ID, notes, empty), ws.tabs.value.map { it.id })
        ws.select(empty)
        assertEquals(empty, ws.selected.value)
        ws.bind(empty, "timer")
        assertEquals(empty, ws.selected.value)
        assertEquals("timer", ws.tab(empty)?.workspaceId)
    }

    @Test
    fun oneBoundTabPerWorkspace() {
        val ws = AgentWorkspace()
        val a = ws.openWorkspace("notes")
        val b = ws.openWorkspace("notes")
        assertEquals(a, b)
        assertEquals(1, ws.tabs.value.count { it.workspaceId == "notes" })
    }

    @Test
    fun recentsMostRecentFirstAndPersist() {
        val file = File.createTempFile("agent-recents", ".txt")
        file.deleteOnExit()
        val ws = AgentWorkspace(file)
        ws.openWorkspace("notes")
        ws.openWorkspace("timer")
        ws.openWorkspace("notes")
        assertEquals(listOf("notes", "timer"), ws.recents())
        val reloaded = AgentWorkspace(file)
        assertEquals(listOf("notes", "timer"), reloaded.recents())
        reloaded.forgetRecent("notes")
        assertEquals(listOf("timer"), reloaded.recents())
        assertEquals(listOf("timer"), AgentWorkspace(file).recents())
    }
}
