package app.deft.bridge

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsPermissionsTest {
    private val camera = "android.permission.CAMERA"
    private val mic = "android.permission.RECORD_AUDIO"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"
    private val app = "app-1"
    private val message = "Allow the camera for Deft"

    private class FakeEnv(
        val held: MutableSet<String> = mutableSetOf(),
        val declaredSet: Set<String> = setOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
        val visible: Boolean = true,
        val tap: Boolean = true,
        var now: Long = 0L,
        val answer: suspend (List<String>) -> RuntimePermissions.Answer? = { RuntimePermissions.Answer.GRANTED },
    ) : PermissionEnv {
        var asks = 0
        var consumes = 0
        var refreshes = 0
        val logs = mutableListOf<String>()
        /** Kinds already asked in the current tap; a new tap clears it. */
        val spent = mutableSetOf<UserGesture.AskKind>()
        override fun held(permission: String) = permission in held
        override fun declared() = declaredSet
        override fun onScreen(webAppId: String) = visible
        override fun tapRecent(webAppId: String) = tap
        override fun spendTapForAsk(webAppId: String, kind: UserGesture.AskKind): Boolean {
            consumes++
            return tap && spent.add(kind)
        }
        override fun refreshGesture(webAppId: String) {
            refreshes++
        }
        override fun nowMs() = now
        override fun log(line: String) {
            logs += line
        }
        override suspend fun askOnScreen(webAppId: String, permissions: List<String>): RuntimePermissions.Answer? {
            asks++
            return answer(permissions)
        }
    }

    private fun ensure(
        env: PermissionEnv,
        cooldown: DenyCooldown = DenyCooldown(),
        background: Boolean = false,
        permissions: List<String> = listOf(camera),
        timeoutMs: Long = 1_000L,
    ) = runBlocking {
        OsPermissions.ensureWith(env, cooldown, app, background, permissions, message, timeoutMs)
    }

    @Test
    fun heldPermissionsShortCircuit() {
        val env = FakeEnv(held = mutableSetOf(camera))
        assertNull(ensure(env))
        assertEquals(0, env.asks)
        assertEquals(0, env.consumes)
    }

    @Test
    fun backgroundRunsAreRefusedWithoutAPrompt() {
        val env = FakeEnv()
        val err = ensure(env, background = true)!!
        assertEquals(BridgeErrors.OS_PERMISSION_MISSING, err.code)
        assertEquals(0, env.asks)
    }

    @Test
    fun failedGateMeansNoRequest() {
        assertEquals(0, FakeEnv(tap = false).let { env -> ensure(env); env.asks })
        assertEquals(0, FakeEnv(visible = false).let { env -> ensure(env); env.asks })
    }

    @Test
    fun undeclaredPermissionIsNotAsked() {
        val env = FakeEnv(declaredSet = setOf(mic))
        assertEquals(BridgeErrors.OS_PERMISSION_MISSING, ensure(env)!!.code)
        assertEquals(0, env.asks)
    }

    @Test
    fun grantContinuesTheCall() {
        val env = FakeEnv()
        assertNull(ensure(env))
        assertEquals(1, env.asks)
        assertEquals(1, env.consumes)
    }

    @Test
    fun deniedAnswerIsAnErrorWithTheMessageAsIs() {
        val env = FakeEnv(answer = { RuntimePermissions.Answer.DENIED })
        assertEquals(message, ensure(env)!!.message)
    }

    @Test
    fun blockedAnswerPointsAtSettings() {
        val env = FakeEnv(answer = { RuntimePermissions.Answer.BLOCKED })
        assertTrue(ensure(env)!!.message.contains("app settings"))
    }

    @Test
    fun cannotStartIsAnErrorWithoutWaiting() {
        val env = FakeEnv(answer = { null })
        assertEquals(message, ensure(env)!!.message)
    }

    @Test
    fun unansweredDialogTimesOutAsAnError() {
        val env = FakeEnv(answer = { delay(2_000); RuntimePermissions.Answer.GRANTED })
        assertEquals(message, ensure(env, timeoutMs = 20L)!!.message)
    }

    @Test
    fun cooldownAfterADenialRepeatsTheRefusalWithoutADialog() {
        val env = FakeEnv(answer = { RuntimePermissions.Answer.DENIED })
        val cooldown = DenyCooldown()
        ensure(env, cooldown)
        assertEquals(1, env.asks)
        env.now = 29_000L
        assertEquals(message, ensure(env, cooldown)!!.message)
        assertEquals(1, env.asks)
        env.now = 31_000L
        env.spent.clear() // a new tap
        ensure(env, cooldown)
        assertEquals(2, env.asks)
    }

    @Test
    fun oneTapBuysOneDialog() {
        val env = FakeEnv(declaredSet = setOf(camera, coarse), answer = { RuntimePermissions.Answer.DENIED })
        ensure(env, permissions = listOf(camera, coarse))
        assertEquals(1, env.consumes)
    }

    @Test
    fun cancellationReleasesTheSlot() {
        val slot = RequestSlot()
        runBlocking {
            val result = withTimeoutOrNull(20L) { slot.tryRun { awaitCancellation() } }
            assertNull(result)
        }
        assertFalse(slot.busy)
        runBlocking { assertEquals("again", slot.tryRun { "again" }) }
    }

    @Test
    fun allowRefreshesTheGestureOnce() {
        val env = FakeEnv()
        assertNull(ensure(env))
        assertEquals(1, env.refreshes)
        assertTrue(env.logs.last().endsWith("reason=allowed"))
    }

    @Test
    fun denyDoesNotRefreshTheGesture() {
        val env = FakeEnv(answer = { RuntimePermissions.Answer.DENIED })
        ensure(env)
        assertEquals(0, env.refreshes)
    }

    @Test
    fun blockedAndTimedOutDoNotRefreshEither() {
        val blocked = FakeEnv(answer = { RuntimePermissions.Answer.BLOCKED })
        ensure(blocked)
        assertEquals(0, blocked.refreshes)
        val timedOut = FakeEnv(answer = { delay(2_000); RuntimePermissions.Answer.GRANTED })
        ensure(timedOut, timeoutMs = 20L)
        assertEquals(0, timedOut.refreshes)
    }

    @Test
    fun twoSystemAsksInOneTapSecondIsRefused() {
        val env = FakeEnv(declaredSet = setOf(camera, mic))
        assertNull(ensure(env, permissions = listOf(camera)))
        assertEquals(message, ensure(env, permissions = listOf(mic))!!.message)
        assertEquals(1, env.asks)
        assertTrue(env.logs.last().endsWith("reason=tap spent"))
    }

    @Test
    fun everyRefusalNamesItsReason() {
        val background = FakeEnv().also { ensure(it, background = true) }
        val undeclared = FakeEnv(declaredSet = setOf(mic)).also { ensure(it) }
        val hidden = FakeEnv(visible = false).also { ensure(it) }
        val noTap = FakeEnv(tap = false).also { ensure(it) }
        assertEquals("system app=$app permission=$camera reason=background", background.logs.single())
        assertEquals("system app=$app permission=$camera reason=undeclared", undeclared.logs.single())
        assertEquals("system app=$app permission=$camera reason=not on screen", hidden.logs.single())
        assertEquals("system app=$app permission=$camera reason=no tap", noTap.logs.single())
    }

    @Test
    fun secondRequestWhileOneIsOpenIsRefused() {
        val slot = RequestSlot()
        runBlocking {
            val inner = slot.tryRun { slot.tryRun { "second" } }
            assertNull(inner)
        }
    }

    @Test
    fun friendlyNamesCoverWhatTheUserSees() {
        assertEquals("camera and microphone", RuntimePermissions.friendlyNames(listOf(camera, mic)))
        assertEquals("location", RuntimePermissions.friendlyNames(listOf("android.permission.ACCESS_FINE_LOCATION", coarse)))
    }
}
