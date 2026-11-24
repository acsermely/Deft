package app.deft.special

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialAccessFlowTest {
    private val pkg = "app.deft"
    private val listener = "app.deft/.notifications.DeftNotificationListener"
    private val a11y = "app.deft/.a11y.DeftAccessibilityService"

    private fun targets(access: SystemAccess, sdk: Int) =
        SpecialAccessFlow.targets(access, sdk, pkg, listener, a11y)

    @Test
    fun notificationListenerUsesDetailScreenFromApi30() {
        assertEquals(
            listOf(
                SettingsTarget(
                    Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
                    extraKey = Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    extraValue = listener,
                ),
                SettingsTarget(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            ),
            targets(SystemAccess.NOTIFICATION_LISTENER, 30),
        )
    }

    @Test
    fun accessibilityUsesDetailScreenOnlyFromApi31() {
        assertEquals(
            listOf(SettingsTarget(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            targets(SystemAccess.ACCESSIBILITY, 30),
        )
        val detail = targets(SystemAccess.ACCESSIBILITY, 31)
        assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", detail.first().action)
        assertEquals(a11y, detail.first().extraValue)
        assertTrue(detail.first().moreExtras.contains("android.intent.extra.COMPONENT_NAME" to a11y))
        assertEquals(SettingsTarget(Settings.ACTION_ACCESSIBILITY_SETTINGS), detail.last())
    }

    @Test
    fun usageAndDndPointAtThisPackageThenFallBack() {
        assertEquals(
            listOf(
                SettingsTarget(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageData = true),
                SettingsTarget(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            ),
            targets(SystemAccess.USAGE_ACCESS, 30),
        )
        assertEquals(
            listOf(
                SettingsTarget(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, packageData = true),
                SettingsTarget(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
            ),
            targets(SystemAccess.DND_ACCESS, 33),
        )
    }

    @Test
    fun backgroundLocationOpensThisAppsPage() {
        assertEquals(
            listOf(SettingsTarget(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageData = true)),
            targets(SystemAccess.BACKGROUND_LOCATION, 30),
        )
    }

    @Test
    fun otherAccessesOpenTheirPlainScreen() {
        assertEquals(
            listOf(SettingsTarget(Settings.ACTION_INPUT_METHOD_SETTINGS)),
            targets(SystemAccess.IME, 35),
        )
    }

    @Test
    fun exactAlarmRequestNeedsApi31AndFallsBackToAppPage() {
        assertEquals(
            listOf(
                SettingsTarget(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageData = true),
                SettingsTarget(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageData = true),
            ),
            SpecialAccessFlow.exactAlarmTargets(31),
        )
        assertEquals(
            listOf(SettingsTarget(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageData = true)),
            SpecialAccessFlow.exactAlarmTargets(30),
        )
    }

    @Test
    fun restrictedHintOnlyForAccessibilityFromApi33OutsideThePlayStore() {
        val other = "com.example.installer"
        assertTrue(SpecialAccessFlow.restrictedHintDue(SystemAccess.ACCESSIBILITY, 33, other, alreadyShown = false))
        assertTrue(SpecialAccessFlow.restrictedHintDue(SystemAccess.ACCESSIBILITY, 34, null, alreadyShown = false))
        assertFalse(SpecialAccessFlow.restrictedHintDue(SystemAccess.ACCESSIBILITY, 33, "com.android.vending", alreadyShown = false))
        assertFalse(SpecialAccessFlow.restrictedHintDue(SystemAccess.ACCESSIBILITY, 32, other, alreadyShown = false))
        assertFalse(SpecialAccessFlow.restrictedHintDue(SystemAccess.ACCESSIBILITY, 33, other, alreadyShown = true))
        assertFalse(SpecialAccessFlow.restrictedHintDue(SystemAccess.USAGE_ACCESS, 33, other, alreadyShown = false))
    }

    @Test
    fun stageStartsDoneWhenAlreadyOnThenHintThenReady() {
        assertEquals(SpecialAccessFlow.Stage.DONE, SpecialAccessFlow.start(systemOn = true, hintDue = true))
        assertEquals(SpecialAccessFlow.Stage.RESTRICTED_HINT, SpecialAccessFlow.start(systemOn = false, hintDue = true))
        assertEquals(SpecialAccessFlow.Stage.READY, SpecialAccessFlow.start(systemOn = false, hintDue = false))
    }

    @Test
    fun openMovesOnlyFromHintOrReady() {
        assertEquals(SpecialAccessFlow.Stage.OPENED, SpecialAccessFlow.open(SpecialAccessFlow.Stage.READY))
        assertEquals(SpecialAccessFlow.Stage.OPENED, SpecialAccessFlow.open(SpecialAccessFlow.Stage.RESTRICTED_HINT))
        assertEquals(SpecialAccessFlow.Stage.DONE, SpecialAccessFlow.open(SpecialAccessFlow.Stage.DONE))
    }

    @Test
    fun returningFromSettingsFinishesOnlyWhenTheSwitchIsOn() {
        assertEquals(SpecialAccessFlow.Stage.DONE, SpecialAccessFlow.resumed(SpecialAccessFlow.Stage.OPENED, systemOn = true))
        assertEquals(SpecialAccessFlow.Stage.OPENED, SpecialAccessFlow.resumed(SpecialAccessFlow.Stage.OPENED, systemOn = false))
        assertEquals(SpecialAccessFlow.Stage.DONE, SpecialAccessFlow.resumed(SpecialAccessFlow.Stage.RESTRICTED_HINT, systemOn = true))
    }

    @Test
    fun wholeFlowForAccessibilityOnAndroid13Sideload() {
        // Start: the hint is due; the user goes through it, opens Settings, comes back with the switch still off, then on.
        var stage = SpecialAccessFlow.start(systemOn = false, hintDue = true)
        assertEquals(SpecialAccessFlow.Stage.RESTRICTED_HINT, stage)
        stage = SpecialAccessFlow.open(stage)
        stage = SpecialAccessFlow.resumed(stage, systemOn = false)
        assertEquals(SpecialAccessFlow.Stage.OPENED, stage)
        stage = SpecialAccessFlow.resumed(stage, systemOn = true)
        assertEquals(SpecialAccessFlow.Stage.DONE, stage)
    }
}
