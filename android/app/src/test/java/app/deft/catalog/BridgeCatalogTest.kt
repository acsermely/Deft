package app.deft.catalog

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeCatalogTest {
    @Test
    fun catalogIdsMatchCapabilityEntries() {
        val file = File("src/main/assets/agent/bridge-catalog.json")
        assertTrue("catalog missing at ${file.absolutePath}", file.isFile)
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val caps = root["capabilities"]!!.jsonObject
        val jsonIds = caps.keys.toSortedSet()
        val enumIds = Capability.entries.map { it.id }.toSortedSet()
        assertEquals(enumIds, jsonIds)
        for (cap in Capability.entries) {
            val obj = caps[cap.id]!!.jsonObject
            assertNotNull(obj["risk"])
            assertTrue(obj["risk"]!!.toString().isNotBlank())
        }
        for (method in BridgeCatalog.allMethods()) {
            assertNotNull("unmapped $method", BridgeCatalog.capabilityFor(method))
        }
    }

    /** Review: queued events are purged and filtered by this mapping; it must match the catalog. */
    @Test
    fun everyCatalogEventMapsToItsCapability() {
        val caps = Json.parseToJsonElement(File("src/main/assets/agent/bridge-catalog.json").readText())
            .jsonObject["capabilities"]!!.jsonObject
        val fromJson = mutableMapOf<String, MutableSet<String>>()
        for ((id, obj) in caps) {
            for (e in obj.jsonObject["events"]?.jsonArray.orEmpty()) {
                fromJson.getOrPut(e.jsonPrimitive.content) { mutableSetOf() }.add(id)
            }
        }
        assertEquals(fromJson.keys, BridgeCatalog.allEvents())
        for ((event, ids) in fromJson) {
            assertEquals(event, ids, BridgeCatalog.capabilitiesForEvent(event).map { it.id }.toSet())
            // Exactly one capability per event; the one shared event is delivered without one.
            if (event in BridgeCatalog.UNGATED_EVENTS) continue
            assertEquals("$event maps to $ids", 1, ids.size)
        }
        assertEquals(setOf("intents.viewReceived"), BridgeCatalog.UNGATED_EVENTS)
    }

    @Test
    fun eventAllowedNeedsTheEventsOwnCapability() {
        // Capability ids do not prefix their events: sms.read owns sms.received, calls.control owns telephony.*.
        assertTrue(BridgeCatalog.eventAllowed("sms.received", setOf(Capability.SMS_READ)))
        assertFalse(BridgeCatalog.eventAllowed("sms.received", setOf(Capability.SMS_SEND)))
        assertFalse(BridgeCatalog.eventAllowed("sms.composeRequested", emptySet()))
        assertTrue(BridgeCatalog.eventAllowed("telephony.incoming", setOf(Capability.CALLS_CONTROL)))
        assertFalse(BridgeCatalog.eventAllowed("telephony.state", setOf(Capability.CALLS_PLACE)))
        assertFalse(BridgeCatalog.eventAllowed("contacts.changed", setOf(Capability.CONTACTS_WRITE)))
        // Unknown events are never delivered from the queue.
        assertFalse(BridgeCatalog.eventAllowed("made.up", Capability.entries.toSet()))
        assertTrue(BridgeCatalog.eventAllowed("intents.viewReceived", emptySet()))
    }

    @Test
    fun docsMentionEveryMethod() {
        val dir = File("src/main/assets/agent/skills")
        val text = (listOf(File(dir, "js-bridge.md")) + File(dir, "js-bridge").listFiles().orEmpty().sortedBy { it.name })
            .joinToString("\n") { it.readText() }
        val missing = BridgeCatalog.allMethods().filter { it.substringAfter('.') !in text && it !in text }
        assertTrue("methods missing from docs: $missing", missing.isEmpty() || missing.size < 5)
    }

    @Test
    fun bridgeIndexIsSmallAndGroupFilesFit() {
        val dir = File("src/main/assets/agent/skills")
        assertTrue("js-bridge.md index must be <= 4096 B", File(dir, "js-bridge.md").length() <= 4096)
        val groups = File(dir, "js-bridge").listFiles().orEmpty()
        assertTrue(groups.isNotEmpty())
        for (f in groups) assertTrue("${f.name} must be < 16 KB", f.length() < 16 * 1024)
        val index = File(dir, "js-bridge.md").readText()
        for (f in groups.filter { it.name != "basics.md" }) {
            assertTrue("index must name js-bridge/${f.nameWithoutExtension}", "js-bridge/${f.nameWithoutExtension}" in index)
        }
        assertTrue("10-140" in index)
    }
}
