package app.deft.bridge

import app.deft.background.BackgroundTriggers
import app.deft.catalog.BridgeCatalog
import app.deft.catalog.Capability
import app.deft.registry.DeftManifestParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSpecTest {
    private fun obj(json: String): JsonObject = BridgeJson.parseToJsonElement(json).jsonObject

    private fun <T> ok(p: LocationSpec.Parsed<T>): T {
        assertTrue("expected Ok, got $p", p is LocationSpec.Parsed.Ok)
        return (p as LocationSpec.Parsed.Ok).value
    }

    private fun bad(p: LocationSpec.Parsed<*>) {
        assertTrue("expected Bad, got $p", p is LocationSpec.Parsed.Bad)
    }

    @Test
    fun watchAcceptsTenSecondsAndUp() {
        assertEquals(LocationSpec.Watch(10_000, 0f), ok(LocationSpec.parseWatch(obj("""{"intervalMs":10000}"""))))
        assertEquals(LocationSpec.Watch(60_000, 25f), ok(LocationSpec.parseWatch(obj("""{"intervalMs":60000,"minDistanceM":25}"""))))
        assertEquals(LocationSpec.Watch(3_600_000, 0f), ok(LocationSpec.parseWatch(obj("""{"intervalMs":3600000}"""))))
    }

    @Test
    fun watchRejectsShortIntervalsStringsAndBadDistances() {
        for (json in listOf(
            """{}""",
            """{"intervalMs":9999}""",
            """{"intervalMs":3600001}""",
            """{"intervalMs":"10000"}""",
            """{"intervalMs":10000,"minDistanceM":-1}""",
            """{"intervalMs":10000,"minDistanceM":100001}""",
            """{"intervalMs":10000,"minDistanceM":"5"}""",
        )) {
            bad(LocationSpec.parseWatch(obj(json)))
        }
    }

    @Test
    fun geofenceParsesAndFixesExpiry() {
        val g = ok(LocationSpec.parseGeofence(obj("""{"id":"home","lat":47.5,"lng":19.04,"radiusM":100}"""), now = 1_000))
        assertEquals(LocationSpec.Geofence("home", 47.5, 19.04, 100f, null), g)
        assertEquals(-1L, g.remainingMs(5_000))
        val e = ok(LocationSpec.parseGeofence(obj("""{"id":"a.b_c-1","lat":-90,"lng":180,"radiusM":100000,"expiresMs":60000}"""), now = 1_000))
        assertEquals(61_000L, e.expiresAt)
        assertEquals(31_000L, e.remainingMs(30_000))
        assertFalse(e.expired(60_999))
        assertTrue(e.expired(61_000))
    }

    @Test
    fun geofenceRejectsEveryBadField() {
        for (json in listOf(
            """{"lat":1,"lng":1,"radiusM":100}""",
            """{"id":"","lat":1,"lng":1,"radiusM":100}""",
            """{"id":"a b","lat":1,"lng":1,"radiusM":100}""",
            """{"id":"a/b","lat":1,"lng":1,"radiusM":100}""",
            """{"id":7,"lat":1,"lng":1,"radiusM":100}""",
            """{"id":"${"x".repeat(65)}","lat":1,"lng":1,"radiusM":100}""",
            """{"id":"a","lat":90.1,"lng":1,"radiusM":100}""",
            """{"id":"a","lat":1,"lng":-180.5,"radiusM":100}""",
            """{"id":"a","lat":"1","lng":1,"radiusM":100}""",
            """{"id":"a","lat":1,"lng":1,"radiusM":99}""",
            """{"id":"a","lat":1,"lng":1,"radiusM":100001}""",
            """{"id":"a","lat":1,"lng":1}""",
            """{"id":"a","lat":1,"lng":1,"radiusM":100,"expiresMs":59999}""",
            """{"id":"a","lat":1,"lng":1,"radiusM":100,"expiresMs":"60000"}""",
        )) {
            bad(LocationSpec.parseGeofence(obj(json), now = 0))
        }
    }

    private fun fence(id: String, expiresAt: Long? = null) = LocationSpec.Geofence(id, 1.0, 2.0, 150f, expiresAt)

    @Test
    fun atMostTwentyGeofencesSameIdReplacesExpiredDropOut() {
        val twenty = (1..20).map { fence("g$it") }
        assertNull(LocationSpec.withAdded(twenty, fence("g21"), now = 0))
        val replaced = LocationSpec.withAdded(twenty, fence("g5").copy(radiusM = 500f), now = 0)!!
        assertEquals(20, replaced.size)
        assertEquals(500f, replaced.first { it.id == "g5" }.radiusM)
        val withExpired = twenty.dropLast(1) + fence("old", expiresAt = 10)
        val next = LocationSpec.withAdded(withExpired, fence("new"), now = 10)!!
        assertEquals(20, next.size)
        assertFalse(next.any { it.id == "old" })
    }

    @Test
    fun storeRoundTripsAndSkipsBrokenEntries() {
        val list = listOf(fence("a"), fence("b", expiresAt = 123_456_789_000))
        assertEquals(list, LocationSpec.decode(LocationSpec.encode(list)))
        assertEquals(emptyList<LocationSpec.Geofence>(), LocationSpec.decode(null))
        assertEquals(emptyList<LocationSpec.Geofence>(), LocationSpec.decode("not json"))
        assertEquals(emptyList<LocationSpec.Geofence>(), LocationSpec.decode("""{"id":"a"}"""))
        val mixed = """[{"id":"ok","lat":1,"lng":2,"radiusM":150},{"id":"bad id","lat":1,"lng":2,"radiusM":150},""" +
            """{"id":"far","lat":91,"lng":2,"radiusM":150},{"id":"small","lat":1,"lng":2,"radiusM":5},7,""" +
            """{"id":"ok","lat":3,"lng":4,"radiusM":200}]"""
        assertEquals(listOf(LocationSpec.Geofence("ok", 1.0, 2.0, 150f, null)), LocationSpec.decode(mixed))
    }

    @Test
    fun payloadsHaveTheDocumentedShape() {
        val fix = LocationSpec.Fix(47.5, 19.0, 4.5f, null, 2.5f, null, 1_700_000_000_000, "gps")
        val p = BridgeJson.parseToJsonElement(LocationSpec.fixPayload("w1", fix)).jsonObject
        assertEquals(setOf("watchId", "lat", "lng", "accuracy", "speed", "time", "provider"), p.keys)
        assertEquals("w1", p["watchId"]!!.jsonPrimitive.content)
        assertEquals("gps", p["provider"]!!.jsonPrimitive.content)
        val noAcc = BridgeJson.parseToJsonElement(LocationSpec.fixPayload("w1", fix.copy(accuracy = null))).jsonObject
        assertEquals("null", noAcc["accuracy"].toString())
        assertEquals("""{"id":"home","entering":true}""", LocationSpec.geofencePayload("home", true))
        val listed = LocationSpec.geofenceJson(fence("a"))
        assertEquals(setOf("id", "lat", "lng", "radiusM", "expiresAt"), listed.keys)
    }

    @Test
    fun networkFixesOnlyWhileGpsIsSilent() {
        assertTrue(LocationSpec.acceptFix("gps", now = 100, lastGpsAt = 90, intervalMs = 10_000))
        assertTrue(LocationSpec.acceptFix("network", now = 100, lastGpsAt = 0, intervalMs = 10_000))
        assertFalse(LocationSpec.acceptFix("network", now = 15_000, lastGpsAt = 1_000, intervalMs = 10_000))
        assertTrue(LocationSpec.acceptFix("network", now = 21_000, lastGpsAt = 1_000, intervalMs = 10_000))
    }

    @Test
    fun methodsBelongToLocationBackgroundAndArgsAreBounded() {
        for (m in listOf("location.watchBackground", "location.watchStop", "location.geofenceAdd", "location.geofenceRemove", "location.geofenceList")) {
            assertEquals(m, Capability.LOCATION_BACKGROUND, BridgeCatalog.capabilityFor(m))
        }
        assertTrue(Capability.LOCATION_BACKGROUND.available)
        assertNotNull(BridgeArgs.validate("location.geofenceAdd", obj("""{"id":"a","lat":1,"lng":2}""")))
        assertNotNull(BridgeArgs.validate("location.geofenceAdd", obj("""{"id":"${"x".repeat(65)}","lat":1,"lng":2,"radiusM":100}""")))
        assertNull(BridgeArgs.validate("location.geofenceAdd", obj("""{"id":"a","lat":1,"lng":2,"radiusM":100}""")))
        assertNotNull(BridgeArgs.validate("location.watchBackground", obj("""{}""")))
        assertNull(BridgeArgs.validate("location.watchBackground", obj("""{"intervalMs":10000}""")))
    }

    @Test
    fun geofenceIsADeclarableTrigger() {
        assertEquals("geofence", BackgroundTriggers.GEOFENCE)
        assertTrue(BackgroundTriggers.GEOFENCE in DeftManifestParser.BACKGROUND_TRIGGERS)
        val c = BackgroundTriggers.Candidate("a", declaresCapability = true, granted = true, triggers = listOf("geofence"))
        assertTrue(BackgroundTriggers.wants(c, BackgroundTriggers.GEOFENCE))
        assertFalse(BackgroundTriggers.wants(c.copy(granted = false), BackgroundTriggers.GEOFENCE))
    }
}
