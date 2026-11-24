package app.deft.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginIdsTest {
    @Test
    fun localIdsAreRandomAndWellFormed() {
        val ids = (1..200).map { OriginIds.local() }.toSet()
        assertEquals(200, ids.size)
        assertTrue(ids.all { OriginIds.isValid(it) })
    }

    @Test
    fun packagedIdIsStablePerKeyAndId() {
        val a = OriginIds.packaged("key-one", "notes")
        assertEquals(a, OriginIds.packaged("key-one\n", "notes"))
        assertNotEquals(a, OriginIds.packaged("key-two", "notes"))
        assertNotEquals(a, OriginIds.packaged("key-one", "todo"))
        assertTrue(OriginIds.isValid(a))
    }

    @Test
    fun packagedIdMatchesSpecHash() {
        // First 20 base32 chars of SHA-256("k/x"), lowercase RFC 4648 alphabet.
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("k/x".toByteArray())
        val bits = digest.joinToString("") { (it.toInt() and 0xff).toString(2).padStart(8, '0') }
        val expected = bits.chunked(5).take(20).joinToString("") { "abcdefghijklmnopqrstuvwxyz234567"[it.padEnd(5, '0').toInt(2)].toString() }
        assertEquals(expected, OriginIds.packaged("k", "x"))
    }

    @Test
    fun neverCollidesWithABundledOriginId() {
        // Bundled ids are 16 hex characters; new ids are 20 base32 characters.
        val bundled = BundledApps.originId("contacts")
        assertEquals(16, bundled.length)
        assertTrue(!OriginIds.isValid(bundled))
        repeat(50) { assertNotEquals(bundled, OriginIds.local()) }
    }

    @Test
    fun twoAppsWithTheSameSlugGetDifferentOrigins() {
        assertNotEquals(MyAppsStore.originFor(OriginIds.local()), MyAppsStore.originFor(OriginIds.local()))
    }
}
