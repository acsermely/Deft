package app.deft.bridge

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayArgsTest {
    @Test
    fun secureAndHideOverlaysRequireOn() {
        assertNotNull(BridgeArgs.validate("display.secure", buildJsonObject {}))
        assertNotNull(BridgeArgs.validate("display.hideOverlays", buildJsonObject {}))
        assertNull(BridgeArgs.validate("display.secure", buildJsonObject { put("on", JsonPrimitive("true")) }))
        assertNull(BridgeArgs.validate("display.hideOverlays", buildJsonObject { put("on", JsonPrimitive("false")) }))
    }

    @Test
    fun onIsLengthLimited() {
        assertNotNull(BridgeArgs.validate("display.secure", buildJsonObject { put("on", JsonPrimitive("x".repeat(9))) }))
    }
}
