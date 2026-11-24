package app.deft.bridge

import app.deft.bridge.UserGesture.AskKind
import app.deft.catalog.Capability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialAskTest {
    private val app = "app-1"
    private val cap = Capability.USAGE_READ
    private val message = "usage.read is revoked"
    private val code = BridgeErrors.NOT_GRANTED
    private val satisfied = SpecialAsk.Outcome.Satisfied
    private val ran = SpecialAsk.Outcome.SheetRan

    private val askable = SpecialState(
        declared = true,
        granted = false,
        deniedByUser = false,
        heldByOther = false,
        systemOff = false,
    )

    /** A fake host: [sheet] plays the user's answer by changing the state, and returns whether the sheet opened. */
    private class FakeEnv(
        var state: SpecialState,
        var visible: Boolean = true,
        var tap: Boolean = true,
        var now: Long = 0L,
        val sheet: (FakeEnv) -> Boolean = { false },
    ) : SpecialEnv {
        var sheets = 0
        var refreshes = 0
        val kinds = mutableListOf<AskKind>()
        var lastNeedSystem: Boolean? = null
        val logs = mutableListOf<String>()
        override suspend fun state(webAppId: String, capability: Capability) = state
        override fun onScreen(webAppId: String) = visible
        override fun tapRecent(webAppId: String) = tap
        override fun spendTapForAsk(webAppId: String, kind: UserGesture.AskKind): Boolean {
            kinds += kind
            return tap
        }
        override fun refreshGesture(webAppId: String) {
            refreshes++
        }
        override fun nowMs() = now
        override fun log(line: String) {
            logs += line
        }
        override suspend fun openSheet(webAppId: String, capability: Capability, needSystem: Boolean): Boolean {
            sheets++
            lastNeedSystem = needSystem
            return sheet(this)
        }
        // Single-capability tests never open the consent set screen.
        override suspend fun openConsentSheet(webAppId: String, capabilities: List<Capability>): Boolean = false
    }

    /** Several capabilities at once: each has its own state, and [answer] grants whichever ones the user allowed. */
    private class ConsentEnv(
        val states: MutableMap<Capability, SpecialState>,
        val answer: (ConsentEnv) -> Unit = {},
    ) : SpecialEnv {
        var sheets = 0
        var asked: List<String> = emptyList()
        var refreshes = 0
        val kinds = mutableListOf<AskKind>()
        val logs = mutableListOf<String>()
        override suspend fun state(webAppId: String, capability: Capability) = states.getValue(capability)
        override fun onScreen(webAppId: String) = true
        override fun tapRecent(webAppId: String) = true
        override fun spendTapForAsk(webAppId: String, kind: UserGesture.AskKind): Boolean {
            kinds += kind
            return true
        }
        override fun refreshGesture(webAppId: String) {
            refreshes++
        }
        override fun nowMs() = 0L
        override fun log(line: String) {
            logs += line
        }
        override suspend fun openSheet(webAppId: String, capability: Capability, needSystem: Boolean) = false
        override suspend fun openConsentSheet(webAppId: String, capabilities: List<Capability>): Boolean {
            sheets++
            asked = capabilities.map { it.id }
            answer(this)
            return true
        }
    }

    private fun consentAsk(
        env: SpecialEnv,
        caps: List<Capability>,
        background: Boolean = false,
    ): SpecialAsk.Outcome = runBlocking {
        SpecialAsk.ensureConsentWith(env, DenyCooldown(), app, caps, background, code, message)
    }

    private fun outcome(
        env: SpecialEnv,
        cooldown: DenyCooldown = DenyCooldown(),
        background: Boolean = false,
        needSystem: Boolean = false,
        code: String = this.code,
        capability: Capability = cap,
    ): SpecialAsk.Outcome = runBlocking {
        SpecialAsk.ensureWith(env, cooldown, app, capability, background, needSystem, code, message)
    }

    /** The error a call would return, or null when the call may go on. */
    private fun err(
        env: SpecialEnv,
        cooldown: DenyCooldown = DenyCooldown(),
        background: Boolean = false,
        needSystem: Boolean = false,
        code: String = this.code,
        capability: Capability = cap,
    ): BridgeOutcome.Err? =
        (outcome(env, cooldown, background, needSystem, code, capability) as? SpecialAsk.Outcome.Refused)?.err

    private fun grants(env: FakeEnv) {
        env.state = env.state.copy(granted = true)
    }

    @Test
    fun grantedCallContinuesWithoutSheet() {
        val env = FakeEnv(askable.copy(granted = true))
        assertEquals(satisfied, outcome(env))
        assertEquals(0, env.sheets)
    }

    @Test
    fun declaredAvailableOpensSheetAndRunsIt() {
        val env = FakeEnv(askable, sheet = { grants(it); true })
        assertEquals(ran, outcome(env))
        assertEquals(1, env.sheets)
        assertEquals(false, env.lastNeedSystem)
    }

    @Test
    fun sheetThatLeftTheGrantOnRefreshesTheGestureOnce() {
        val env = FakeEnv(askable, sheet = { grants(it); true })
        outcome(env)
        assertEquals(1, env.refreshes)
        assertTrue(env.logs.single().endsWith("capability=${cap.id} reason=sheet ran"))
    }

    @Test
    fun cancelOrRefusalDoesNotRefreshTheGesture() {
        val env = FakeEnv(askable, sheet = { true })
        err(env)
        assertEquals(0, env.refreshes)
        assertTrue(env.logs.single().endsWith("reason=denied by user"))
    }

    @Test
    fun refusalsNameTheirReason() {
        val background = FakeEnv(askable).also { err(it, background = true) }
        val undeclared = FakeEnv(askable.copy(declared = false)).also { err(it) }
        val denied = FakeEnv(askable.copy(deniedByUser = true)).also { err(it) }
        val held = FakeEnv(askable.copy(heldByOther = true)).also { err(it) }
        val hidden = FakeEnv(askable, visible = false).also { err(it) }
        val noTap = FakeEnv(askable, tap = false).also { err(it) }
        val satisfied = FakeEnv(askable.copy(granted = true)).also { outcome(it) }
        assertTrue(background.logs.single().endsWith("reason=background"))
        assertTrue(undeclared.logs.single().endsWith("reason=undeclared"))
        assertTrue(denied.logs.single().endsWith("reason=denied by user"))
        assertTrue(held.logs.single().endsWith("reason=held by other app"))
        assertTrue(hidden.logs.single().endsWith("reason=not on screen"))
        assertTrue(noTap.logs.single().endsWith("reason=no tap"))
        assertTrue(satisfied.logs.single().endsWith("reason=satisfied"))
    }

    // A plain launcher capability (location) is asked on the consent screen, never the special sheet.
    private val plain = Capability.LOCATION
    private val plainAskable = askable.copy()

    @Test
    fun plainCapabilityNeverAskedPromptsConsentAndRuns() {
        val env = FakeEnv(plainAskable, sheet = { grants(it); true })
        assertEquals(ran, outcome(env, capability = plain))
        assertEquals(listOf(AskKind.CONSENT), env.kinds)
        assertTrue(env.logs.single().startsWith("consent app=$app capability=${plain.id} reason=sheet ran"))
    }

    @Test
    fun plainCapabilityTheUserDeniedDoesNotPrompt() {
        val env = FakeEnv(plainAskable.copy(deniedByUser = true), sheet = { grants(it); true })
        assertEquals(message, err(env, capability = plain)?.message)
        assertEquals(0, env.sheets)
        assertTrue(env.logs.single().endsWith("reason=denied by user"))
    }

    @Test
    fun plainCapabilityNotDeclaredOrInBackgroundDoesNotPrompt() {
        val undeclared = FakeEnv(plainAskable.copy(declared = false), sheet = { grants(it); true })
        assertEquals(message, err(undeclared, capability = plain)?.message)
        assertEquals(0, undeclared.sheets)
        val background = FakeEnv(plainAskable, sheet = { grants(it); true })
        assertEquals(message, err(background, background = true, capability = plain)?.message)
        assertEquals(0, background.sheets)
    }

    @Test
    fun plainCapabilityWithoutAGateGetsNoPrompt() {
        val noTap = FakeEnv(plainAskable, tap = false, sheet = { grants(it); true })
        assertEquals(code, err(noTap, capability = plain)?.code)
        assertEquals(0, noTap.sheets)
        val hidden = FakeEnv(plainAskable, visible = false, sheet = { grants(it); true })
        assertEquals(code, err(hidden, capability = plain)?.code)
        assertEquals(0, hidden.sheets)
    }

    @Test
    fun plainCapabilityDeclinedOnTheConsentScreenIsNotAskedAgain() {
        val cooldown = DenyCooldown()
        val env = FakeEnv(plainAskable, sheet = { true })
        err(env, cooldown, capability = plain)
        assertTrue(err(env, cooldown, capability = plain) != null)
        assertEquals("cooldown: no second consent screen", 1, env.sheets)
    }

    @Test
    fun specialCapabilityUsesTheSpecialKindOnly() {
        val env = FakeEnv(askable, sheet = { grants(it); true })
        outcome(env)
        assertEquals(listOf(AskKind.SPECIAL), env.kinds)
    }

    private val camera = Capability.CAMERA
    private val mic = Capability.MICROPHONE
    private val never = SpecialState(declared = true, granted = false, deniedByUser = false, heldByOther = false, systemOff = false)

    @Test
    fun cameraAndMicNeverAskedAskOnceWithBothIds() {
        val env = ConsentEnv(mutableMapOf(camera to never, mic to never), answer = { e ->
            e.states[camera] = e.states.getValue(camera).copy(granted = true)
            e.states[mic] = e.states.getValue(mic).copy(granted = true)
        })
        assertEquals(SpecialAsk.Outcome.SheetRan, consentAsk(env, listOf(camera, mic)))
        assertEquals(1, env.sheets)
        assertEquals(listOf("camera", "microphone"), env.asked)
        assertEquals(listOf(AskKind.CONSENT), env.kinds)
        assertEquals(1, env.refreshes)
    }

    @Test
    fun unticking_oneOfThemRefusesTheWholeRequest() {
        val env = ConsentEnv(mutableMapOf(camera to never, mic to never), answer = { e ->
            e.states[camera] = e.states.getValue(camera).copy(granted = true)
        })
        assertEquals(code, (consentAsk(env, listOf(camera, mic)) as SpecialAsk.Outcome.Refused).err.code)
        assertEquals(1, env.sheets)
        assertEquals(0, env.refreshes)
    }

    @Test
    fun aSpecialOrDeniedCapabilityInTheSetAsksNothing() {
        val special = ConsentEnv(mutableMapOf(camera to never, Capability.USAGE_READ to never))
        assertTrue(consentAsk(special, listOf(camera, Capability.USAGE_READ)) is SpecialAsk.Outcome.Refused)
        assertEquals(0, special.sheets)
        val denied = ConsentEnv(mutableMapOf(camera to never, mic to never.copy(deniedByUser = true)))
        assertTrue(consentAsk(denied, listOf(camera, mic)) is SpecialAsk.Outcome.Refused)
        assertEquals(0, denied.sheets)
    }

    @Test
    fun aSetInTheBackgroundAsksNothing() {
        val env = ConsentEnv(mutableMapOf(camera to never, mic to never))
        assertTrue(consentAsk(env, listOf(camera, mic), background = true) is SpecialAsk.Outcome.Refused)
        assertEquals(0, env.sheets)
    }

    @Test
    fun undeclaredCapabilityGetsNoSheet() {
        val env = FakeEnv(askable.copy(declared = false))
        assertEquals(message, err(env)?.message)
        assertEquals(0, env.sheets)
    }

    @Test
    fun deniedByUserKeepsThePlainError() {
        val env = FakeEnv(askable.copy(deniedByUser = true))
        assertEquals(message, err(env)?.message)
        assertEquals(0, env.sheets)
    }

    @Test
    fun exclusiveGroupHeldByAnotherAppGetsNoSheet() {
        val env = FakeEnv(askable.copy(heldByOther = true))
        assertEquals(message, err(env)?.message)
        assertEquals(0, env.sheets)
    }

    @Test
    fun backgroundRunGetsNoSheet() {
        val env = FakeEnv(askable, sheet = { grants(it); true })
        assertEquals(message, err(env, background = true)?.message)
        assertEquals(0, env.sheets)
    }

    @Test
    fun noTapOrHiddenScreenFailsTheGatesWithAHint() {
        val noTap = FakeEnv(askable, tap = false, sheet = { grants(it); true })
        val e = err(noTap)
        assertEquals(code, e?.code)
        assertTrue(e?.message?.endsWith(SpecialAsk.HINT + ".") == true)
        assertEquals(0, noTap.sheets)

        val hidden = FakeEnv(askable, visible = false, sheet = { grants(it); true })
        assertTrue(err(hidden) != null)
        assertEquals(0, hidden.sheets)
    }

    @Test
    fun cancelReturnsTheErrorWithTheHint() {
        val env = FakeEnv(askable, sheet = { true })
        val e = err(env)
        assertEquals(code, e?.code)
        assertEquals("$message. ${SpecialAsk.HINT}.", e?.message)
        assertEquals(1, env.sheets)
    }

    @Test
    fun cancelStartsACooldownForTheSameCapability() {
        val cooldown = DenyCooldown()
        val env = FakeEnv(askable, sheet = { true })
        err(env, cooldown)
        env.now = DenyCooldown.WINDOW_MS - 1
        assertTrue(err(env, cooldown) != null)
        assertEquals("no second sheet inside the window", 1, env.sheets)

        val later = FakeEnv(askable, now = DenyCooldown.WINDOW_MS, sheet = { grants(it); true })
        assertEquals(ran, outcome(later, cooldown))
        assertEquals(1, later.sheets)
    }

    @Test
    fun sheetThatCannotStartLeavesNoCooldown() {
        val cooldown = DenyCooldown()
        val env = FakeEnv(askable, sheet = { false })
        assertTrue(err(env, cooldown) != null)
        assertTrue(err(env, cooldown) != null)
        assertEquals(2, env.sheets)
    }

    @Test
    fun androidAccessOffOpensTheSheetOnItsStep() {
        val env = FakeEnv(askable.copy(granted = true, systemOff = true), sheet = { it.state = it.state.copy(systemOff = false); true })
        assertEquals(ran, outcome(env, needSystem = true, code = BridgeErrors.OS_PERMISSION_MISSING))
        assertEquals(1, env.sheets)
        assertEquals(true, env.lastNeedSystem)
    }

    @Test
    fun clearDropsOnlyThatAppsCooldown() {
        val cooldown = DenyCooldown()
        val denied = RuntimePermissions.Answer.DENIED
        cooldown.record(app, listOf(cap.id), denied, now = 0)
        cooldown.record("app-2", listOf(cap.id), denied, now = 0)
        cooldown.clear(app)
        assertEquals(null, cooldown.lastRefusal(app, listOf(cap.id), now = 1))
        assertEquals(denied, cooldown.lastRefusal("app-2", listOf(cap.id), now = 1))
    }

    @Test
    fun androidAccessStillOffAfterTheSheetKeepsTheOsError() {
        val env = FakeEnv(askable.copy(granted = true, systemOff = true), sheet = { true })
        val e = err(env, needSystem = true, code = BridgeErrors.OS_PERMISSION_MISSING)
        assertEquals(BridgeErrors.OS_PERMISSION_MISSING, e?.code)
        assertEquals(1, env.sheets)
    }

    @Test
    fun androidAccessAlreadyOnIsSatisfiedAndRunsNoSheet() {
        // Review: nothing ran, so the caller keeps its own module result (no second module call).
        val env = FakeEnv(askable.copy(granted = true, systemOff = false))
        assertEquals(satisfied, outcome(env, needSystem = true, code = BridgeErrors.OS_PERMISSION_MISSING))
        assertEquals(0, env.sheets)
    }

    @Test
    fun sheetThatClosesWithAccessStillOffIsNotSheetRan() {
        val env = FakeEnv(askable.copy(granted = true, systemOff = true), sheet = { true })
        assertTrue(outcome(env, needSystem = true, code = BridgeErrors.OS_PERMISSION_MISSING) is SpecialAsk.Outcome.Refused)
    }
}
