package app.deft.bridge.modules

import android.os.SystemClock
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.BridgeService
import app.deft.bridge.CallRate
import app.deft.bridge.NetFetchSpec
import app.deft.catalog.Capability
import app.deft.catalog.isGranted

/**
 * `net.fetch`: a native GET/HEAD with no CORS rules, for feeds and public endpoints the web
 * page cannot read. Policy lives in [NetFetchSpec]; the checked client is [NetFetchClient],
 * which the agent's web tools share.
 */
class NetFetchModule(private val service: BridgeService) : BridgeModule {
    private val rate = CallRate(NetFetchSpec.RATE_PER_MINUTE, 60_000L)

    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        if (!rate.tryAcquire(request.webAppId, SystemClock.elapsedRealtime())) {
            return BridgeOutcome.Err(BridgeErrors.RATE_LIMITED, "At most ${NetFetchSpec.RATE_PER_MINUTE} fetches a minute")
        }
        val args = when (val parsed = NetFetchClient.parse(request.args)) {
            is NetFetchClient.Parsed.Bad -> return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, parsed.message)
            is NetFetchClient.Parsed.Ok -> parsed.args
        }
        val repo = service.repository
        val app = repo.getApp(request.webAppId) ?: return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "Unknown app")
        val listed = repo.networkFor(app)
        val open = holds(request.webAppId, Capability.NETWORK_OPEN)
        val local = holds(request.webAppId, Capability.NET_LOCAL)
        return NetFetchClient.fetch(args.target, args.method, args.mode, args.headers, listed, open, local)
    }

    private suspend fun holds(webAppId: String, cap: Capability): Boolean {
        val repo = service.repository
        return cap in repo.declaredCapabilities(webAppId) && repo.grantLevel(webAppId, cap).isGranted()
    }
}
