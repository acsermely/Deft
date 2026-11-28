package app.deft.bridge.modules

import android.app.usage.UsageStatsManager
import android.content.Context
import app.deft.bridge.AppsSpec
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.HostSurfaces
import app.deft.bridge.jsonArg
import app.deft.special.SystemAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// DEVICE-UNCHECKED (app list, launch, usage stats and per-app device id): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a screen-time sample shows per-app usage.
// Remove this note once checked.
/**
 * `usage.stats` (SPECIAL exclusive `usage.read`): foreground time per app between two
 * times, from Android's usage statistics. Needs the Usage access switch the user turns on in
 * system settings; without it the call returns `os_permission_missing`. Foreground only.
 */
class UsageModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        if (request.method != "usage.stats") return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        val (since, until) = AppsSpec.usageRange(jsonArg(request.args, "sinceMs"), jsonArg(request.args, "untilMs"))
            ?: return BridgeOutcome.Err(
                BridgeErrors.INVALID_ARGS,
                "sinceMs must be before untilMs, and the window at most 366 days",
            )
        if (!SystemAccess.USAGE_ACCESS.isGranted(context)) {
            return BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Usage access")
        }
        val usage = context.getSystemService(UsageStatsManager::class.java)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Usage statistics are not available on this phone")
        return withContext(Dispatchers.IO) {
            try {
                val rows = usage.queryAndAggregateUsageStats(since, until).values
                    .filter { it.totalTimeInForeground > 0 }
                    .sortedByDescending { it.totalTimeInForeground }
                BridgeOutcome.Ok(
                    buildJsonArray {
                        for (row in rows) {
                            add(
                                buildJsonObject {
                                    put("package", row.packageName)
                                    put("foregroundMs", row.totalTimeInForeground)
                                    put("lastUsed", row.lastTimeUsed)
                                },
                            )
                        }
                    },
                )
            } catch (_: SecurityException) {
                BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Usage access")
            }
        }
    }
}
