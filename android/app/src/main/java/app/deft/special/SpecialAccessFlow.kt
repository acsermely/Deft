package app.deft.special

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.deft.a11y.DeftAccessibilityService
import app.deft.notifications.DeftNotificationListener

/**
 * One screen to try for a special access, with the extra that points it at this app or component.
 * [packageData] adds the `package:` URI that highlights this app where Android supports it.
 */
internal data class SettingsTarget(
    val action: String,
    val packageData: Boolean = false,
    val extraKey: String? = null,
    val extraValue: String? = null,
    /** More extras, for a screen whose extra name differs between Android versions. */
    val moreExtras: List<Pair<String, String>> = emptyList(),
)

/**
 * The one helper for every special-access step. It picks the deepest Settings screen Android allows
 * for the phone's API level (the screens are tried in order, the plain list last), starts the first one that
 * resolves, and tracks one Android step from "needs a switch" to "done" on return.
 *
 * Pure parts ([targets], [exactAlarmTargets], [restrictedHintDue], the stage functions) take plain values so the
 * unit tests need no device.
 */
object SpecialAccessFlow {
    /** Where one Android step of the sheet stands. */
    enum class Stage {
        /** Accessibility on Android 13+ for an app not from a store: the one-time "Allow restricted settings" steps. */
        RESTRICTED_HINT,

        /** The Android screen has not been opened yet. */
        READY,

        /** The Android screen was opened; the state is read again when the user comes back. */
        OPENED,

        /** Android now reports the access as on. */
        DONE,
    }

    const val PREFS = "special_access"
    private const val KEY_RESTRICTED_HINT_SHOWN = "restricted_hint_shown"
    private const val PLAY_STORE = "com.android.vending"

    /** Android 12+ accessibility details screen. Literal, so the code does not depend on the SDK it was compiled with. */
    private const val ACTION_ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /**
     * Extra that names the accessibility service on the details screen. Not verified on a device yet: when
     * Android ignores it, the screen is the accessibility list, which still works.
     */
    private const val EXTRA_ACCESSIBILITY_COMPONENT = "android.provider.extra.ACCESSIBILITY_COMPONENT_NAME"

    /** `Intent.EXTRA_COMPONENT_NAME`: the extra Settings' accessibility details screen reads (flattened component name). */
    private const val EXTRA_COMPONENT_NAME = "android.intent.extra.COMPONENT_NAME"

    /** Pure: the screens to try for [access] on an [sdk] level, deepest first. The last entry is the plain list. */
    internal fun targets(
        access: SystemAccess,
        sdk: Int,
        pkg: String,
        listenerComponent: String,
        a11yComponent: String,
    ): List<SettingsTarget> = when (access) {
        SystemAccess.ACCESSIBILITY -> listOfNotNull(
            if (sdk >= 31) SettingsTarget(
                ACTION_ACCESSIBILITY_DETAILS,
                extraKey = EXTRA_ACCESSIBILITY_COMPONENT,
                extraValue = a11yComponent,
                moreExtras = listOf(EXTRA_COMPONENT_NAME to a11yComponent),
            ) else null,
            SettingsTarget(access.action),
        )
        SystemAccess.NOTIFICATION_LISTENER -> listOfNotNull(
            if (sdk >= 30) {
                SettingsTarget(
                    Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
                    extraKey = Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    extraValue = listenerComponent,
                )
            } else {
                null
            },
            SettingsTarget(access.action),
        )
        SystemAccess.USAGE_ACCESS, SystemAccess.DND_ACCESS -> listOf(
            SettingsTarget(access.action, packageData = true),
            SettingsTarget(access.action),
        )
        SystemAccess.BACKGROUND_LOCATION -> listOf(SettingsTarget(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageData = true))
        else -> listOf(SettingsTarget(access.action))
    }

    /** Pure: exact alarms (Android 12+) on this app's package; the app's own page when the request screen is missing. */
    internal fun exactAlarmTargets(sdk: Int): List<SettingsTarget> = listOfNotNull(
        if (sdk >= 31) SettingsTarget(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageData = true) else null,
        SettingsTarget(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageData = true),
    )

    /**
     * Pure: the one-time restricted-settings steps are due for accessibility on Android 13+ when the app was not
     * installed from the Play Store, the switch is off, and the steps were not shown before.
     */
    internal fun restrictedHintDue(access: SystemAccess, sdk: Int, installer: String?, alreadyShown: Boolean): Boolean =
        access == SystemAccess.ACCESSIBILITY && sdk >= 33 && installer != PLAY_STORE && !alreadyShown

    /** Pure: the stage when an Android step begins. A switch that is already on is done. */
    internal fun start(systemOn: Boolean, hintDue: Boolean): Stage = when {
        systemOn -> Stage.DONE
        hintDue -> Stage.RESTRICTED_HINT
        else -> Stage.READY
    }

    /** Pure: the user tapped the step that opens Android's screen. */
    internal fun open(stage: Stage): Stage = when (stage) {
        Stage.RESTRICTED_HINT, Stage.READY -> Stage.OPENED
        else -> stage
    }

    /** Pure: the user came back (or the sheet was shown again). Android reporting the access on finishes the step. */
    internal fun resumed(stage: Stage, systemOn: Boolean): Stage =
        if (systemOn) Stage.DONE else stage

    /** The stage for [access] when its Android step begins on this phone. Marks the one-time hint as shown when it is due. */
    fun beginSystem(context: Context, access: SystemAccess): Stage {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shown = prefs.getBoolean(KEY_RESTRICTED_HINT_SHOWN, false)
        val on = access.isGranted(context)
        // Only a switch that is off needs the steps; an access already on must not spend the one-time hint.
        val hint = !on && restrictedHintDue(access, Build.VERSION.SDK_INT, installer(context), shown)
        if (hint) prefs.edit().putBoolean(KEY_RESTRICTED_HINT_SHOWN, true).apply()
        return start(systemOn = on, hintDue = hint)
    }

    /** The screens for [access] that resolve on this phone, deepest first. Empty when none does. */
    fun intents(context: Context, access: SystemAccess): List<Intent> {
        val pkg = context.packageName
        val listener = DeftNotificationListener.component(context).flattenToString()
        val a11y = ComponentName(context, DeftAccessibilityService::class.java).flattenToString()
        return targets(access, Build.VERSION.SDK_INT, pkg, listener, a11y).map { it.toIntent(pkg) }
            .filter { it.resolveActivity(context.packageManager) != null }
    }

    /** The exact-alarm screens that resolve on this phone. */
    fun exactAlarmIntents(context: Context): List<Intent> =
        exactAlarmTargets(Build.VERSION.SDK_INT).map { it.toIntent(context.packageName) }
            .filter { it.resolveActivity(context.packageManager) != null }

    /**
     * Starts the first of [intents] that Android accepts. False when none can start. A non-activity context
     * gets the new-task flag.
     */
    fun start(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            val started = runCatching {
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.isSuccess
            if (started) return true
        }
        return false
    }

    /** Starts the deepest Settings screen for [access]. False when this phone has none. */
    fun start(context: Context, access: SystemAccess): Boolean = start(context, intents(context, access))

    private fun installer(context: Context): String? = runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    }.getOrNull()

    private fun SettingsTarget.toIntent(pkg: String): Intent = Intent(action).apply {
        if (packageData) data = Uri.fromParts("package", pkg, null)
        if (extraKey != null) putExtra(extraKey, extraValue)
        for ((key, value) in moreExtras) putExtra(key, value)
    }
}
