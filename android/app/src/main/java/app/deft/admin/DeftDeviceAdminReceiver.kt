package app.deft.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * PARITY G11: the launcher's device admin (`device.admin`). Its policy file (res/xml/device_admin.xml)
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
