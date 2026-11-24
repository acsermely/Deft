package app.deft.bridge.modules

import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.IntentReply
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentsModuleCameraTest {
    @Test
    fun cameraAppIsLookedUpAndMissingAppIsUnsupported() {
        val err = IntentsModule.cameraAppRefusal { false } as BridgeOutcome.Err
        assertEquals(BridgeErrors.UNSUPPORTED, err.code)
    }

    @Test
    fun grantedWithACameraAppRuns() {
        assertNull(IntentsModule.cameraAppRefusal { true })
    }

    @Test
    fun launchErrorIsReportedAsAnErrorNotACancel() {
        val error = BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "No app on this phone can do this")
        assertEquals(error, IntentsModule.intentOutcome(IntentReply(false, null, emptyList(), error)))
    }

    @Test
    fun dismissedChooserIsCancelledNotAnError() {
        val outcome = IntentsModule.intentOutcome(IntentReply(false, null, emptyList())) as BridgeOutcome.Ok
        assertEquals("cancelled", ((outcome.value as JsonObject)["resultCode"] as JsonPrimitive).content)
    }

    @Test
    fun answeredChooserIsOk() {
        val outcome = IntentsModule.intentOutcome(IntentReply(true, "text", emptyList())) as BridgeOutcome.Ok
        assertEquals("ok", ((outcome.value as JsonObject)["resultCode"] as JsonPrimitive).content)
        assertTrue(outcome.value is JsonObject)
    }

    @Test
    fun cameraMessageDoesNotMentionSettingsUnlessBlocked() {
        assertFalse(IntentsModule.CAMERA_MISSING.contains("settings"))
    }
}
