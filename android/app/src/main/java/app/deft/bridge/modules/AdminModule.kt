package app.deft.bridge.modules

import android.app.admin.DevicePolicyManager
import android.content.Context
import app.deft.DeftApp
import app.deft.admin.DeftDeviceAdminReceiver
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.catalog.Capability
import app.deft.roles.RoleSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `admin.*` (PARITY G11, SPECIAL exclusive `device.admin`): lock the screen through the launcher's
 * device admin, which can do nothing else (no wipe). Only the holder of group `device-admin` gets
 * here (the grant check in `BridgeService.invoke`; checked again). Works from the app on screen and
 * from background runs (lock on a trigger, such as leaving a place); at most one lock every
 * [RoleSpec.LOCK_MIN_INTERVAL_MS], so a misbehaving app cannot keep the user out of the phone.
 */
class AdminModule(private val context: Context) : BridgeModule {
    @Volatile private var lastLock: Long? = null

    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = withContext(Dispatchers.IO) {
        val group = Capability.DEVICE_ADMIN.exclusiveGroup!!
        if (DeftApp.instance.repository.specialHolder(group) != request.webAppId) {
            return@withContext BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "device.admin is revoked")
        }
        val active = DeftDeviceAdminReceiver.isActive(context)
        when (request.method) {
            "admin.status" -> BridgeOutcome.Ok(buildJsonObject { put("active", active) })
            "admin.lockNow" -> lockNow(active)
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private fun lockNow(active: Boolean): BridgeOutcome {
        if (!active) return BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Device admin app")
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (!RoleSpec.lockAllowed(lastLock, now)) {
                return BridgeOutcome.Err(BridgeErrors.IN_USE, "The phone was locked less than 30 seconds ago")
            }
            lastLock = now
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Device admin is not available on this phone")
        return try {
            dpm.lockNow()
            BridgeOutcome.Ok(buildJsonObject { put("locked", true) })
        } catch (e: SecurityException) {
            BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Device admin app")
        }
    }
}
