package app.deft.bridge

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DndSpecTest {
    @Test
    fun filterNamesAreFixed() {
        assertEquals(setOf("all", "priority", "alarms", "none"), DndSpec.FILTERS)
    }

    @Test
    fun dndArgsAreChecked() {
        assertNotNull(BridgeArgs.validate("dnd.set", buildJsonObject {}))
        assertNotNull(BridgeArgs.validate("dnd.set", buildJsonObject { put("filter", JsonPrimitive("x".repeat(17))) }))
        assertNull(BridgeArgs.validate("dnd.set", buildJsonObject { put("filter", JsonPrimitive("alarms")) }))
        assertNull(BridgeArgs.validate("dnd.state", buildJsonObject {}))
        assertNull(BridgeArgs.validate("dnd.requestAccess", buildJsonObject {}))
    }
}
