package app.deft.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSettingsTest {
    @Test fun choosingAnAccentOrAppearanceKeepsTheOtherSwitches() {
        val current = ThemeSettings(appearance = "dark", reduceTransparency = true, monochromeIcons = false)
        val next = current.withChoice("blue")
        assertEquals(ThemeSettings.PRESETS.getValue("blue"), next.accent)
        assertEquals("dark", next.appearance)
        assertTrue(next.reduceTransparency)
        assertFalse(next.monochromeIcons)
        val light = current.withChoice("red", "light")
        assertEquals("light", light.appearance)
        assertFalse(light.monochromeIcons)
    }

    @Test fun unknownAccentIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { ThemeSettings().withChoice("not-a-colour") }
    }
}
