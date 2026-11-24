package app.deft.bridge

import app.deft.catalog.Capability
import app.deft.catalog.CapabilityKind
import app.deft.catalog.GrantLevel
import app.deft.catalog.isUserDenial
import app.deft.data.AppRepository
import app.deft.data.GrantEntity
import app.deft.data.InstalledWebApp

/**
 * The capability ids a page's bridge allow-list holds (host, agent preview, background run,
 * live wallpaper, keyboard). Review: only capabilities the manifest still declares; a grant row
 * left from an older manifest adds nothing. [BridgeService.invoke] checks the same on every call.
 */
object GrantedPrefixes {
    fun of(declared: List<Capability>, rows: List<GrantEntity>): Set<String> {
        val byId = rows.associate { it.capability to it.level }
        val declaredIds = declared.map { it.id }.toSet()
        val ids = mutableSetOf<String>()
        for (cap in declared) {
            if (cap.kind != CapabilityKind.BRIDGE) continue
            val level = byId[cap.id]
            if (level == null || level != GrantLevel.DENIED.name) ids += cap.id
        }
        for (row in rows) {
            if (row.level != GrantLevel.DENIED.name && row.capability in declaredIds) ids += row.capability
        }
        return ids
    }

    /** Reads the app's current row, so a stale [app] cannot list a capability the manifest dropped. */
    suspend fun forApp(repository: AppRepository, app: InstalledWebApp): Set<String> {
        val declared = repository.getApp(app.id)?.requiredCapabilities ?: return emptySet()
        return of(declared, repository.grantsForApp(app.id))
    }

    /**
     * What one foreground page's bridge may do, read fresh. [granted] is what the page sees (its caps and shims):
     * exactly [of]. [askable] is what a call may reach BridgeService with: a bridge capability that is granted, never
     * decided, or DENIED without a user denial, so BridgeService grants it or asks at the point of use. [declared] is
     * the bridge capabilities the manifest still declares; it only tells a refusal's reason apart.
     */
    data class Gates(val granted: Set<String>, val askable: Set<String>, val declared: Set<String>)

    fun gatesOf(declared: List<Capability>, rows: List<GrantEntity>): Gates {
        val bridge = declared.filter { it.kind == CapabilityKind.BRIDGE }
        val byId = rows.associateBy { it.capability }
        val askable = bridge.filter { cap ->
            val row = byId[cap.id]
            !isUserDenial(row?.level, row?.deniedByUser == true)
        }.map { it.id }.toSet()
        return Gates(granted = of(declared, rows), askable = askable, declared = bridge.map { it.id }.toSet())
    }

    suspend fun gatesForApp(repository: AppRepository, app: InstalledWebApp): Gates {
        val declared = repository.getApp(app.id)?.requiredCapabilities ?: return Gates(emptySet(), emptySet(), emptySet())
        return gatesOf(declared, repository.grantsForApp(app.id))
    }
}
