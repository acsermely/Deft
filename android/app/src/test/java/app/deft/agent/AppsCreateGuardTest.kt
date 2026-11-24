package app.deft.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsCreateGuardTest {
    @Test fun homeScreenRequestIsBlocked() {
        assertNotNull(AgentTabRules.createGuard("add a clock to my home screen", false))
        assertNotNull(AgentTabRules.createGuard("new wallpaper with a clock", false))
        assertNotNull(AgentTabRules.createGuard("put a photo on the canvas", false))
    }

    @Test fun topicWordsNoLongerPass() {
        assertNotNull(AgentTabRules.createGuard("add the weather to my home screen", false))
        assertNotNull(AgentTabRules.createGuard("Rakj időjárást a kezdőképernyőre", false))
        assertNotNull(AgentTabRules.createGuard("show my steps today on home", false))
    }

    @Test fun purposeWidgetOrToolsPasses() {
        assertNull(AgentTabRules.createGuard("put the bus times on my home screen", false, "widget"))
        assertNull(AgentTabRules.createGuard("Rakj időjárást a kezdőképernyőre", false, "Widget"))
        assertNull(AgentTabRules.createGuard("on my home screen, a lookup tool", false, "tools"))
        assertNotNull(AgentTabRules.createGuard("a clock on my home screen", false, "app"))
    }

    @Test fun badPurposeIsRejected() {
        val err = AgentTabRules.createGuard("build a timer", false, "gadget")
        assertTrue(err!!.contains("purpose"))
    }

    @Test fun structuralWidgetWordPasses() {
        assertNull(AgentTabRules.createGuard("add a widget to my home screen", false))
    }

    @Test fun appRequestOrConfirmedPasses() {
        assertNull(AgentTabRules.createGuard("make a weather app for my home screen", false))
        assertNull(AgentTabRules.createGuard("build a timer", false))
        assertNull(AgentTabRules.createGuard("weather on home", true))
        assertNull(AgentTabRules.createGuard("clock on my home screen", true))
    }

    @Test fun homeWordsInOtherContextsPass() {
        assertNull(AgentTabRules.createGuard("a tracker for my home workouts", false))
        assertNull(AgentTabRules.createGuard("homework planner", false))
        assertNull(AgentTabRules.createGuard("track home automation scenes", false))
        assertNotNull(AgentTabRules.createGuard("put a clock on my home", false))
    }
}
