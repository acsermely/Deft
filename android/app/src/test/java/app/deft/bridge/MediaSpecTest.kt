package app.deft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSpecTest {
    @Test
    fun queryNeedsAKnownKind() {
        assertNull(MediaSpec.parseQuery(null, null, null, null))
        assertNull(MediaSpec.parseQuery("photos", null, null, null))
        assertEquals("images", MediaSpec.parseQuery("images", null, null, null)?.kind)
        assertEquals("video", MediaSpec.parseQuery("video", null, null, null)?.kind)
        assertEquals("audio", MediaSpec.parseQuery("audio", null, null, null)?.kind)
    }

    @Test
    fun queryDefaultsAndLimits() {
        val plain = MediaSpec.parseQuery("images", null, null, null)!!
        assertEquals(MediaSpec.DEFAULT_LIMIT, plain.limit)
        assertEquals(0, plain.offset)
        assertNull(plain.sinceMs)
        assertEquals(500, MediaSpec.parseQuery("images", null, "500", null)?.limit)
        assertNull(MediaSpec.parseQuery("images", null, "501", null))
        assertNull(MediaSpec.parseQuery("images", null, "0", null))
        assertNull(MediaSpec.parseQuery("images", null, "many", null))
        assertNull(MediaSpec.parseQuery("images", null, null, "-1"))
        assertEquals(40, MediaSpec.parseQuery("images", null, null, "40")?.offset)
    }

    @Test
    fun sinceIsEpochMilliseconds() {
        assertEquals(1_700_000_000_000L, MediaSpec.parseQuery("audio", "1700000000000", null, null)?.sinceMs)
        assertNull(MediaSpec.parseQuery("audio", "-5", null, null))
        assertNull(MediaSpec.parseQuery("audio", "yesterday", null, null))
    }

    @Test
    fun thumbnailSizeIsBetween64And512() {
        assertEquals(MediaSpec.DEFAULT_THUMB, MediaSpec.parseThumbSize(null))
        assertEquals(64, MediaSpec.parseThumbSize("64"))
        assertEquals(512, MediaSpec.parseThumbSize("512"))
        assertNull(MediaSpec.parseThumbSize("63"))
        assertNull(MediaSpec.parseThumbSize("513"))
        assertNull(MediaSpec.parseThumbSize("big"))
    }

    @Test
    fun idsArePositiveNumbers() {
        assertEquals(42L, MediaSpec.parseId("42"))
        assertNull(MediaSpec.parseId("0"))
        assertNull(MediaSpec.parseId("-3"))
        assertNull(MediaSpec.parseId("abc"))
        assertNull(MediaSpec.parseId(null))
    }

    @Test
    fun columnsDependOnKind() {
        for (kind in MediaSpec.KINDS) {
            assertTrue(MediaSpec.ID in MediaSpec.columns(kind))
        }
        assertTrue(MediaSpec.WIDTH in MediaSpec.columns("images"))
        assertTrue(MediaSpec.DATE_TAKEN in MediaSpec.columns("images"))
        assertFalse(MediaSpec.DATE_TAKEN in MediaSpec.columns("audio"))
        assertFalse(MediaSpec.WIDTH in MediaSpec.columns("audio"))
        assertTrue(MediaSpec.DURATION in MediaSpec.columns("video"))
        assertTrue(MediaSpec.DURATION in MediaSpec.columns("audio"))
        assertTrue(MediaSpec.ALBUM in MediaSpec.columns("audio"))
    }
}
