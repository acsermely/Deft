package app.deft.bridge

import app.deft.background.BackgroundPolicy
import app.deft.background.BackgroundTriggers
import app.deft.catalog.BridgeCatalog
import app.deft.catalog.Capability
import app.deft.catalog.Risk
import app.deft.registry.DeftManifestParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsSpecTest {
    private fun obj(json: String): JsonObject = BridgeJson.parseToJsonElement(json).jsonObject

    private fun <T> ok(p: AccountsSpec.Parsed<T>): T {
        assertTrue("expected Ok, got $p", p is AccountsSpec.Parsed.Ok)
        return (p as AccountsSpec.Parsed.Ok).value
    }

    private fun bad(p: AccountsSpec.Parsed<*>) = assertTrue("expected Bad, got $p", p is AccountsSpec.Parsed.Bad)

    private fun stored(androidName: String, origin: String?, name: String?) = AccountsSpec.Stored(androidName, origin, name)

    @Test
    fun nameRules() {
        assertEquals("work", ok(AccountsSpec.parseName(obj("""{"name":"  work "}"""))))
        assertEquals("a".repeat(64), ok(AccountsSpec.parseName(obj("""{"name":"${"a".repeat(64)}"}"""))))
        assertEquals("me@example.com", ok(AccountsSpec.parseName(obj("""{"name":"me@example.com"}"""))))
        bad(AccountsSpec.parseName(obj("""{"name":"${"a".repeat(65)}"}""")))
        bad(AccountsSpec.parseName(obj("""{"name":"   "}""")))
        bad(AccountsSpec.parseName(obj("""{"name":""}""")))
        bad(AccountsSpec.parseName(obj("""{"name":"a\nb"}""")))
        bad(AccountsSpec.parseName(obj("""{"name":"a\u0000b"}""")))
        bad(AccountsSpec.parseName(obj("""{"name":"a b"}""")))
        bad(AccountsSpec.parseName(obj("""{"name":42}""")))
        bad(AccountsSpec.parseName(obj("""{"name":null}""")))
        bad(AccountsSpec.parseName(obj("""{}""")))
    }

    @Test
    fun displayNameShowsTheOwningApp() {
        assertEquals("work · Notes", AccountsSpec.displayName("work", "Notes"))
        assertEquals("work · Web app", AccountsSpec.displayName("work", "  "))
        val long = AccountsSpec.displayName("w", "x".repeat(100))
        assertEquals("w · " + "x".repeat(AccountsSpec.APP_NAME_MAX - 1) + "…", long)
        assertFalse(AccountsSpec.displayName("w", "A\nB").contains('\n'))
    }

    @Test
    fun ownerFilteringSeesOnlyOwnAccounts() {
        val all = listOf(
            stored("work · A", "originA", "work"),
            stored("home · A", "originA", "home"),
            stored("work · B", "originB", "work"),
            stored("orphan", null, null),
            stored("half", "originA", null),
        )
        assertEquals(listOf("work", "home"), AccountsSpec.ownedBy(all, "originA").map { it.name })
        assertEquals(listOf("work · B"), AccountsSpec.ownedBy(all, "originB").map { it.androidName })
        assertTrue(AccountsSpec.ownedBy(all, "originC").isEmpty())
        assertEquals("work · B", AccountsSpec.find(all, "originB", "work")?.androidName)
        assertNull(AccountsSpec.find(all, "originB", "home"))
        assertNull(AccountsSpec.find(all, "originC", "work"))
    }

    @Test
    fun addChecks() {
        val all = listOf(stored("work · Notes", "o1", "work"))
        assertEquals(AccountsSpec.AddCheck.Exists, AccountsSpec.checkAdd(all, "o1", "work", "work · Notes"))
        assertEquals(AccountsSpec.AddCheck.Ok, AccountsSpec.checkAdd(all, "o1", "home", "home · Notes"))
        // A second app also called "Notes" cannot take the same Android account name.
        assertEquals(AccountsSpec.AddCheck.Taken, AccountsSpec.checkAdd(all, "o2", "work", "work · Notes"))
        assertEquals(AccountsSpec.AddCheck.Ok, AccountsSpec.checkAdd(all, "o2", "work", "work · Other"))
        val full = (1..AccountsSpec.MAX_PER_APP).map { stored("a$it · N", "o1", "a$it") }
        assertEquals(AccountsSpec.AddCheck.TooMany, AccountsSpec.checkAdd(full, "o1", "new", "new · N"))
        assertEquals(AccountsSpec.AddCheck.Ok, AccountsSpec.checkAdd(full, "o2", "new", "new · M"))
    }

    @Test
    fun periodicSeconds() {
        assertEquals(0L, ok(AccountsSpec.parsePeriodicSeconds(obj("""{"seconds":0}"""))))
        assertEquals(900L, ok(AccountsSpec.parsePeriodicSeconds(obj("""{"seconds":900}"""))))
        assertEquals(3600L, ok(AccountsSpec.parsePeriodicSeconds(obj("""{"seconds":3600.0}"""))))
        assertEquals(AccountsSpec.MAX_PERIODIC_SECONDS, ok(AccountsSpec.parsePeriodicSeconds(obj("""{"seconds":2592000}"""))))
        bad(AccountsSpec.parsePeriodicSeconds(obj("""{"seconds":899}""")))
        bad(AccountsSpec.parsePeriodicSeconds(obj("""{"seconds":2592001}""")))
        bad(AccountsSpec.parsePeriodicSeconds(obj("""{"seconds":-900}""")))
        bad(AccountsSpec.parsePeriodicSeconds(obj("""{"seconds":1000.5}""")))
        bad(AccountsSpec.parsePeriodicSeconds(obj("""{"seconds":"900"}""")))
        bad(AccountsSpec.parsePeriodicSeconds(obj("""{}""")))
    }

    @Test
    fun syncPayloadAndTrigger() {
        val p = obj(AccountsSpec.syncPayload("work \"x\""))
        assertEquals("work \"x\"", p["account"]!!.jsonPrimitive.content)
        assertEquals("sync", BackgroundTriggers.SYNC)
        assertTrue(DeftManifestParser.validBackgroundTrigger("sync"))
        assertTrue(BackgroundPolicy.declares(listOf("sync"), BackgroundTriggers.SYNC))
    }

    @Test
    fun catalogAndArgs() {
        assertEquals(Risk.MEDIUM, Capability.ACCOUNTS_OWN.risk)
        assertEquals(Risk.SPECIAL, Capability.ACCOUNTS_READ.risk)
        assertTrue(Capability.ACCOUNTS_READ.available)
        for (m in listOf("accounts.add", "accounts.list", "accounts.remove", "accounts.requestSync", "accounts.setPeriodicSync")) {
            assertEquals(m, Capability.ACCOUNTS_OWN, BridgeCatalog.capabilityFor(m))
        }
        assertEquals(Capability.ACCOUNTS_READ, BridgeCatalog.capabilityFor("accounts.listAll"))
        assertNull(BridgeArgs.validate("accounts.add", obj("""{"name":"work"}""")))
        assertTrue(BridgeArgs.validate("accounts.add", obj("""{}""")) != null)
        assertTrue(BridgeArgs.validate("accounts.add", obj("""{"name":"${"a".repeat(200)}"}""")) != null)
        assertTrue(BridgeArgs.validate("accounts.setPeriodicSync", obj("""{"name":"w"}""")) != null)
        assertNull(BridgeArgs.validate("accounts.setPeriodicSync", obj("""{"name":"w","seconds":900}""")))
        assertNull(BridgeArgs.validate("accounts.listAll", obj("""{}""")))
    }
}
