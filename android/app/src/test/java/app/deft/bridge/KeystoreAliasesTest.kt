package app.deft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeystoreAliasesTest {
    @Test
    fun plainNamesAreAccepted() {
        assertEquals("vault", KeystoreAliases.localAlias("vault"))
        assertEquals("My_key-2", KeystoreAliases.localAlias("My_key-2"))
        assertEquals("a".repeat(64), KeystoreAliases.localAlias("a".repeat(64)))
    }

    @Test
    fun badNamesAreRejected() {
        assertNull(KeystoreAliases.localAlias(null))
        assertNull(KeystoreAliases.localAlias(""))
        assertNull(KeystoreAliases.localAlias("a".repeat(65)))
        assertNull(KeystoreAliases.localAlias("x.y"))
        assertNull(KeystoreAliases.localAlias("../etc"))
        assertNull(KeystoreAliases.localAlias("with space"))
        assertNull(KeystoreAliases.localAlias("slash/name"))
    }

    @Test
    fun keyNamesCarryTheOwner() {
        val name = KeystoreAliases.keystoreAlias("abcdefghij234567abcd", "vault")
        assertEquals("deft.abcdefghij234567abcd.vault", name)
        assertEquals("vault", KeystoreAliases.localName("abcdefghij234567abcd", name))
    }

    @Test
    fun anotherAppsKeyIsRejected() {
        val theirs = KeystoreAliases.keystoreAlias("otherowner0000000000", "vault")
        assertNull(KeystoreAliases.localName("abcdefghij234567abcd", theirs))
    }

    @Test
    fun dottedOwnerDoesNotReachIntoAnotherOwnersKeys() {
        val dotted = KeystoreAliases.keystoreAlias("app.two", "vault")
        assertNull(KeystoreAliases.localName("app", dotted))
        assertEquals("vault", KeystoreAliases.localName("app.two", dotted))
    }

    @Test
    fun validityRange() {
        assertEquals(0, KeystoreAliases.validity(null))
        assertEquals(0, KeystoreAliases.validity("0"))
        assertEquals(60, KeystoreAliases.validity("60"))
        assertEquals(3600, KeystoreAliases.validity("3600"))
        assertNull(KeystoreAliases.validity("3601"))
        assertNull(KeystoreAliases.validity("-1"))
        assertNull(KeystoreAliases.validity("soon"))
    }

    @Test
    fun uninstallDeletesOnlyTheOwnersKeys() {
        val all = listOf(
            "deft.abc.vault",
            "deft.abc.sign-1",
            "deft.abc.def.vault", // owner "abc.def", not "abc"
            "deft.abcd.vault",
            "other.abc.vault",
            "deft.abc.",
        )
        assertEquals(listOf("deft.abc.vault", "deft.abc.sign-1"), KeystoreAliases.ownedBy("abc", all))
        assertEquals(listOf("deft.abc.def.vault"), KeystoreAliases.ownedBy("abc.def", all))
    }

    @Test
    fun perUseKeysAreAskedBeforeUse() {
        assertEquals(KeystoreAliases.Lock.NONE, KeystoreAliases.lock(authRequired = false, validitySeconds = 0))
        assertEquals(KeystoreAliases.Lock.PER_USE, KeystoreAliases.lock(authRequired = true, validitySeconds = 0))
        assertEquals(KeystoreAliases.Lock.PER_USE, KeystoreAliases.lock(authRequired = true, validitySeconds = -1))
        assertEquals(KeystoreAliases.Lock.TIMED, KeystoreAliases.lock(authRequired = true, validitySeconds = 30))
    }
}
