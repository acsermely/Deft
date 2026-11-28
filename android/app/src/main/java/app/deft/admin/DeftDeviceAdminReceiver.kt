package app.deft.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

// DEVICE-UNCHECKED (extra roles (browser, assistant, IME, wallpaper, device admin)): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: each role can be granted to one origin and reverted in system settings.
// Remove this note once checked.
/**
 * The launcher's device admin (`device.admin`). Its policy file (res/xml/device_admin.xml)
 * declares only `force-lock`: it can lock the screen and nothing else. No wipe, no password rules,
 * no camera switch. The user activates it from the special-access sheet; removing the grant (or the
 * app) deactivates it ([app.deft.roles.RoleComponents.sync]).
 */
class DeftDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context): ComponentName = ComponentName(context, DeftDeviceAdminReceiver::class.java)

        fun isActive(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(component(context)) == true
    }
}
