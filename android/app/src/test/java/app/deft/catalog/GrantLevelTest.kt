package app.deft.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantLevelTest {
    @Test
    fun onlyDeniedIsNotGranted() {
        assertFalse(GrantLevel.DENIED.isGranted())
        assertTrue(GrantLevel.WHILE_USING.isGranted())
        assertTrue(GrantLevel.CONFIRM_EACH.isGranted())
    }

    @Test
    fun removedAskLevelIsNotAValue() {
        assertTrue(GrantLevel.entries.none { it.name == "ASK" })
    }
}
