package app.deft.ui

import android.app.Activity
import android.os.Build

/**
 * Tapjacking guard for the launcher's own approval screens (consent, confirm, special grant,
 * "Open with", key unlock, dashboard). Another app's overlay must not cover an Allow button and
 * pass the user's tap through, or hide what is being approved.
 *
 * - Android 12+: other apps' overlay windows are hidden while the screen shows.
 * - Every version: touches that arrive while another window covers ours are dropped. Set on the
 *   decor view, so it applies to the whole Compose tree (a ViewGroup that filters a touch never
 *   dispatches it to its children).
 */
object OverlayGuard {
    fun protect(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { activity.window.setHideOverlayWindows(true) }
        }
        activity.window.decorView.filterTouchesWhenObscured = true
    }
}
