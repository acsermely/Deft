package app.deft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemStatsSpecTest {
    @Test
    fun conversions() {
        assertEquals("severe", SystemStatsSpec.thermalName(3))
        assertEquals("unknown", SystemStatsSpec.thermalName(-1))
        assertEquals(50, SystemStatsSpec.percent(25, 50))
        assertNull(SystemStatsSpec.percent(-1, 100))
        assertNull(SystemStatsSpec.percent(5, 0))
        assertEquals(1800, SystemStatsSpec.mhz("1800000\n"))
        assertNull(SystemStatsSpec.mhz("n/a"))
        assertNull(SystemStatsSpec.mhz(null))
        assertEquals(-350, SystemStatsSpec.milliAmps(-350_000))
        assertNull(SystemStatsSpec.milliAmps(Long.MIN_VALUE))
    }
}
