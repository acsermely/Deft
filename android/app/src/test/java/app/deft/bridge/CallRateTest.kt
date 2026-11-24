package app.deft.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRateTest {
    @Test
    fun limitsPerKeyInASlidingWindow() {
        val r = CallRate(2, 1000)
        assertTrue(r.tryAcquire("a", 0))
        assertTrue(r.tryAcquire("a", 100))
        assertFalse(r.tryAcquire("a", 200))
        assertTrue(r.tryAcquire("b", 200))
        assertTrue(r.tryAcquire("a", 1000)) // the first call left the window
        assertFalse(r.tryAcquire("a", 1050))
    }
}
