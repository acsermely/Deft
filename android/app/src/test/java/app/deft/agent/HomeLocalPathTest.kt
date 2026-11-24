package app.deft.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeLocalPathTest {
    private val placed = setOf("clock", "weather")

    @Test
    fun placedAppPathsResolveToTheAppFolder() {
        assertEquals(AgentTabRules.LocalRef("clock", "index.html"), AgentTabRules.localRef("local/clock/index.html", placed))
        assertEquals(AgentTabRules.LocalRef("clock", "sub/a.js"), AgentTabRules.localRef("local/clock/sub/a.js", placed))
        assertEquals(AgentTabRules.LocalRef("clock", "."), AgentTabRules.localRef("local/clock", placed))
        assertEquals(AgentTabRules.LocalRef("weather", "."), AgentTabRules.localRef("local/weather/", placed))
    }

    @Test
    fun otherPathsAreNotLocal() {
        assertNull(AgentTabRules.localRef("index.html", placed))
        assertNull(AgentTabRules.localRef("local", placed))
        assertNull(AgentTabRules.localRef("", placed))
    }

    @Test
    fun unplacedAppsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { AgentTabRules.localRef("local/notes/index.html", placed) }
        assertThrows(IllegalArgumentException::class.java) { AgentTabRules.localRef("local/notes/index.html", emptySet()) }
    }

    @Test
    fun malformedSlugsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { AgentTabRules.localRef("local/", placed) }
        assertThrows(IllegalArgumentException::class.java) { AgentTabRules.localRef("local/../clock/x", placed) }
        assertThrows(IllegalArgumentException::class.java) { AgentTabRules.localRef("local/home/x", placed) }
    }
}
