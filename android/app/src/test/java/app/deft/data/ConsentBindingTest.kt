package app.deft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Allow is bound to the manifest the consent screen showed. */
class ConsentBindingTest {
    private val shown = """{"deft":1,"id":"notes","capabilities":[{"id":"haptics","reason":"Buzz"}]}""".toByteArray()

    @Test
    fun sameManifestStillGrants() {
        val h = ConsentBinding.hash(shown)
        assertEquals(64, h.length)
        assertTrue(ConsentBinding.stillShown(h, ConsentBinding.hash(shown.copyOf())))
    }

    @Test
    fun anEditWhileTheScreenWasOpenAsksAgain() {
        val edited = """{"deft":1,"id":"notes","capabilities":[{"id":"haptics","reason":"Buzz"},{"id":"sms.send","reason":"x"}]}""".toByteArray()
        assertNotEquals(ConsentBinding.hash(shown), ConsentBinding.hash(edited))
        assertFalse(ConsentBinding.stillShown(ConsentBinding.hash(shown), ConsentBinding.hash(edited)))
        // Even whitespace counts: the screen showed exactly those bytes.
        assertFalse(ConsentBinding.stillShown(ConsentBinding.hash(shown), ConsentBinding.hash(shown + ' '.code.toByte())))
    }

    @Test
    fun unknownHashesNeverGrant() {
        val h = ConsentBinding.hash(shown)
        assertFalse(ConsentBinding.stillShown(null, h))
        assertFalse(ConsentBinding.stillShown(h, null))
        assertFalse(ConsentBinding.stillShown(null, null))
    }
}
