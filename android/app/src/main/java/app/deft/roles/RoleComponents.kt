package app.deft.roles

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import app.deft.DeftApp
import app.deft.admin.DeftDeviceAdminReceiver
import app.deft.catalog.Capability
import app.deft.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * PARITY G11: keeps the role components ([RoleComponent]) enabled only while a web app holds the
 * matching special grant. Disabling one makes the launcher stop qualifying for the Android role,
 * and Android then gives the role back to its default app. Re-evaluated on every special grant
 * change, uninstall, manifest change and process start, and right after a grant in the sheet
 * (before Android's role request, which needs the component enabled).
 *
 * `device.admin` has no component to switch: the receiver must stay enabled for Android's
 * activation screen. Instead, sync deactivates the launcher's device admin when no app holds the grant.
 */
object RoleComponents {
    private const val TAG = "RoleComponents"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    suspend fun sync(context: Context, repository: AppRepository) = lock.withLock {
        val groups = RoleComponent.entries.mapNotNull { it.cap.exclusiveGroup }.distinct()
        val held = groups.filter { repository.specialHolder(it) != null }.toSet()
        val on = RoleSpec.enabledComponents(held)
        // PARITY B4: with no payment holder, the last holder's AIDs go too, so a later holder never
        // gets commands for AIDs it did not register. Android keeps dynamic AIDs across restarts;
        // cleared before the service is disabled, while Android still knows it.
        if (Capability.NFC_HCE_PAYMENT.exclusiveGroup !in held) {
            app.deft.nfc.DeftPaymentApduService.clearAids(context)
        }
        val pm = context.packageManager
        for (component in RoleComponent.entries) {
            val name = ComponentName(context.packageName, component.className)
            val want = if (component in on) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            val now = runCatching { pm.getComponentEnabledSetting(name) }.getOrNull() ?: continue
            // DEFAULT means the manifest value, which is disabled for every role component.
            val effectiveNow = if (now == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                now
            }
            if (effectiveNow != want) {
                runCatching { pm.setComponentEnabledSetting(name, want, PackageManager.DONT_KILL_APP) }
                    .onFailure { Log.w(TAG, "${component.name} not updated", it) }
            }
        }
        // PARITY G11 device.admin: with no app holding the grant, the launcher's device admin goes off.
        val adminGroup = Capability.DEVICE_ADMIN.exclusiveGroup!!
        if (repository.specialHolder(adminGroup) == null && DeftDeviceAdminReceiver.isActive(context)) {
            runCatching {
                context.getSystemService(DevicePolicyManager::class.java)
                    ?.removeActiveAdmin(DeftDeviceAdminReceiver.component(context))
            }.onFailure { Log.w(TAG, "device admin not removed", it) }
        }
    }

    /**
     * PARITY G11: links the launcher itself opens (a web app's outside link, `intents.open`) must
     * reach a real browser, not come back to the browser-role holder. Adds the role components to
     * a chooser's excluded list.
     */
    fun excludeSelf(context: Context, chooser: Intent): Intent = chooser.putExtra(
        Intent.EXTRA_EXCLUDE_COMPONENTS,
        RoleComponent.entries.map { ComponentName(context.packageName, it.className) }.toTypedArray(),
    )

    /** PARITY G11: true when [intent] would open in the launcher itself (it is the default browser). */
    fun resolvesToSelf(context: Context, intent: Intent): Boolean = runCatching {
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    /** Off the caller's thread; never fails the caller. */
    fun syncSoon(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { sync(app, DeftApp.instance.repository) }
                .onFailure { Log.w(TAG, "sync failed", it) }
        }
    }
}
