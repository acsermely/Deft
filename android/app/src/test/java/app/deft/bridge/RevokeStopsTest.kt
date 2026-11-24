package app.deft.bridge

import app.deft.bridge.RevokeStops.Live
import app.deft.catalog.Capability
import org.junit.Assert.assertEquals
import org.junit.Test

class RevokeStopsTest {
    @Test
    fun allGrantedStopsNothing() {
        val granted = setOf(
            Capability.CLIPBOARD_WATCH, Capability.SENSORS, Capability.SPEECH_RECOGNIZE,
            Capability.MICROPHONE, Capability.TORCH, Capability.AUDIO_CONTROL,
        )
        assertEquals(emptySet<Live>(), RevokeStops.toStop(granted))
    }

    @Test
    fun eachRevokeStopsItsListener() {
        assertEquals(Live.entries.toSet(), RevokeStops.toStop(emptySet()))
        val all = setOf(
            Capability.CLIPBOARD_WATCH, Capability.SENSORS, Capability.SPEECH_RECOGNIZE,
            Capability.MICROPHONE, Capability.TORCH, Capability.AUDIO_CONTROL,
        )
        assertEquals(setOf(Live.CLIPBOARD_WATCH), RevokeStops.toStop(all - Capability.CLIPBOARD_WATCH))
        assertEquals(setOf(Live.SENSORS), RevokeStops.toStop(all - Capability.SENSORS))
        assertEquals(setOf(Live.TORCH), RevokeStops.toStop(all - Capability.TORCH))
        assertEquals(setOf(Live.AUDIO_ROUTE), RevokeStops.toStop(all - Capability.AUDIO_CONTROL))
        assertEquals(setOf(Live.SPEECH), RevokeStops.toStop(all - Capability.SPEECH_RECOGNIZE))
        // Listening also needs the microphone.
        assertEquals(setOf(Live.SPEECH), RevokeStops.toStop(all - Capability.MICROPHONE))
    }
}
