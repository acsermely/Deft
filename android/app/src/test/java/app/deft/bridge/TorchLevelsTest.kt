package app.deft.bridge

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TorchLevelsTest {
    @Test
    fun missingLevelIsStrongest() {
        assertEquals(5, TorchLevels.clamp(null, 5))
        assertEquals(1, TorchLevels.clamp(null, 1))
    }

    @Test
    fun levelsInRangeAreAccepted() {
        assertEquals(1, TorchLevels.clamp("1", 5))
        assertEquals(3, TorchLevels.clamp("3", 5))
        assertEquals(5, TorchLevels.clamp("5", 5))
    }

    @Test
    fun levelsOutOfRangeAreRejected() {
        assertNull(TorchLevels.clamp("0", 5))
        assertNull(TorchLevels.clamp("-1", 5))
        assertNull(TorchLevels.clamp("6", 5))
        assertNull(TorchLevels.clamp("2", 1))
        assertNull(TorchLevels.clamp("", 5))
        assertNull(TorchLevels.clamp("max", 5))
        assertNull(TorchLevels.clamp("1.5", 5))
    }

    @Test
    fun torchArgsRequireOnAndLimitLength() {
        assertNotNull(BridgeArgs.validate("torch.set", buildJsonObject { put("level", JsonPrimitive("1")) }))
        assertNull(BridgeArgs.validate("torch.set", buildJsonObject { put("on", JsonPrimitive("true")) }))
        assertNull(BridgeArgs.validate("torch.set", buildJsonObject { put("on", JsonPrimitive("true")); put("level", JsonPrimitive("12")) }))
        assertNotNull(BridgeArgs.validate("torch.set", buildJsonObject { put("on", JsonPrimitive("true")); put("level", JsonPrimitive("12345")) }))
        assertNull(BridgeArgs.validate("torch.state", buildJsonObject {}))
    }
}
