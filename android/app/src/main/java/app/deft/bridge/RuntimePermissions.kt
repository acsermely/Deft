package app.deft.bridge

import android.Manifest

/**
 * Pure rules for runtime permission requests made by bridge calls and web requests. The asking itself lives in
 * [OsPermissions]; the host activity shows the dialogs.
 */
object RuntimePermissions {
    enum class Answer { GRANTED, DENIED, BLOCKED }

    /** True when the missing permissions may be asked now: declared, on screen, and a tap just happened. */
    fun canAsk(missing: List<String>, declared: Set<String>, hasVisibleHost: Boolean, recentTap: Boolean): Boolean =
        missing.isNotEmpty() && missing.all { it in declared } && hasVisibleHost && recentTap

    /**
     * Android shows a rationale after a refusal only while another dialog is still allowed. Without one,
     * the refusal was permanent ("Don't ask again", or refused again), so the user must use app settings.
     */
    fun answerFor(allGranted: Boolean, rationaleStillShown: Boolean): Answer = when {
        allGranted -> Answer.GRANTED
        rationaleStillShown -> Answer.DENIED
        else -> Answer.BLOCKED
    }

    /** The error text for a refusal; a permanent one also points at the app's settings page. */
    fun refusalMessage(message: String, answer: Answer): String =
        if (answer == Answer.BLOCKED) "$message in Android's app settings" else message

    /** The words a user sees for one runtime permission. The only place these names are kept. */
    fun friendlyName(permission: String): String = when (permission) {
        Manifest.permission.CAMERA -> "camera"
        Manifest.permission.RECORD_AUDIO -> "microphone"
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        -> "location"
        Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS -> "contacts"
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR -> "calendar"
        Manifest.permission.CALL_PHONE -> "phone calls"
        Manifest.permission.READ_PHONE_STATE -> "phone state"
        Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG -> "call log"
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE -> "Bluetooth"
        Manifest.permission.POST_NOTIFICATIONS -> "notifications"
        Manifest.permission.ACTIVITY_RECOGNITION -> "physical activity"
        Manifest.permission.NEARBY_WIFI_DEVICES -> "nearby devices"
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        -> "photos, videos and music"
        else -> "a phone permission"
    }

    /** "camera and microphone", for a sheet or an error that covers several permissions. */
    fun friendlyNames(permissions: List<String>): String =
        permissions.map { friendlyName(it) }.distinct().joinToString(" and ")
}
