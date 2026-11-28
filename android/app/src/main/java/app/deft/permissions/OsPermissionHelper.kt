package app.deft.permissions

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.deft.catalog.Capability

object OsPermissionHelper {
    fun runtimePermissionsFor(capabilities: List<Capability>): List<String> {
        val out = mutableListOf<String>()
        for (cap in capabilities) {
            when (cap) {
                Capability.LOCATION -> {
                    out += Manifest.permission.ACCESS_FINE_LOCATION
                    out += Manifest.permission.ACCESS_COARSE_LOCATION
                }
                Capability.CAMERA -> out += Manifest.permission.CAMERA
                Capability.MICROPHONE, Capability.SPEECH_RECOGNIZE -> out += Manifest.permission.RECORD_AUDIO
                Capability.CONTACTS_READ -> out += Manifest.permission.READ_CONTACTS
                Capability.CONTACTS_WRITE -> {
                    out += Manifest.permission.READ_CONTACTS
                    out += Manifest.permission.WRITE_CONTACTS
                }
                Capability.CALLS_PLACE, Capability.CALLS_CONTROL, Capability.CALLLOG_READ, Capability.CALLLOG_WRITE -> {
                    out += Manifest.permission.CALL_PHONE
                    out += Manifest.permission.READ_PHONE_STATE
                    out += Manifest.permission.READ_CALL_LOG
                    out += Manifest.permission.WRITE_CALL_LOG
                    out += Manifest.permission.ANSWER_PHONE_CALLS
                    out += Manifest.permission.READ_PHONE_NUMBERS
                }
                Capability.SMS_READ, Capability.SMS_WRITE, Capability.SMS_SEND -> {
                    out += Manifest.permission.SEND_SMS
                    out += Manifest.permission.RECEIVE_SMS
                    out += Manifest.permission.READ_SMS
                    out += Manifest.permission.RECEIVE_MMS
                    out += Manifest.permission.RECEIVE_WAP_PUSH
                    out += Manifest.permission.READ_PHONE_STATE
                    out += Manifest.permission.POST_NOTIFICATIONS
                }
                Capability.NOTIFICATIONS_POST, Capability.ALARMS ->
                    out += Manifest.permission.POST_NOTIFICATIONS
                // Scans need location, and on Android 13 and newer the nearby-devices permission.
                Capability.WIFI_SCAN -> {
                    out += Manifest.permission.ACCESS_FINE_LOCATION
                    if (android.os.Build.VERSION.SDK_INT >= 33) out += Manifest.permission.NEARBY_WIFI_DEVICES
                }
                Capability.CALENDAR_READ, Capability.CALENDAR_WRITE -> {
                    out += Manifest.permission.READ_CALENDAR
                    out += Manifest.permission.WRITE_CALENDAR
                }
                Capability.NFC_READ, Capability.NFC_WRITE, Capability.NFC_TECH, Capability.NFC_HCE, Capability.NFC_HCE_PAYMENT ->
                    out += Manifest.permission.NFC
                Capability.BLUETOOTH_SERIAL, Capability.BLUETOOTH_BLE_CENTRAL, Capability.BLUETOOTH_BLE_PERIPHERAL -> {
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        out += Manifest.permission.BLUETOOTH_CONNECT
                        out += Manifest.permission.BLUETOOTH_SCAN
                        out += Manifest.permission.BLUETOOTH_ADVERTISE
                    } else {
                        @Suppress("DEPRECATION")
                        out += Manifest.permission.BLUETOOTH
                        @Suppress("DEPRECATION")
                        out += Manifest.permission.BLUETOOTH_ADMIN
                        out += Manifest.permission.ACCESS_FINE_LOCATION
                    }
                }
                Capability.USB, Capability.FILES_PICK, Capability.FILES_READ, Capability.FILES_WRITE,
                Capability.INTENTS_OPEN, Capability.INTENTS_START, Capability.SHARE_RECEIVE,
                // VIBRATE is an install-time permission; the rest need none.
                Capability.HAPTICS, Capability.DISPLAY, Capability.TILES, Capability.WIDGETS, Capability.CLIPBOARD_READ, Capability.MEDIA_DRM, Capability.MIDI,
                // Notification policy access is an install-time permission; the system screen grants the rest.
                Capability.TORCH, Capability.PIP, Capability.SYSTEM_STATS, Capability.TTS, Capability.AUDIO_CONTROL, Capability.DND, Capability.KEYSTORE,
                Capability.PAYMENTS_REQUEST, Capability.CALLS_BLOCK, Capability.NETWORK_OPEN, Capability.NETWORK_USER, Capability.WASM, Capability.WEB_EMBED,
                Capability.STORAGE_LARGE,
                // The multicast lock is an install-time permission; sockets need no runtime grant.
                Capability.NET_LOCAL, Capability.NET_SOCKETS,
                // Wi-Fi state and suggestions are install-time permissions; the panel needs none.
                Capability.WIFI_CONTROL,
                // The sign-in page opens in the browser; no Android permission.
                Capability.AUTH_SESSION,
                // Credential Manager needs no Android permission.
                Capability.WEBAUTHN,
                // Opening apps and the device id need no Android permission.
                Capability.APPS_LAUNCH, Capability.DEVICE_ID,
                // Runs inside the launcher's own service; no Android permission.
                Capability.BACKGROUND,
                // The media session lives in the foreground service; no Android permission.
                Capability.MEDIA_SESSION,
                // The launcher's own account type and sync adapter need no runtime permission.
                Capability.ACCOUNTS_OWN,
                -> Unit
                // The notification that must show while the app keeps running.
                Capability.FOREGROUND -> out += Manifest.permission.POST_NOTIFICATIONS
                // A push for a closed app can show as a notification.
                Capability.PUSH -> out += Manifest.permission.POST_NOTIFICATIONS
                // Special access is never asked at install. Usage access is a system-screen step (SystemAccess),
                // not a runtime permission.
                // ACCESS_BACKGROUND_LOCATION must be asked on its own after fine location (Android 11+),
                // so the special-access sheet asks it (SystemAccess.BACKGROUND_LOCATION), never install.
                // accounts.read needs no permission; Android 8+ shows only accounts visible to the launcher.
                Capability.APPS_LIST, Capability.USAGE_READ, Capability.NOTIFICATIONS_LISTEN, Capability.ACCESSIBILITY,
                Capability.CLIPBOARD_WATCH, Capability.ACCOUNTS_READ, Capability.LOCATION_BACKGROUND,
                // Roles are asked from the special-access sheet (RoleManager), never at install.
                Capability.ROLE_BROWSER, Capability.ROLE_ASSISTANT, Capability.DEVICE_ADMIN, Capability.WALLPAPER, Capability.IME,
                // The default dialer screens calls already; otherwise the sheet asks ROLE_CALL_SCREENING.
                Capability.CALLS_SCREEN,
                -> Unit
                // Carrier codes are dialled through the phone permission.
                Capability.USSD -> out += Manifest.permission.CALL_PHONE
                // Step sensors need physical activity access on Android 10 and newer.
                Capability.SENSORS -> if (android.os.Build.VERSION.SDK_INT >= 29) out += Manifest.permission.ACTIVITY_RECOGNITION
                // Android 13 and newer split media by type; older versions use one storage permission.
                Capability.MEDIA_READ -> if (android.os.Build.VERSION.SDK_INT >= 33) {
                    out += Manifest.permission.READ_MEDIA_IMAGES
                    out += Manifest.permission.READ_MEDIA_VIDEO
                    out += Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    out += Manifest.permission.READ_EXTERNAL_STORAGE
                }
            }
        }
        return out.distinct()
    }

    fun missingRuntime(context: Context, capabilities: List<Capability>): List<String> =
        runtimePermissionsFor(capabilities).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun needsSmsRole(context: Context, capabilities: List<Capability>): Boolean {
        if (capabilities.none { it.exclusiveGroup == "sms" }) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return !rm.isRoleHeld(RoleManager.ROLE_SMS)
    }

    fun smsRoleIntent(context: Context): Intent? {
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        return rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
    }

    fun needsDialerRole(context: Context, capabilities: List<Capability>): Boolean {
        if (capabilities.none { it.exclusiveGroup == "calls" }) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return !rm.isRoleHeld(RoleManager.ROLE_DIALER)
    }

    fun dialerRoleIntent(context: Context): Intent? {
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        return rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)
    }

    fun isGmsPresent(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo("com.google.android.gms", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
}
