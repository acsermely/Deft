package app.deft.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifySpecTest {
    private fun args(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun post(s: String) = NotifySpec.post(args(s)) { "generated" }

    @Test
    fun minimalPostGetsDefaults() {
        val p = post("""{"title":"Hi","body":"There"}""").getOrThrow()
        assertEquals("generated", p.id)
        assertEquals(NotifySpec.DEFAULT_CHANNEL, p.channel)
        assertTrue(p.actions.isEmpty())
        assertNull(p.reply)
    }

    @Test
    fun richPostParses() {
        val p = post(
            """{"id":"order-1","channel":"orders","title":"Shipped","body":"Your order is on the way",
               "actions":[{"id":"track","title":"Track","open":true}],
               "reply":{"actionId":"msg","label":"Message the shop"},
               "progress":{"value":3,"max":5},"ongoing":true,"silent":true,"group":"orders"}""",
        ).getOrThrow()
        assertEquals("order-1", p.id)
        assertEquals(listOf(NotifySpec.Action("track", "Track", true)), p.actions)
        assertEquals("msg", p.reply!!.actionId)
        assertEquals(NotifySpec.Progress(3, 5, false), p.progress)
        assertTrue(p.ongoing && p.silent)
    }

    @Test
    fun badPostsAreRejected() {
        assertTrue(post("""{"body":"x"}""").isFailure)
        assertTrue(post("""{"title":"t","body":"b","id":"has space"}""").isFailure)
        assertTrue(post("""{"title":"t","body":"b","channel":"Bad Channel"}""").isFailure)
        assertTrue(post("""{"title":"t","body":"b","actions":[{"id":"a","title":"A"},{"id":"b","title":"B"},{"id":"c","title":"C"}],"reply":{}}""").isFailure)
        assertTrue(post("""{"title":"t","body":"b","actions":[{"id":"a","title":"A"},{"id":"a","title":"B"}]}""").isFailure)
        assertTrue(post("""{"title":"t","body":"b","progress":{"value":9,"max":5}}""").isFailure)
    }

    @Test
    fun channelsAndNamespacing() {
        val c = NotifySpec.channel(args("""{"id":"orders","name":"Order updates","importance":"high"}""")).getOrThrow()
        assertEquals(NotifySpec.Importance.HIGH, c.importance)
        assertTrue(NotifySpec.channel(args("""{"id":"orders","name":"x","importance":"loud"}""")).isFailure)
        val tag = NotifySpec.tag("myapp:shop", "order-1")
        assertEquals("order-1", NotifySpec.idFromTag("myapp:shop", tag))
        assertNull(NotifySpec.idFromTag("myapp:other", tag))
    }
}
