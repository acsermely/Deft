package app.deft.bridge

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AudioSpecTest {
    @Test
    fun valuesAreWholeNumbers() {
        assertEquals(0, AudioSpec.parseValue("0"))
        assertEquals(7, AudioSpec.parseValue("7"))
        assertEquals(-3, AudioSpec.parseValue("-3"))
        assertNull(AudioSpec.parseValue(null))
        assertNull(AudioSpec.parseValue(""))
        assertNull(AudioSpec.parseValue("loud"))
        assertNull(AudioSpec.parseValue("1.5"))
    }

    @Test
    fun valuesAreClampedIntoRange() {
        assertEquals(0, AudioSpec.clamp(-4, 15))
        assertEquals(9, AudioSpec.clamp(9, 15))
        assertEquals(15, AudioSpec.clamp(99, 15))
        assertEquals(0, AudioSpec.clamp(5, 0))
    }

    @Test
    fun namesAreFixed() {
        assertEquals(setOf("music", "ring", "alarm", "notification", "voiceCall"), AudioSpec.STREAMS)
        assertEquals(setOf("normal", "vibrate", "silent"), AudioSpec.RINGER_MODES)
        assertEquals(setOf("speaker", "earpiece", "bluetooth", "default"), AudioSpec.ROUTE_TARGETS)
    }

    @Test
    fun audioArgsAreChecked() {
        assertNotNull(BridgeArgs.validate("audio.setVolume", buildJsonObject { put("stream", JsonPrimitive("alarm")) }))
        assertNull(BridgeArgs.validate("audio.setVolume", buildJsonObject { put("stream", JsonPrimitive("alarm")); put("value", JsonPrimitive("3")) }))
        assertNotNull(BridgeArgs.validate("audio.setRingerMode", buildJsonObject {}))
        assertNotNull(BridgeArgs.validate("audio.route", buildJsonObject { put("target", JsonPrimitive("x".repeat(17))) }))
        assertNull(BridgeArgs.validate("audio.getVolumes", buildJsonObject {}))
    }
}
