package app.deft.telephony

import app.deft.background.BackgroundPolicy
import app.deft.background.BackgroundTriggers
import app.deft.bridge.BridgeArgs
import app.deft.catalog.BridgeCatalog
import app.deft.catalog.Capability
import app.deft.telephony.ScreeningSpec.Action
import app.deft.telephony.ScreeningSpec.Decision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreeningSpecTest {
    private val id = "0123456789abcdef0123456789abcdef"

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    @Test
    fun responsesMapToAndroid() {
        assertEquals(ScreeningSpec.Response(false, false, false, false, false), ScreeningSpec.response(Decision.ALLOW))
        assertEquals(ScreeningSpec.Response(false, false, true, false, false), ScreeningSpec.response(Decision(Action.SILENCE)))
        assertEquals(ScreeningSpec.Response(true, true, false, false, false), ScreeningSpec.response(Decision(Action.REJECT)))
        assertEquals(
            ScreeningSpec.Response(true, true, false, true, true),
            ScreeningSpec.response(Decision(Action.REJECT, skipLog = true, skipNotification = true)),
        )
        assertEquals(
            ScreeningSpec.Response(true, true, false, true, false),
            ScreeningSpec.response(Decision(Action.REJECT, skipLog = true)),
        )
    }

    @Test
    fun validAnswersParse() {
        val allow = ScreeningSpec.parseRespond(args("callId" to id, "action" to "allow"))
        assertEquals(id, allow.callId)
        assertEquals(Decision.ALLOW, allow.decision)
        assertNull(allow.error)
        assertEquals(Decision(Action.SILENCE), ScreeningSpec.parseRespond(args("callId" to id, "action" to "silence")).decision)
        assertEquals(
            Decision(Action.REJECT, skipLog = true, skipNotification = false),
            ScreeningSpec.parseRespond(args("callId" to id, "action" to "reject", "skipLog" to true, "skipNotification" to false)).decision,
        )
        assertEquals(
            Decision(Action.REJECT),
            ScreeningSpec.parseRespond(args("callId" to id, "action" to "reject", "skipLog" to null)).decision,
        )
    }

    @Test
    fun badAnswersAreRefused() {
        val bad = listOf(
            args("action" to "allow"),
            args("callId" to id),
            args("callId" to 5, "action" to "allow"),
            args("callId" to "", "action" to "allow"),
            args("callId" to "a".repeat(65), "action" to "allow"),
            args("callId" to "abc def", "action" to "allow"),
            args("callId" to "abc/../x", "action" to "allow"),
            args("callId" to id, "action" to "block"),
            args("callId" to id, "action" to "ALLOW"),
            args("callId" to id, "action" to true),
            args("callId" to id, "action" to "reject", "skipLog" to "true"),
            args("callId" to id, "action" to "reject", "skipNotification" to 1),
            args("callId" to id, "action" to "allow", "skipLog" to true),
            args("callId" to id, "action" to "silence", "skipNotification" to true),
        )
        for (a in bad) {
            val p = ScreeningSpec.parseRespond(a)
            assertNotNull(a.toString(), p.error)
            assertNull(a.toString(), p.decision)
        }
    }

    @Test
    fun argLimitsInBridgeArgs() {
        assertNull(BridgeArgs.validate("calls.screenRespond", args("callId" to id, "action" to "reject", "skipLog" to true)))
        assertNotNull(BridgeArgs.validate("calls.screenRespond", args("callId" to id)))
        assertNotNull(BridgeArgs.validate("calls.screenRespond", args("callId" to "a".repeat(65), "action" to "allow")))
    }

    @Test
    fun deadlineIsFourSeconds() {
        assertEquals(4_000L, ScreeningSpec.DEADLINE_MS)
        assertFalse(ScreeningSpec.isLate(1_000, 1_000))
        assertFalse(ScreeningSpec.isLate(1_000, 4_999))
        assertTrue(ScreeningSpec.isLate(1_000, 5_000))
        assertTrue("clock going back counts as late", ScreeningSpec.isLate(1_000, 999))
        assertEquals(4_000L, ScreeningSpec.remainingMs(0, 0))
        assertEquals(1_500L, ScreeningSpec.remainingMs(0, 2_500))
        assertEquals(0L, ScreeningSpec.remainingMs(0, 9_000))
        assertEquals(4_000L, ScreeningSpec.remainingMs(1_000, 0))
        assertEquals(ScreeningSpec.DEADLINE_MS, BackgroundTriggers.urgentMs(BackgroundTriggers.SCREENING))
        assertNull(BackgroundTriggers.urgentMs(BackgroundTriggers.CALLS))
    }

    @Test
    fun numberOnlyWithCallLogOrContacts() {
        assertFalse(ScreeningSpec.showNumber(holdsCallLog = false, holdsContacts = false))
        assertTrue(ScreeningSpec.showNumber(holdsCallLog = true, holdsContacts = false))
        assertTrue(ScreeningSpec.showNumber(holdsCallLog = false, holdsContacts = true))
        assertNull(ScreeningSpec.visibleNumber("+36301234567", show = false))
        assertEquals("+36301234567", ScreeningSpec.visibleNumber("+36301234567", show = true))
        assertEquals("+1 (555) 123-4567", ScreeningSpec.visibleNumber(" +1 (555) 123-4567 ", show = true))
        for (bad in listOf(null, "", "   ", "*21#", "123#", "user@sip.example", "+", "1".repeat(33), "12\n34")) {
            assertNull(bad, ScreeningSpec.visibleNumber(bad, show = true))
        }
    }

    @Test
    fun emergencyNumbersAreNeverScreened() {
        assertTrue(ScreeningSpec.isEmergency(platformSays = true, number = "+36301234567"))
        assertTrue(ScreeningSpec.isEmergency(platformSays = true, number = null))
        for (n in listOf("112", "911", "999", "000", "1-1-2")) assertTrue(n, ScreeningSpec.isEmergency(false, n))
        assertFalse(ScreeningSpec.isEmergency(false, "+36301234567"))
        assertFalse(ScreeningSpec.isEmergency(false, null))
        assertFalse(ScreeningSpec.isEmergency(false, "1123"))
    }

    @Test
    fun payloadShape() {
        val withNumber = Json.parseToJsonElement(ScreeningSpec.payload(id, "+3612345678", verified = true)).jsonObject
        assertEquals(setOf("callId", "number", "verified", "direction"), withNumber.keys)
        assertEquals(id, withNumber["callId"]!!.jsonPrimitive.content)
        assertEquals("+3612345678", withNumber["number"]!!.jsonPrimitive.content)
        assertTrue(withNumber["verified"]!!.jsonPrimitive.boolean)
        assertEquals("incoming", withNumber["direction"]!!.jsonPrimitive.content)
        val hidden = Json.parseToJsonElement(ScreeningSpec.payload(id, null, verified = false)).jsonObject
        assertEquals(JsonNull, hidden["number"])
        assertFalse(hidden["verified"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun auditNeverHoldsTheNumber() {
        val meta = ScreeningSpec.auditMetadata(ScreeningSpec.REASON_ANSWERED, 120, verified = true)
        assertEquals(setOf("reason", "ms", "verified"), Json.parseToJsonElement(meta).jsonObject.keys)
        assertEquals("reject", ScreeningSpec.auditOutcome(Decision(Action.REJECT), ScreeningSpec.REASON_ANSWERED))
        assertEquals("allow", ScreeningSpec.auditOutcome(Decision.ALLOW, ScreeningSpec.REASON_EMERGENCY))
        for (r in listOf(ScreeningSpec.REASON_TIMEOUT, ScreeningSpec.REASON_NOT_DELIVERED, ScreeningSpec.REASON_ERROR)) {
            assertEquals(ScreeningSpec.OUTCOME_DEFAULT_ALLOW, ScreeningSpec.auditOutcome(Decision.ALLOW, r))
        }
    }

    @Test
    fun onlyTheAddresseeAnswersOnceAndInTime() {
        var n = 0
        val reg = ScreeningRegistry { "id${n++}" }
        val (a, answerA) = reg.open("app-a", 1_000)
        assertEquals(ScreeningRegistry.Reply.UNKNOWN, reg.respond("app-b", a, Decision(Action.REJECT), 1_100))
        assertFalse(answerA.isCompleted)
        assertEquals(ScreeningRegistry.Reply.UNKNOWN, reg.respond("app-a", "nope", Decision(Action.REJECT), 1_100))
        assertEquals(ScreeningRegistry.Reply.APPLIED, reg.respond("app-a", a, Decision(Action.SILENCE), 1_200))
        assertEquals(Decision(Action.SILENCE), kotlinx.coroutines.runBlocking { answerA.await() })
        assertEquals("second answer", ScreeningRegistry.Reply.UNKNOWN, reg.respond("app-a", a, Decision(Action.REJECT), 1_300))
        reg.close(a)
        assertEquals(0, reg.size())
        assertEquals(ScreeningRegistry.Reply.UNKNOWN, reg.respond("app-a", a, Decision(Action.REJECT), 1_300))

        val (late, answerLate) = reg.open("app-a", 10_000)
        assertEquals(ScreeningRegistry.Reply.LATE, reg.respond("app-a", late, Decision(Action.REJECT), 14_000))
        assertFalse(answerLate.isCompleted)
        reg.close(late)
        assertTrue(answerLate.isCancelled)
    }

    @Test
    fun registryIdsAreUnique() {
        var n = 0
        val reg = ScreeningRegistry { if (n++ < 2) "same" else "other$n" }
        val first = reg.open("a", 0).first
        val second = reg.open("a", 0).first
        assertTrue(first != second)
    }

    @Test
    fun respondWorksFromABackgroundRun() {
        assertEquals(Capability.CALLS_SCREEN, BridgeCatalog.capabilityFor("calls.screenRespond"))
        assertFalse(
            BackgroundPolicy.needsForeground("calls.screenRespond", args("callId" to id, "action" to "reject"), Capability.CALLS_SCREEN),
        )
        assertTrue(ScreeningSpec.validCallId(id))
    }
}
