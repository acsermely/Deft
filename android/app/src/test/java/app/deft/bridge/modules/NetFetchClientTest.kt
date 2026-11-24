package app.deft.bridge.modules

import app.deft.bridge.NetFetchSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetFetchClientTest {
    private fun args(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    @Test
    fun parseAcceptsHttpsAndRefusesTheRest() {
        val ok = NetFetchClient.parse(args("""{"url":"https://x.test/a?q=1","as":"json","headers":{"Accept":"application/json"}}"""))
        val a = (ok as NetFetchClient.Parsed.Ok).args
        assertEquals("x.test", a.target.host)
        assertEquals("/a?q=1", a.target.path)
        assertEquals("json", a.mode)
        assertEquals("GET", a.method)

        assertTrue(NetFetchClient.parse(args("""{"url":"http://x.test"}""")) is NetFetchClient.Parsed.Bad)
        assertTrue(NetFetchClient.parse(args("""{"url":"https://x.test","method":"POST"}""")) is NetFetchClient.Parsed.Bad)
        assertTrue(NetFetchClient.parse(args("""{"url":"https://x.test","as":"xml"}""")) is NetFetchClient.Parsed.Bad)
    }

    @Test
    fun parseNeverPassesCookiesOrAuthorization() {
        val cookie = NetFetchClient.parse(args("""{"url":"https://x.test/","headers":{"Cookie":"a=b"}}"""))
        assertTrue(cookie is NetFetchClient.Parsed.Bad)
        assertTrue((cookie as NetFetchClient.Parsed.Bad).message.contains("not allowed"))
        assertTrue(NetFetchClient.parse(args("""{"url":"https://x.test/","headers":{"Authorization":"x"}}""")) is NetFetchClient.Parsed.Bad)
    }

    @Test
    fun appOriginsAreMatchedExactly() {
        val target = NetFetchSpec.parseUrl("https://api.x.test/v1/items")!!
        assertTrue(NetFetchSpec.originAllowed(target, listOf("https://api.x.test"), open = false))
        assertTrue(NetFetchSpec.originAllowed(target, listOf("https://API.x.test:443"), open = false))
        assertFalse(NetFetchSpec.originAllowed(target, listOf("https://x.test"), open = false))
        assertFalse(NetFetchSpec.originAllowed(target, emptyList(), open = false))
        assertTrue(NetFetchSpec.originAllowed(target, emptyList(), open = true))
    }
}
