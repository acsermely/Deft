package app.deft.bridge

import app.deft.catalog.Capability
import app.deft.data.GrantEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantedPrefixesAskableTest {
    private val app = "app-1"
    private val plain = Capability.CONTACTS_READ
    private val special = Capability.USAGE_READ

    private fun row(cap: Capability, level: String, byUser: Boolean = false) =
        GrantEntity(app, cap.id, level, deniedByUser = byUser)

    @Test
    fun declaredSpecialDeniedWithoutTheUserFlagIsAskable() {
        val gates = GrantedPrefixes.gatesOf(listOf(special), listOf(row(special, "DENIED")))
        assertTrue(special.id in gates.askable)
        assertFalse(special.id in gates.granted)
    }

    @Test
    fun declaredPlainWithNoRowIsAskable() {
        val gates = GrantedPrefixes.gatesOf(listOf(plain), emptyList())
        assertTrue(plain.id in gates.askable)
        assertEquals(GrantedPrefixes.of(listOf(plain), emptyList()), gates.granted)
    }

    @Test
    fun plainDeniedWithoutTheUserFlagIsAskable() {
        // "Not now" or an unticked consent box: the point-of-use ask can still show.
        val gates = GrantedPrefixes.gatesOf(listOf(plain), listOf(row(plain, "DENIED")))
        assertTrue(plain.id in gates.askable)
        assertFalse(plain.id in gates.granted)
    }

    @Test
    fun userDeniedIsNotAskable() {
        val plainDenied = GrantedPrefixes.gatesOf(listOf(plain), listOf(row(plain, "DENIED", byUser = true)))
        assertFalse(plain.id in plainDenied.askable)
        val specialDenied = GrantedPrefixes.gatesOf(listOf(special), listOf(row(special, "DENIED", byUser = true)))
        assertFalse(special.id in specialDenied.askable)
    }

    @Test
    fun undeclaredIsNotAskableAndNotDeclared() {
        val gates = GrantedPrefixes.gatesOf(emptyList(), listOf(row(plain, "WHILE_USING")))
        assertFalse(plain.id in gates.askable)
        assertFalse(plain.id in gates.declared)
    }

    @Test
    fun capsStayStrictAsToday() {
        // The page's own caps: granted only. A declared special DENIED without the flag is not in them.
        val gates = GrantedPrefixes.gatesOf(listOf(special), listOf(row(special, "DENIED")))
        assertEquals(GrantedPrefixes.of(listOf(special), listOf(row(special, "DENIED"))), gates.granted)
        assertFalse(special.id in gates.granted)
    }
}
