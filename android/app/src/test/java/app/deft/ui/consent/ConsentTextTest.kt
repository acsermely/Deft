package app.deft.ui.consent

import app.deft.catalog.Capability
import app.deft.catalog.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConsentTextTest {
    @Test
    fun replacesLineNamesTheHolderAndTheGroup() {
        assertEquals("This replaces Mail as your text messages app.", ConsentText.replacesHolder("Mail", "sms"))
        assertEquals("This replaces Dialer as your phone calls app.", ConsentText.replacesHolder("Dialer", "calls"))
        assertEquals("This replaces Wallet as your NFC card app.", ConsentText.replacesHolder("Wallet", "nfc.hce"))
    }

    @Test
    fun everyGroupTheConsentScreenCanGrantHasAPlainLabel() {
        val groups = Capability.entries.filter { it.risk != Risk.SPECIAL }.mapNotNull { it.exclusiveGroup }.toSet()
        for (g in groups) assertNotEquals(g, g, ConsentText.groupLabel(g))
    }
}
