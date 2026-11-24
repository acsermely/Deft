package app.deft.bridge

import app.deft.bridge.modules.ClipboardWatchRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardWatchRulesTest {
    @Test
    fun sensitiveClipsAreRecognised() {
        // ClipDescription.EXTRA_IS_SENSITIVE has this value (API 33); OEMs use the same key earlier.
        assertTrue(ClipboardWatchRules.sensitive { it == "android.content.extra.IS_SENSITIVE" })
        assertFalse(ClipboardWatchRules.sensitive { false })
        assertFalse(ClipboardWatchRules.sensitive { error("missing") })
    }

    @Test
    fun shortTextIsUnchanged() {
        assertEquals("hello", ClipboardWatchRules.cap("hello"))
    }

    @Test
    fun longAsciiTextIsCappedAtTheLimit() {
        val text = "a".repeat(ClipboardWatchRules.MAX_BYTES + 10)
        assertEquals(ClipboardWatchRules.MAX_BYTES, ClipboardWatchRules.cap(text).length)
    }

    @Test
    fun capNeverSplitsACharacter() {
        // Each euro sign is 3 bytes in UTF-8; the cap must stop before a character that does not fit.
        val euros = "€".repeat(ClipboardWatchRules.MAX_BYTES)
        val capped = ClipboardWatchRules.cap(euros)
        assertTrue(capped.toByteArray(Charsets.UTF_8).size <= ClipboardWatchRules.MAX_BYTES)
        assertTrue(capped.all { it == '€' })
        assertEquals(ClipboardWatchRules.MAX_BYTES / 3, capped.length)
    }

    @Test
    fun capKeepsSurrogatePairsWhole() {
        // U+1F600 is 4 bytes in UTF-8 and two chars in UTF-16.
        val emoji = "😀".repeat(ClipboardWatchRules.MAX_BYTES)
        val capped = ClipboardWatchRules.cap(emoji)
        assertEquals(ClipboardWatchRules.MAX_BYTES / 4 * 2, capped.length)
        assertTrue(capped.toByteArray(Charsets.UTF_8).size <= ClipboardWatchRules.MAX_BYTES)
    }
}
