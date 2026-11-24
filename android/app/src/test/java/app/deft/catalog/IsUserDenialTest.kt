package app.deft.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsUserDenialTest {
    private val denied = GrantLevel.DENIED.name
    private val allowed = GrantLevel.WHILE_USING.name

    @Test
    fun noRowIsNeverAsked() {
        assertFalse(isUserDenial(null, rowDeniedByUser = false))
    }

    @Test
    fun deniedWithoutTheUserFlagIsAskable() {
        // "Not now" and an unticked consent box write DENIED without the flag: the app may ask later.
        assertFalse(isUserDenial(denied, rowDeniedByUser = false))
    }

    @Test
    fun deniedWithTheUserFlagIsAUserDenial() {
        // The dashboard's off switch and "Don't ask again" write DENIED with the flag.
        assertTrue(isUserDenial(denied, rowDeniedByUser = true))
    }

    @Test
    fun grantedRowIsNotADenial() {
        assertFalse(isUserDenial(allowed, rowDeniedByUser = false))
        assertFalse(isUserDenial(allowed, rowDeniedByUser = true))
    }
}
