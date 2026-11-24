package app.deft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSpecTest {
    @Test
    fun missingRateAndPitchMeanNormal() {
        assertEquals(1f, SpeechSpec.rateOrPitch(null)!!, 0f)
        assertEquals(0.5f, SpeechSpec.rateOrPitch(null, 0.5f)!!, 0f)
    }

    @Test
    fun ratesAreClampedToQuarterToFour() {
        assertEquals(0.25f, SpeechSpec.rateOrPitch("0")!!, 0f)
        assertEquals(0.25f, SpeechSpec.rateOrPitch("-2")!!, 0f)
        assertEquals(4f, SpeechSpec.rateOrPitch("9")!!, 0f)
        assertEquals(1.5f, SpeechSpec.rateOrPitch("1.5")!!, 0f)
    }

    @Test
    fun ratesThatAreNotNumbersAreRejected() {
        assertNull(SpeechSpec.rateOrPitch(""))
        assertNull(SpeechSpec.rateOrPitch("fast"))
        assertNull(SpeechSpec.rateOrPitch("NaN"))
        assertNull(SpeechSpec.rateOrPitch("Infinity"))
    }

    @Test
    fun langTagsHaveBcp47Shape() {
        assertTrue(SpeechSpec.isLangTag("en"))
        assertTrue(SpeechSpec.isLangTag("en-US"))
        assertTrue(SpeechSpec.isLangTag("zh-Hant-TW"))
        assertTrue(SpeechSpec.isLangTag("de-CH-1901"))
    }

    @Test
    fun malformedLangTagsAreRejected() {
        assertFalse(SpeechSpec.isLangTag(""))
        assertFalse(SpeechSpec.isLangTag("e"))
        assertFalse(SpeechSpec.isLangTag("en_US"))
        assertFalse(SpeechSpec.isLangTag("en-"))
        assertFalse(SpeechSpec.isLangTag("en US"))
        assertFalse(SpeechSpec.isLangTag("en-US-verylongsubtagvalue"))
        assertFalse(SpeechSpec.isLangTag("a".repeat(40)))
    }

    @Test
    fun alternativesAreTheOtherCandidates() {
        // Review: the top result is `text`; the rest (up to 5, no blanks) are the alternatives.
        assertEquals(emptyList<String>(), SpeechSpec.alternatives(null))
        assertEquals(emptyList<String>(), SpeechSpec.alternatives(listOf("hello")))
        assertEquals(listOf("yellow", "fellow"), SpeechSpec.alternatives(listOf("hello", "yellow", " ", "fellow")))
        assertEquals(listOf("b", "c", "d", "e", "f"), SpeechSpec.alternatives(listOf("a", "b", "c", "d", "e", "f", "g")))
    }

    @Test
    fun speechIsQueuedBehindAnotherApp() {
        assertTrue(SpeechSpec.flushes("a", emptyList()))
        assertTrue(SpeechSpec.flushes("a", listOf("a", "a")))
        assertFalse(SpeechSpec.flushes("a", listOf("a", "b")))
        assertFalse(SpeechSpec.flushes("a", listOf("b")))
    }

    @Test
    fun onlyTheSpeakingAppMayStop() {
        assertTrue(SpeechSpec.mayStop("a", "a", listOf("a", "b")))
        assertFalse(SpeechSpec.mayStop("a", "b", listOf("a", "b")))
        assertTrue(SpeechSpec.mayStop("a", null, listOf("a")))
        assertFalse(SpeechSpec.mayStop("a", null, listOf("a", "b")))
        assertFalse(SpeechSpec.mayStop("a", null, emptyList()))
    }
}
