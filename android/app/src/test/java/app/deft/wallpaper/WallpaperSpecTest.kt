package app.deft.wallpaper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WallpaperSpecTest {
    private val t = 1_000_000L

    @Test
    fun touchActionsAreMapped() {
        assertEquals("down", WallpaperSpec.touchAction(0))
        assertEquals("up", WallpaperSpec.touchAction(1))
        assertEquals("move", WallpaperSpec.touchAction(2))
        assertEquals("cancel", WallpaperSpec.touchAction(3))
        // Outside, hover and second-finger actions are not sent.
        for (other in listOf(4, 5, 6, 7, 9, 10)) assertNull(WallpaperSpec.touchAction(other))
    }

    @Test
    fun movesAreThrottledOthersAlwaysPass() {
        assertTrue(WallpaperSpec.touchPasses("move", null, t))
        assertFalse(WallpaperSpec.touchPasses("move", t, t + 32))
        assertTrue(WallpaperSpec.touchPasses("move", t, t + 33))
        assertTrue("clock went back", WallpaperSpec.touchPasses("move", t, t - 1))
        for (action in listOf("down", "up", "cancel", "tap")) {
            assertTrue(action, WallpaperSpec.touchPasses(action, t, t + 1))
        }
    }

    @Test
    fun offsetsWaitForTheGapThenSendTheLatest() {
        assertEquals(0L, WallpaperSpec.offsetsDelay(null, t))
        assertEquals(33L, WallpaperSpec.offsetsDelay(t, t))
        assertEquals(13L, WallpaperSpec.offsetsDelay(t, t + 20))
        assertEquals(0L, WallpaperSpec.offsetsDelay(t, t + 33))
        assertEquals(0L, WallpaperSpec.offsetsDelay(t, t - 5))
    }

    @Test
    fun frameRateFollowsVisibilityAndBatterySaver() {
        assertEquals(0, WallpaperSpec.targetFps(visible = false, powerSave = false))
        assertEquals(0, WallpaperSpec.targetFps(visible = false, powerSave = true))
        assertEquals(30, WallpaperSpec.targetFps(visible = true, powerSave = false))
        assertEquals(15, WallpaperSpec.targetFps(visible = true, powerSave = true))
        assertEquals(0, WallpaperSpec.frameGapMs(0))
        assertEquals(33, WallpaperSpec.frameGapMs(30))
        assertEquals(67, WallpaperSpec.frameGapMs(15))
    }

    @Test
    fun frameCapScriptCarriesTheGap() {
        val js = WallpaperSpec.frameCapJs(33)
        assertTrue("var gap=33," in js)
        assertTrue("__deftWallpaperFrameGap" in js)
        assertTrue("window.requestAnimationFrame=" in js)
        assertTrue("window.cancelAnimationFrame=" in js)
    }

    @Test
    fun useIsLoggedAtMostDaily() {
        assertTrue(WallpaperSpec.shouldLogUse(null, t))
        assertFalse(WallpaperSpec.shouldLogUse(t, t + WallpaperSpec.USE_LOG_GAP_MS - 1))
        assertTrue(WallpaperSpec.shouldLogUse(t, t + WallpaperSpec.USE_LOG_GAP_MS))
        assertTrue(WallpaperSpec.shouldLogUse(t, t - 1))
    }

    @Test
    fun touchPayloadIsInCssPixels() {
        val obj = Json.parseToJsonElement(WallpaperSpec.touchPayload(300f, 451f, 3f, "down")).jsonObject
        assertEquals(100.0, obj["x"]!!.jsonPrimitive.double, 0.0)
        assertEquals(150.3, obj["y"]!!.jsonPrimitive.double, 0.0)
        assertEquals("down", obj["action"]!!.jsonPrimitive.content)
        assertEquals(setOf("x", "y", "action"), obj.keys)
        val noDensity = Json.parseToJsonElement(WallpaperSpec.touchPayload(10f, 20f, 0f, "tap")).jsonObject
        assertEquals(10.0, noDensity["x"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun offsetsPayloadIsClamped() {
        val obj = Json.parseToJsonElement(WallpaperSpec.offsetsPayload(0.25f, 1.5f)).jsonObject
        assertEquals(setOf("xOffset", "yOffset"), obj.keys)
        assertEquals(0.25, obj["xOffset"]!!.jsonPrimitive.double, 1e-6)
        assertEquals(1.0, obj["yOffset"]!!.jsonPrimitive.double, 0.0)
        val nan = Json.parseToJsonElement(WallpaperSpec.offsetsPayload(Float.NaN, -1f)).jsonObject
        assertEquals(0.0, nan["xOffset"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.0, nan["yOffset"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun wallpaperXmlHasLabelDescriptionAndThumbnail() {
        val xml = File("src/main/res/xml/wallpaper.xml").readText()
        assertTrue("android:description=" in xml)
        assertTrue("android:thumbnail=" in xml)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = manifest.substringAfter("android:name=\".wallpaper.DeftWallpaperService\"").substringBefore("</service>")
        assertTrue("android:label=" in service)
        assertTrue("@xml/wallpaper" in service)
    }
}
