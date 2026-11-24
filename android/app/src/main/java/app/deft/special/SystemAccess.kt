package app.deft.special

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.deft.catalog.Capability

/**
 * PARITY G0: Android's own special-access switches that the launcher must hold before a
 * special capability can work. The FL grant is per app; this switch is launcher-wide, so the
 * grant sheet opens the system screen once and reads the state back.
 */
enum class SystemAccess(
    val label: String,
    val action: String,
    /** PARITY G11: the Android role this step requests with [RoleManager.createRequestRoleIntent]; [action] is where to undo it. */
    val role: String? = null,
) {
    ACCESSIBILITY("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS),
    NOTIFICATION_LISTENER("Notification access", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
    USAGE_ACCESS("Usage access", Settings.ACTION_USAGE_ACCESS_SETTINGS),
    DND_ACCESS("Do Not Disturb access", Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),

    /**
     * PARITY G5: Android's "Allow all the time" location. A runtime permission, but on Android 11
     * and newer it must be asked on its own, after precise or approximate location, and the
     * request opens the launcher's location settings page. [settingsIntent] with a context is
     * the launcher's app info page, for when Android no longer shows the request.
     */
    BACKGROUND_LOCATION("Location: Allow all the time", Settings.ACTION_APPLICATION_DETAILS_SETTINGS),

    /** PARITY G11: Deft as the phone's default browser (`role.browser`). */
    ROLE_BROWSER("Default browser", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, RoleManager.ROLE_BROWSER),

    /**
     * PARITY G11: Deft as the digital assistant (`role.assistant`). Many phones do not
     * let apps request this role; the sheet then sends the user to Default apps.
     */
    ROLE_ASSISTANT("Digital assistant app", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, RoleManager.ROLE_ASSISTANT),

    /** PARITY G11: Deft's device admin, lock only (`device.admin`). Undone in Security settings. */
    DEVICE_ADMIN("Device admin app", Settings.ACTION_SECURITY_SETTINGS),

    /**
     * PARITY G11: Deft's live wallpaper as the phone's wallpaper (`wallpaper`). Android's
     * preview screen sets it; the user picks another wallpaper under Wallpaper ([Intent.ACTION_SET_WALLPAPER]).
     */
    WALLPAPER("Live wallpaper", Intent.ACTION_SET_WALLPAPER),

    /**
     * PARITY G11: Deft's keyboard as the phone's keyboard (`ime`). Two Android steps: turn
     * it on in keyboard settings ([settingsIntent]), then choose it in the system keyboard picker
     * (`InputMethodManager.showInputMethodPicker`). Granted only when it is on and chosen.
     */
    IME("Keyboard", Settings.ACTION_INPUT_METHOD_SETTINGS),

    /**
     * PARITY F7b: Deft screens calls (`calls.screen`). Already true while it is the
     * default phone app (Android binds the dialer's screening service for every call); otherwise
     * Android's call screening role ("Caller ID and spam app") is requested.
     */
    CALL_SCREENING("Caller ID and spam app", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, RoleManager.ROLE_CALL_SCREENING),

    /**
     * PARITY B4: Deft's payment card emulation service as the phone's default payment
     * app (`nfc.hce.payment`). Android's own question ([requestIntent]); undone under Contactless payments.
     */
    PAYMENT_DEFAULT("Default payment app", Settings.ACTION_NFC_PAYMENT_SETTINGS),
    ;

    fun settingsIntent(): Intent = Intent(action)

    /**
     * The most direct screen for this step: the launcher's own page where Android has one (the notification
     * listener's detail page on Android 11 and newer, the app's page with a package URI), otherwise [settingsIntent].
     */
    fun settingsIntent(context: Context): Intent =
        SpecialAccessFlow.intents(context, this).firstOrNull() ?: Intent(action)

    /**
     * PARITY G11: Android's role request for [role], or null when this is not a role or the phone
     * does not offer the role (some phones leave roles out).
     */
    fun roleRequestIntent(context: Context): Intent? {
        val name = role ?: return null
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(name)) return null
        return rm.createRequestRoleIntent(name)
    }

    /**
     * PARITY G11: Android's own request screen, started for a result: the role request, or the
     * device admin activation. Null for steps that open a settings screen instead, or when the phone
     * does not offer the role.
     */
    fun requestIntent(context: Context): Intent? = when (this) {
        DEVICE_ADMIN -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, app.deft.admin.DeftDeviceAdminReceiver.component(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Deft only uses this to lock the screen for the one app you chose. It cannot erase the phone or change your screen lock.",
            )
        WALLPAPER -> Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(
                android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                app.deft.wallpaper.DeftWallpaperService.component(context),
            )
        PAYMENT_DEFAULT -> app.deft.nfc.DeftPaymentApduService.requestDefaultIntent(context)
        else -> roleRequestIntent(context)
    }

    /** PARITY G11: true when this step is Android's own request screen ([requestIntent]), not a settings switch. */
    val asksInPlace: Boolean get() = role != null || this == DEVICE_ADMIN || this == WALLPAPER || this == PAYMENT_DEFAULT

    fun isGranted(context: Context): Boolean {
        val pkg = context.packageName
        // PARITY F7b: the default dialer screens calls without the call screening role.
        if (this == CALL_SCREENING) {
            val rm = context.getSystemService(RoleManager::class.java) ?: return false
            return rm.isRoleHeld(RoleManager.ROLE_DIALER) || rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        }
        role?.let { name ->
            return context.getSystemService(RoleManager::class.java)?.isRoleHeld(name) == true
        }
        return when (this) {
            // PARITY G9: the launcher's own service component, not just any service of this package.
            ACCESSIBILITY -> app.deft.bridge.A11ySpec.enabledIn(
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                pkg,
                app.deft.a11y.DeftAccessibilityService::class.java.name,
            )
            // PARITY G8: the launcher's own listener component, not just any listener of this package.
            NOTIFICATION_LISTENER -> context.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(
                    app.deft.notifications.DeftNotificationListener.component(context),
                ) == true
            USAGE_ACCESS -> {
                val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), pkg) ==
                    AppOpsManager.MODE_ALLOWED
            }
            DND_ACCESS -> context.getSystemService(NotificationManager::class.java)
                ?.isNotificationPolicyAccessGranted == true
            BACKGROUND_LOCATION -> ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            ROLE_BROWSER, ROLE_ASSISTANT, CALL_SCREENING -> false // handled above
            DEVICE_ADMIN -> app.deft.admin.DeftDeviceAdminReceiver.isActive(context)
            WALLPAPER -> app.deft.wallpaper.DeftWallpaperService.isCurrent(context)
            IME -> app.deft.ime.DeftInputMethodService.isCurrent(context)
            PAYMENT_DEFAULT -> app.deft.nfc.DeftPaymentApduService.isDefault(context)
        }
    }

    companion object {
        /**
         * The Android access a bridge call needs for [cap] when it reports OS_PERMISSION_MISSING: the special
         * ones from [forCapability], and Do Not Disturb, which is a plain capability with its own access.
         */
        fun forAsk(cap: Capability): SystemAccess? = if (cap == Capability.DND) DND_ACCESS else forCapability(cap)

        fun forCapability(cap: Capability): SystemAccess? = when (cap) {
            Capability.ACCESSIBILITY -> ACCESSIBILITY
            Capability.NOTIFICATIONS_LISTEN -> NOTIFICATION_LISTENER
            Capability.USAGE_READ -> USAGE_ACCESS
            Capability.LOCATION_BACKGROUND -> BACKGROUND_LOCATION
            Capability.ROLE_BROWSER -> ROLE_BROWSER
            Capability.ROLE_ASSISTANT -> ROLE_ASSISTANT
            Capability.DEVICE_ADMIN -> DEVICE_ADMIN
            Capability.WALLPAPER -> WALLPAPER
            Capability.IME -> IME
            Capability.CALLS_SCREEN -> CALL_SCREENING
            Capability.NFC_HCE_PAYMENT -> PAYMENT_DEFAULT
            else -> null
        }
    }
}
