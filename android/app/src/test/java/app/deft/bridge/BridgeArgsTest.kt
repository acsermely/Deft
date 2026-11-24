package app.deft.bridge

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeArgsTest {
    @Test
    fun smsSendRequiresToAndBody() {
        val missing = BridgeArgs.validate("sms.send", buildJsonObject { put("to", JsonPrimitive("+1")) })
        assertNotNull(missing)
        val ok = BridgeArgs.validate(
            "sms.send",
            buildJsonObject {
                put("to", JsonPrimitive("+15551212"))
                put("body", JsonPrimitive("hi"))
            },
        )
        assertNull(ok)
    }

    @Test
    fun bulkListLimitAndOffsetAreBounded() {
        val ok = BridgeArgs.validate(
            "sms.listMessages",
            buildJsonObject {
                put("limit", JsonPrimitive(50_000))
                put("offset", JsonPrimitive(1_000))
            },
        )
        assertNull(ok)
        val tooLong = BridgeArgs.validate(
            "telephony.listCalls",
            buildJsonObject { put("limit", JsonPrimitive("1234567890")) },
        )
        assertNotNull(tooLong)
    }

    @Test
    fun unknownMethodHasNoRequiredFields() {
        assertNull(BridgeArgs.validate("bluetooth.bleState", buildJsonObject {}))
    }
}
