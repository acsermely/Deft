package app.deft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipAspectTest {
    @Test
    fun missingAspectIsSixteenByNine() {
        assertEquals(PipRatio(16, 9), PipAspect.parse(null))
    }

    @Test
    fun wholeNumberPairsAreAccepted() {
        assertEquals(PipRatio(16, 9), PipAspect.parse("16:9"))
        assertEquals(PipRatio(1, 1), PipAspect.parse("1:1"))
        assertEquals(PipRatio(9, 16), PipAspect.parse("9:16"))
    }

    @Test
    fun malformedOrOutOfRangeIsRejected() {
        assertNull(PipAspect.parse(""))
        assertNull(PipAspect.parse("16"))
        assertNull(PipAspect.parse("16:9:1"))
        assertNull(PipAspect.parse("a:b"))
        assertNull(PipAspect.parse("0:9"))
        assertNull(PipAspect.parse("16:-9"))
        assertNull(PipAspect.parse("10001:1"))
    }

    @Test
    fun ratiosPastTheAndroidLimitAreCutBack() {
        // Android accepts 1:2.39 to 2.39:1 only.
        assertEquals(PipRatio(2, 1), PipAspect.parse("100:1"))
        assertEquals(PipRatio(1, 2), PipAspect.parse("1:100"))
    }
}
