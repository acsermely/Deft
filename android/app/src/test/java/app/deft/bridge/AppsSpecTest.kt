package app.deft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsSpecTest {
    @Test
    fun packageNamesAreDottedAndBounded() {
        assertTrue(AppsSpec.validPackage("com.example.app"))
        assertTrue(AppsSpec.validPackage("org.signal.app_2"))
        assertFalse(AppsSpec.validPackage("nodots"))
        assertFalse(AppsSpec.validPackage("com..example"))
        assertFalse(AppsSpec.validPackage("1com.example"))
        assertFalse(AppsSpec.validPackage("com.example/Main"))
        assertFalse(AppsSpec.validPackage(""))
        assertFalse(AppsSpec.validPackage(null))
        assertFalse(AppsSpec.validPackage("a." + "b".repeat(254)))
    }

    @Test
    fun usageRangeNeedsBothEndsInOrder() {
        assertEquals(1000L to 2000L, AppsSpec.usageRange("1000", "2000"))
        assertEquals(0L to 1L, AppsSpec.usageRange("0", "1"))
        assertNull(AppsSpec.usageRange(null, "2000"))
        assertNull(AppsSpec.usageRange("1000", null))
        assertNull(AppsSpec.usageRange("2000", "2000"))
        assertNull(AppsSpec.usageRange("2000", "1000"))
        assertNull(AppsSpec.usageRange("-1", "1000"))
        assertNull(AppsSpec.usageRange("soon", "later"))
    }

    @Test
    fun usageRangeIsCapped() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(0L to 366 * day, AppsSpec.usageRange("0", (366 * day).toString()))
        assertNull(AppsSpec.usageRange("0", (366 * day + 1).toString()))
    }
}
