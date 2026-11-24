package app.deft.bridge

import app.deft.catalog.Capability
import app.deft.catalog.GrantLevel
import app.deft.data.GrantEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class GrantedPrefixesTest {
    private fun row(cap: Capability, level: GrantLevel) = GrantEntity("app", cap.id, level.name, 0)

    @Test
    fun grantRowForDroppedCapabilityIsNotListed() {
        val declared = listOf(Capability.CONTACTS_READ)
        val rows = listOf(
            row(Capability.CONTACTS_READ, GrantLevel.WHILE_USING),
            // Granted under an older manifest that also declared sms.read.
            row(Capability.SMS_READ, GrantLevel.WHILE_USING),
        )
        assertEquals(setOf("contacts.read"), GrantedPrefixes.of(declared, rows))
    }

    @Test
    fun deniedIsNotListedAndDeclaredStandardGrantIs() {
        val declared = listOf(Capability.CONTACTS_READ, Capability.PAYMENTS_REQUEST)
        val rows = listOf(
            row(Capability.CONTACTS_READ, GrantLevel.DENIED),
            row(Capability.PAYMENTS_REQUEST, GrantLevel.WHILE_USING),
        )
        assertEquals(setOf("payments.request"), GrantedPrefixes.of(declared, rows))
    }

    @Test
    fun nothingDeclaredListsNothing() {
        val rows = listOf(row(Capability.CONTACTS_READ, GrantLevel.WHILE_USING))
        assertEquals(emptySet<String>(), GrantedPrefixes.of(emptyList(), rows))
    }
}
