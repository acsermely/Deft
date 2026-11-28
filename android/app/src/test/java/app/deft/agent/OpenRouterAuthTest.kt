package app.deft.agent

import app.deft.config.parseAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterAuthTest {
    @Test
    fun challengeIsUnpaddedBase64UrlSha256() {
        // Computed independently with Bun's sha256 + base64url.
        assertEquals(
            "TDp3C-wPo3aKDK1Bg-ukP78nZ9YiqUD2ToRD96HoaHU",
            OpenRouterAuth.challengeFor("abcdefghijklmnopqrstuvwxyz0123456789-._~ABCDEFG"),
        )
    }

    @Test
    fun newPkceMatchesItsOwnChallenge() {
        val pkce = OpenRouterAuth.newPkce()
        assertEquals(OpenRouterAuth.challengeFor(pkce.verifier), pkce.challenge)
        assertTrue(pkce.verifier.length >= 43)
        assertFalse(pkce.verifier.contains('='))
    }

    @Test
    fun authUrlCarriesTheLocalhostCallbackAndS256() {
        val url = OpenRouterAuth.authUrl(51423, OpenRouterAuth.Pkce("v", "chal", "st"))
        assertTrue(url.startsWith("https://openrouter.ai/auth?"))
        assertTrue(url.contains("callback_url=http%3A%2F%2Flocalhost%3A51423%2Fcallback"))
        assertTrue(url.contains("code_challenge=chal"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=st"))
        assertFalse(url.contains("limit="))
    }

    @Test
    fun callbackWithTheRightStateGivesTheCode() {
        assertEquals(
            OpenRouterAuth.Callback.Code("abc"),
            OpenRouterAuth.parseCallback("GET /callback?code=abc&state=s1 HTTP/1.1", "s1"),
        )
    }

    @Test
    fun wrongStateAndStrayRequestsAreIgnored() {
        assertEquals(OpenRouterAuth.Callback.Ignore, OpenRouterAuth.parseCallback("GET /callback?code=abc&state=evil HTTP/1.1", "s1"))
        assertEquals(OpenRouterAuth.Callback.Ignore, OpenRouterAuth.parseCallback("GET /favicon.ico HTTP/1.1", "s1"))
        assertEquals(OpenRouterAuth.Callback.Ignore, OpenRouterAuth.parseCallback("POST /callback?code=abc&state=s1 HTTP/1.1", "s1"))
        assertEquals(OpenRouterAuth.Callback.Ignore, OpenRouterAuth.parseCallback("", "s1"))
        assertEquals(OpenRouterAuth.Callback.Ignore, OpenRouterAuth.parseCallback("GET /callback?state=s1 HTTP/1.1", "s1"))
    }

    @Test
    fun deniedCallbackIsReported() {
        val result = OpenRouterAuth.parseCallback("GET /callback?error=access_denied HTTP/1.1", "s1")
        assertTrue(result is OpenRouterAuth.Callback.Denied)
    }

    @Test
    fun parsesKeyAndFreeTier() {
        assertEquals("sk-or-1", OpenRouterAuth.parseKey("""{"key":"sk-or-1","user_id":"u"}"""))
        assertNull(OpenRouterAuth.parseKey("""{"error":"x"}"""))
        assertEquals(true, OpenRouterAuth.parseFreeTier("""{"data":{"label":"a","is_free_tier":true}}"""))
        assertEquals(false, OpenRouterAuth.parseFreeTier("""{"data":{"is_free_tier":false}}"""))
        assertNull(OpenRouterAuth.parseFreeTier("not json"))
    }

    @Test
    fun defaultModelFollowsTheFreeTier() {
        assertEquals(AgentModelChoices.FREE_MODEL, AgentModelChoices.defaultFor(freeTier = true))
        assertEquals("z-ai/glm-5.3-flash", AgentModelChoices.defaultFor(freeTier = false))
    }

    @Test
    fun oldNitroModelBecomesFastAndApiBaseIsIgnored() {
        val cfg = parseAgent(
            mapOf("api_base" to "https://example.test/v1", "model" to "z-ai/glm-5.3-flash:nitro", "fast" to "false"),
        )
        assertEquals("z-ai/glm-5.3-flash", cfg.model)
        assertTrue(cfg.fast)
        assertFalse(cfg.toToml().contains("api_base"))
    }

    @Test
    fun defaultsAreFastAndMinimalEffort() {
        val cfg = parseAgent(emptyMap())
        assertEquals(AgentModelChoices.DEFAULT_MODEL, cfg.model)
        assertTrue(cfg.fast)
        assertEquals("minimal", cfg.effort)
        assertEquals("minimal", parseAgent(mapOf("effort" to "extreme")).effort)
        assertEquals("high", parseAgent(mapOf("effort" to "high")).effort)
    }

    @Test
    fun noCreditsIsItsOwnError() {
        val error = AgentError.from(HttpStatusException(402, "Insufficient credits"))
        assertEquals(AgentError.NO_CREDITS, error.code)
        assertEquals(402, error.http)
    }
}
