package app.deft.bridge

import app.deft.catalog.BridgeCatalog
import app.deft.catalog.Capability
import app.deft.data.GrantEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeftJsBridgeAskableTest {
    private val app = "app-1"
    private val special = Capability.LOCATION_BACKGROUND
    private val methods = BridgeCatalog.methodsFor(special).toSet()

    private fun gates(byUser: Boolean) = GrantedPrefixes.gatesOf(
        listOf(special),
        listOf(GrantEntity(app, special.id, "DENIED", deniedByUser = byUser)),
    )

    private fun names(ids: Collection<String>) = DeftJsBridge.methodsJson(ids).map { it.toString().trim('"') }.toSet()

    @Test
    fun undecidedSpecialIsAskableButNotGranted() {
        val g = gates(byUser = false)
        // allowed (granted now) stays strict: the default-denied grant is not in it.
        assertFalse(special.id in g.granted)
        assertTrue(methods.isNotEmpty())
        assertFalse(names(g.granted).any { it in methods })
        // askable carries the method, so the shim lets the call reach native code, which asks.
        assertTrue(methods.all { it in names(DeftJsBridge.askableFor(false, g)) })
    }

    @Test
    fun backgroundRunGetsNoAskableMethods() {
        assertEquals(emptySet<String>(), DeftJsBridge.askableFor(true, gates(byUser = false)))
    }

    @Test
    fun foregroundWithoutGatesSendsNothing() {
        assertEquals(emptySet<String>(), DeftJsBridge.askableFor(false, null))
    }

    @Test
    fun userDeniedIsNotAskable() {
        assertEquals(emptySet<String>(), DeftJsBridge.askableFor(false, gates(byUser = true)))
    }

    @Test
    fun methodsJsonExpandsCapabilitiesToMethodNames() {
        assertEquals(methods, names(setOf(special.id)))
    }
}
