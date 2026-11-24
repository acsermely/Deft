package app.deft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkRowsTest {
    @Test
    fun limitDefaultsAndClamps() {
        assertEquals(50, BulkRows.limit(null))
        assertEquals(100, BulkRows.limit(null, default = 100))
        assertEquals(1, BulkRows.limit(0))
        assertEquals(1, BulkRows.limit(-5))
        assertEquals(BulkRows.MAX_LIMIT, BulkRows.limit(10_000_000))
    }

    @Test
    fun offsetIsNeverNegative() {
        assertEquals(0, BulkRows.offset(null))
        assertEquals(0, BulkRows.offset(-1))
        assertEquals(BulkRows.MAX_OFFSET, BulkRows.offset(Int.MAX_VALUE))
    }

    @Test
    fun confirmOnlyAboveFiveHundred() {
        assertFalse(BulkRows.needsConfirm(500))
        assertTrue(BulkRows.needsConfirm(501))
        assertTrue(BulkRows.needsConfirm(BulkRows.MAX_LIMIT))
    }

    @Test
    fun metadataHoldsRowCountOnly() {
        assertEquals("{\"rows\":0}", BulkRows.metadata(0))
        assertEquals("{\"rows\":1200}", BulkRows.metadata(1200))
    }

    @Test
    fun pagingPastFiveHundredInTheWindowAsksOnce() {
        val w = BulkRows.ReadWindow(windowMs = 1_000)
        assertFalse(w.needsConfirm("a", 100, now = 0))
        repeat(5) { w.record("a", 100, now = it.toLong()) }
        // 500 read: the next page would pass the limit.
        assertTrue(w.needsConfirm("a", 1, now = 10))
        // Another app or kind has its own count.
        assertFalse(w.needsConfirm("b", 100, now = 10))
        w.confirmed("a", now = 10)
        w.record("a", 100, now = 11)
        assertFalse(w.needsConfirm("a", 100, now = 12))
        // A single big read still asks every time.
        assertTrue(w.needsConfirm("a", 501, now = 12))
        // After the window the confirm and the old reads are gone.
        assertFalse(w.needsConfirm("a", 100, now = 1_020))
        repeat(5) { w.record("a", 100, now = 1_020) }
        assertTrue(w.needsConfirm("a", 1, now = 1_021))
    }

    @Test
    fun oldReadsLeaveTheWindow() {
        val w = BulkRows.ReadWindow(windowMs = 1_000)
        w.record("a", 400, now = 0)
        assertTrue(w.needsConfirm("a", 200, now = 500))
        assertFalse(w.needsConfirm("a", 200, now = 1_000))
    }
}
