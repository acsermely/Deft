package app.deft.telephony

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UssdCodesTest {
    @Test
    fun acceptsCarrierCodes() {
        assertTrue(UssdCodes.isValid("*100#"))
        assertTrue(UssdCodes.isValid("*123*1#"))
        assertTrue(UssdCodes.isValid("#100#"))
        assertTrue(UssdCodes.isValid("*211#"))
        assertTrue(UssdCodes.isValid("*3#"))
        assertTrue(UssdCodes.isValid("*" + "1".repeat(30) + "#"))
    }

    @Test
    fun rejectsOtherShapes() {
        assertFalse(UssdCodes.isValid("100#"))
        assertFalse(UssdCodes.isValid("*100"))
        assertFalse(UssdCodes.isValid("*100#1"))
        assertFalse(UssdCodes.isValid("*1 00#"))
        assertFalse(UssdCodes.isValid("*+1555#"))
        assertFalse(UssdCodes.isValid("*ab#"))
        assertFalse(UssdCodes.isValid("*" + "1".repeat(31) + "#"))
        assertFalse(UssdCodes.isValid(""))
    }

    @Test
    fun rejectsSupplementaryServiceCodes() {
        assertFalse(UssdCodes.isValid("**21*5551234#")) // forward all calls
        assertFalse(UssdCodes.isValid("*21*5551234#"))
        assertFalse(UssdCodes.isValid("#21#"))
        assertFalse(UssdCodes.isValid("##002#"))
        assertFalse(UssdCodes.isValid("*#21#"))
        assertFalse(UssdCodes.isValid("*#06#"))
        assertFalse(UssdCodes.isValid("*33*1234#")) // barring
        assertFalse(UssdCodes.isValid("#330*1234#"))
        assertFalse(UssdCodes.isValid("**04*1234*5678*5678#")) // PIN change
        assertFalse(UssdCodes.isValid("*43#")) // call waiting
        assertFalse(UssdCodes.isValid("#31#")) // caller id
        assertFalse(UssdCodes.isValid("*61*5551234*11*20#"))
        assertFalse(UssdCodes.isValid("*03*330*1111*2222*2222#"))
    }
}
