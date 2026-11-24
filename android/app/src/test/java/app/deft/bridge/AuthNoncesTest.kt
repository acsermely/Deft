package app.deft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class AuthNoncesTest {
    private class Clock(var now: Long = 1_000_000L) {
        fun read(): Long = now
    }

    private fun registry(clock: Clock) = AuthNonces(clock = clock::read, random = SecureRandom())

    @Test
    fun noncesAre128BitHexAndDiffer() {
        val r = registry(Clock())
        val a = r.issue("app-a")
        val b = r.issue("app-a")
        assertTrue(AuthSpec.isNonce(a))
        assertEquals(32, a.length)
        assertNotEquals(a, b)
    }

    @Test
    fun completesOnlyAfterStartForTheSameApp() {
        val r = registry(Clock())
        val nonce = r.issue("app-a")
        assertTrue(r.start(nonce, "app-a"))
        assertEquals("app-a", r.complete(nonce))
    }

    @Test
    fun redirectBeforeStartBurnsTheNonce() {
        val r = registry(Clock())
        val nonce = r.issue("app-a")
        assertNull("a redirect for a sign-in that never started completes nothing", r.complete(nonce))
        assertFalse("and the nonce is used up", r.start(nonce, "app-a"))
    }

    @Test
    fun nonceOfAppACannotCompleteAppB() {
        val r = registry(Clock())
        val nonce = r.issue("app-a")
        assertFalse("app B may not start A's nonce", r.start(nonce, "app-b"))
        assertTrue("A's nonce is still A's", r.start(nonce, "app-a"))
        assertEquals("the owner is reported, never another app", "app-a", r.complete(nonce))
    }

    @Test
    fun secondUseFails() {
        val r = registry(Clock())
        val nonce = r.issue("app-a")
        assertTrue(r.start(nonce, "app-a"))
        assertFalse("a sign-in cannot start twice", r.start(nonce, "app-a"))
        assertEquals("app-a", r.complete(nonce))
        assertNull("a redirect cannot be replayed", r.complete(nonce))
    }

    @Test
    fun expiredNonceFails() {
        val clock = Clock()
        val r = registry(clock)
        val expiring = r.issue("app-a")
        clock.now += AuthNonces.TTL_MS - 1
        assertTrue("still valid just before expiry", r.start(expiring, "app-a"))
        assertEquals("app-a", r.complete(expiring))

        val expired = r.issue("app-a")
        clock.now += AuthNonces.TTL_MS
        assertFalse("cannot start after expiry", r.start(expired, "app-a"))
        assertNull(r.complete(expired))
    }

    @Test
    fun expiredStartedSignInCannotComplete() {
        val clock = Clock()
        val r = registry(clock)
        val nonce = r.issue("app-a")
        assertTrue(r.start(nonce, "app-a"))
        clock.now += AuthNonces.TTL_MS
        assertNull("a redirect after expiry is refused", r.complete(nonce))
    }

    @Test
    fun unknownNonceFails() {
        val r = registry(Clock())
        assertFalse(r.start("0".repeat(32), "app-a"))
        assertNull(r.complete("0".repeat(32)))
    }

    @Test
    fun abandonOnlyTouchesStartedSignIns() {
        val r = registry(Clock())
        val prepared = r.issue("app-a")
        val started = r.issue("app-a")
        assertTrue(r.start(started, "app-a"))
        assertEquals(listOf(started), r.startedNonces("app-a"))
        assertFalse("a prepared nonce is not an open sign-in", r.abandon(prepared))
        assertTrue(r.abandon(started))
        assertNull(r.complete(started))
        assertTrue(r.startedNonces("app-a").isEmpty())
    }

    @Test
    fun openSignInsAreListedPerApp() {
        val r = registry(Clock())
        val a = r.issue("app-a")
        val b = r.issue("app-b")
        assertTrue(r.start(a, "app-a"))
        assertTrue(r.start(b, "app-b"))
        assertEquals(listOf(a), r.startedNonces("app-a"))
        assertEquals(listOf(b), r.startedNonces("app-b"))
        assertTrue(r.startedNonces("app-c").isEmpty())
    }

    @Test
    fun cancelRemovesNonce() {
        val r = registry(Clock())
        val nonce = r.issue("app-a")
        r.cancel(nonce)
        assertFalse(r.start(nonce, "app-a"))
    }

    @Test
    fun eachAppKeepsAtMostTheNewestNonces() {
        val r = registry(Clock())
        val first = r.issue("app-a")
        repeat(AuthNonces.MAX_PER_APP) { r.issue("app-a") }
        assertFalse("the oldest nonce was dropped", r.start(first, "app-a"))
        val other = r.issue("app-b")
        assertTrue("another app is not affected", r.start(other, "app-b"))
    }

    @Test
    fun authSpecRedirectUriUsesPackageScheme() {
        assertEquals("app.deft.debug.auth", AuthSpec.scheme("app.deft.debug"))
        val nonce = "0123456789abcdef0123456789abcdef"
        val uri = AuthSpec.redirectUri(AuthSpec.scheme("app.deft"), nonce)
        assertEquals("app.deft.auth:/cb/$nonce", uri)
        assertEquals(nonce, AuthSpec.nonceFromPath("/cb/$nonce"))
        assertNull(AuthSpec.nonceFromPath("/cb/NOT-A-NONCE"))
        assertNull(AuthSpec.nonceFromPath("/other/$nonce"))
        assertNull(AuthSpec.nonceFromPath(null))
    }

    @Test
    fun startUrlMustBeHttpsWithHost() {
        assertNull(AuthSpec.startUrlProblem("https://idp.example.com/authorize?x=1"))
        assertEquals("url must use https", AuthSpec.startUrlProblem("http://idp.example.com/authorize"))
        assertEquals("url must use https", AuthSpec.startUrlProblem("intent://evil#Intent;end"))
        assertEquals("url must use https", AuthSpec.startUrlProblem("file:///data/x"))
        assertEquals("url needs a host", AuthSpec.startUrlProblem("https:///path"))
        assertEquals("url may not contain user info", AuthSpec.startUrlProblem("https://user:pw@idp.example.com/"))
        assertEquals("url is not valid", AuthSpec.startUrlProblem("https://idp example.com/"))
        assertEquals("url is too long", AuthSpec.startUrlProblem("https://a.example/" + "x".repeat(AuthSpec.MAX_URL)))
    }
}
