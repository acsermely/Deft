package app.deft.data

import app.deft.webview.CspFlag
import app.deft.webview.CspPolicy
import androidx.room.withTransaction
import app.deft.workspace.OriginIds

import android.content.Intent
import android.net.Uri
import android.webkit.GeolocationPermissions
import app.deft.BuildConfig
import app.deft.DeftApp
import app.deft.bridge.BridgeService
import app.deft.catalog.Capability
import app.deft.catalog.GrantLevel
import app.deft.catalog.Risk
import app.deft.catalog.isGranted
import app.deft.catalog.isUserDenial
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import app.deft.registry.DeftManifestParser
import app.deft.workspace.BundledApps
import app.deft.workspace.MyAppsStore
import java.util.UUID

data class InstalledWebApp(
    val id: String,
    val name: String,
    val startUrl: String,
    val origin: String,
    val manifestUrl: String,
    val requiredCapabilities: List<Capability>,
    val filesAllAccess: Boolean,
    val filesTreeUri: String?,
    val confirmBiometric: Boolean,
    val workspaceSlug: String?,
    val tier: String = "local",
    val originId: String? = null,
)

/** Outcome of granting a special-access capability from the dashboard sheet. */
sealed interface SpecialGrantResult {
    data object Granted : SpecialGrantResult
    /** Another app holds the exclusive group; it must be removed there first. */
    data class HeldBy(val webAppId: String, val name: String) : SpecialGrantResult
    /** Not a special capability, or the app does not declare it. */
    data object Refused : SpecialGrantResult
}

class AppRepository(private val db: AppDatabase) {
    fun observeApps(): Flow<List<WebAppEntity>> = db.webApps().observeAll()
    fun observeGrants(): Flow<List<GrantEntity>> = db.grants().observeAll()
    fun observeLog(): Flow<List<UsageLogEntity>> = db.usageLog().observeRecent()
    fun observeRecents(): Flow<List<RecentLaunchEntity>> =
        db.recentLaunches().observe(RecentLaunchEntity.KEEP)

    suspend fun allApps(): List<InstalledWebApp> = db.webApps().all().map { it.toModel() }

    suspend fun getApp(id: String): InstalledWebApp? = db.webApps().get(id)?.toModel()

    suspend fun byOrigin(origin: String): InstalledWebApp? {
        db.webApps().byOrigin(origin)?.toModel()?.let { return it }
        val want = canonicalOrigin(origin)
        if (want.isEmpty()) return null
        return allApps().firstOrNull { canonicalOrigin(it.origin) == want }
    }

    suspend fun install(
        name: String,
        startUrl: String,
        origin: String,
        manifestUrl: String,
        capabilities: List<Capability>,
        grants: Map<Capability, GrantLevel>,
        confirmBiometric: Boolean = false,
        catalogHash: String? = null,
        existingId: String? = null,
    ): InstalledWebApp {
        val prior = existingId?.let { db.webApps().get(it) } ?: urlAppByOrigin(origin)
        val id = prior?.id ?: UUID.randomUUID().toString()
        val entity = WebAppEntity(
            id = id,
            name = name,
            startUrl = startUrl,
            origin = origin,
            manifestUrl = manifestUrl,
            requiredCapabilitiesJson = JsonArray(capabilities.map { JsonPrimitive(it.id) }).toString(),
            filesAllAccess = false,
            filesTreeUri = prior?.filesTreeUri,
            confirmBiometric = confirmBiometric,
            workspaceSlug = prior?.workspaceSlug,
            pinned = prior?.pinned ?: false,
            lastOpenedAt = prior?.lastOpenedAt,
            catalogHash = catalogHash ?: prior?.catalogHash,
            tier = prior?.tier ?: "local",
            originId = prior?.originId,
        )
        db.webApps().upsert(entity)
        writeGrants(id, grants)
        revokeCapsNotRequired(id, capabilities)
        syncPeriodic(id)
        syncHandlers()
        return entity.toModel()
    }

    private suspend fun urlAppByOrigin(origin: String): WebAppEntity? {
        val want = canonicalOrigin(origin)
        if (want.isEmpty()) return null
        return db.webApps().all().firstOrNull {
            it.workspaceSlug == null && canonicalOrigin(it.origin) == want
        }
    }

    suspend fun refreshUrlApp(
        id: String,
        name: String,
        startUrl: String,
        origin: String,
        manifestUrl: String,
        capabilities: List<Capability>,
        catalogHash: String?,
    ) {
        val prior = db.webApps().get(id) ?: return
        db.webApps().upsert(
            prior.copy(
                name = name,
                startUrl = startUrl,
                origin = origin,
                manifestUrl = manifestUrl,
                requiredCapabilitiesJson = JsonArray(capabilities.map { JsonPrimitive(it.id) }).toString(),
                catalogHash = catalogHash ?: prior.catalogHash,
            ),
        )
        revokeCapsNotRequired(id, capabilities)
        syncPeriodic(id)
        syncHandlers()
    }

    private suspend fun revokeCapsNotRequired(webAppId: String, required: List<Capability>) {
        val keep = required.map { it.id }.toSet()
        var removed = false
        for (row in db.grants().forApp(webAppId)) {
            if (row.capability in keep) continue
            removed = true
            db.grants().delete(webAppId, row.capability)
            Capability.fromId(row.capability)?.let { afterRevoke(webAppId, it) }
        }
        // Runs on every open of a My App or bundled app; only a change needs the rechecks.
        if (!removed) return
        purgeDisallowedEvents(webAppId)
        recheckForeground(webAppId)
        syncRoles()
    }

    /**
     * Review: what taking [cap] away from [webAppId] stops, once its row says DENIED or is gone.
     * The one path for the dashboard toggle, the consent screen, a manifest drop and a displaced
     * exclusive holder; callers then run [purgeDisallowedEvents] and [recheckForeground].
     */
    private fun afterRevoke(webAppId: String, cap: Capability) {
        if (cap.id.startsWith("bluetooth.")) BridgeService.instanceOrNull()?.releaseBluetooth(webAppId)
        if (cap.id.startsWith("net.")) BridgeService.instanceOrNull()?.releaseNetwork(webAppId)
        // The grant toggle is the kill switch; a run in progress ends now.
        if (cap == Capability.BACKGROUND) BridgeService.instanceOrNull()?.cancelBackground(webAppId)
        // Revoking `push` unregisters the app's push endpoint.
        if (cap == Capability.PUSH) {
            runCatching { app.deft.push.PushRegistry.unregister(DeftApp.instance, webAppId) }
        }
        if (cap.risk == Risk.SPECIAL) {
            runCatching { app.deft.special.SpecialUse.stop(DeftApp.instance, webAppId, cap) }
            runCatching { app.deft.special.SpecialReviewRunner.forget(DeftApp.instance, webAppId, cap) }
        }
    }

    /**
     * A running `fgs.start` ends when `foreground` or the grant its type needs is gone.
     * Background location watches and geofences end when `location` or
     * `location.background` is gone. Accounts go with `accounts.own`.
     * Review: the live listeners in [app.deft.bridge.RevokeStops] and the `nfc.hce` AIDs too.
     */
    private suspend fun recheckForeground(webAppId: String) {
        runCatching {
            app.deft.background.AppForegroundService.recheckGrants(DeftApp.instance, this, webAppId)
        }
        runCatching {
            app.deft.location.BackgroundLocation.recheckGrants(DeftApp.instance, this, webAppId)
        }
        // Without `accounts.own` the app's accounts (and their syncs) are removed.
        runCatching {
            app.deft.account.DeftAccounts.recheckGrants(DeftApp.instance, this, webAppId)
        }
        // Review: clipboard watch, sensors, listening and the torch end with their grant.
        runCatching { app.deft.bridge.RevokeStops.recheck(webAppId) }
        // With no `nfc.hce` holder, its AIDs go, as they do for payment AIDs.
        if (hceHolderId() == null) runCatching { app.deft.nfc.DeftHostApduService.clearAids(DeftApp.instance) }
        // Review: the same for payment AIDs now, not only in the later RoleComponents sync.
        if (paymentHolderId() == null) runCatching { app.deft.nfc.DeftPaymentApduService.clearAids(DeftApp.instance) }
    }

    /** Catalog Update: missing grant or previous DENIED still needs a consent sheet. */
    suspend fun capabilitiesNeedingConsent(webAppId: String, manifest: List<Capability>): List<Capability> {
        val grants = db.grants().forApp(webAppId).associate { it.capability to it.level }
        return manifest.filter { cap ->
            val level = grants[cap.id]
            // Special caps stay DENIED until the dashboard sheet; only a missing row asks.
            if (cap.risk == Risk.SPECIAL) return@filter level == null
            level == null || level == GrantLevel.DENIED.name
        }
    }

    /**
     * What a widget of [app] still needs the user to settle before it can run (a widget cannot ask): capability ids
     * with no grant row yet, unconsented network origins and missing libraries, read from the served manifest
     * (not the row, which may predate a manifest edit). Empty means ready. Grants nothing.
     */
    suspend fun widgetSetupNeeds(row: InstalledWebApp): List<String> {
        val manifest = servedManifest(row.id, row.workspaceSlug) ?: return emptyList()
        // SPECIAL caps cannot be granted by the launch consent flow (dashboard sheet only), so they never count as setup.
        val caps = ungrantedCapabilities(row.id, manifest.capabilities.map { it.first }).filter { it.risk != Risk.SPECIAL }.map { it.id }
        val origins = runCatching { unconsentedNetwork(row.id, row.workspaceSlug) }.getOrDefault(emptyList())
        val deps = runCatching {
            app.deft.workspace.AppDeps.missing(DeftApp.instance, row.id, row.workspaceSlug).map { it.url }
        }.getOrDefault(emptyList())
        return caps + origins + deps
    }

    /** Ids of the SPECIAL capabilities the served manifest of [row] declares: a widget cannot use them (dashboard sheet only). */
    suspend fun widgetSpecialCaps(row: InstalledWebApp): List<String> =
        servedManifest(row.id, row.workspaceSlug)?.capabilities?.map { it.first }?.filter { it.risk == Risk.SPECIAL }?.map { it.id }.orEmpty()

    /** Upsert My Apps metadata only. Does not create or change grants. */
    suspend fun ensureWorkspaceApp(slug: String): InstalledWebApp {
        val store = DeftApp.instance.myApps
        val parsed = store.readManifest(slug)
        val id = MyAppsStore.webAppId(slug)
        val existing = db.webApps().get(id) ?: db.webApps().bySlug(slug)
        // The origin id is fixed once per row and is not the slug. Existing rows keep
        // theirs (changing it would drop the app's storage); new rows get a random id, or the
        // spec hash when the folder holds a signed package.
        val originId = existing?.originId ?: store.pinnedKey(slug)
            ?.let { key ->
                val manifestId = runCatching {
                    DeftManifestParser.parse(java.io.File(store.projectDir(slug), "manifest.json").readText()).id
                }.getOrDefault(slug)
                OriginIds.packaged(key, manifestId)
            }
            ?: OriginIds.local()
        val origin = existing?.origin?.takeIf { existing.originId != null } ?: MyAppsStore.originFor(originId)
        val entity = WebAppEntity(
            id = existing?.id ?: id,
            name = parsed.name,
            startUrl = "$origin/",
            origin = origin,
            manifestUrl = "$origin/manifest.json",
            requiredCapabilitiesJson = JsonArray(parsed.capabilities.map { JsonPrimitive(it.id) }).toString(),
            filesAllAccess = false,
            filesTreeUri = existing?.filesTreeUri,
            confirmBiometric = existing?.confirmBiometric ?: false,
            workspaceSlug = slug,
            pinned = existing?.pinned ?: false,
            lastOpenedAt = existing?.lastOpenedAt,
            catalogHash = existing?.catalogHash,
            tier = existing?.tier ?: "local",
            originId = originId,
        )
        db.webApps().upsert(entity)
        // Review: a capability dropped from the manifest loses its grant (and what it started).
        revokeCapsNotRequired(entity.id, parsed.capabilities)
        // The manifest may have gained, changed or lost `periodic:N`.
        syncPeriodic(entity.id)
        // The manifest may have gained, changed or lost `handles`.
        syncHandlers()
        return entity.toModel()
    }

    suspend fun ensureBundledApp(id: String): InstalledWebApp {
        val ctx = DeftApp.instance
        val dir = BundledApps.dir(ctx, id)
        val file = java.io.File(dir, "manifest.json")
        if (!file.isFile) throw IllegalArgumentException("missing bundled manifest for $id")
        val raw = file.readText()
        val parsed = runCatching { DeftManifestParser.parse(raw) }.getOrElse {
            throw IllegalArgumentException("bundled manifest for $id must be a Deft manifest")
        }
        val name = parsed.name
        val capabilities = parsed.capabilities.map { it.first }
        val webId = BundledApps.webAppId(id)
        val origin = BundledApps.origin(id)
        val existing = db.webApps().get(webId)
        val entity = WebAppEntity(
            id = webId,
            name = name,
            startUrl = "$origin/",
            origin = origin,
            manifestUrl = "$origin/manifest.json",
            requiredCapabilitiesJson = JsonArray(capabilities.map { JsonPrimitive(it.id) }).toString(),
            filesAllAccess = false,
            filesTreeUri = existing?.filesTreeUri,
            confirmBiometric = existing?.confirmBiometric ?: false,
            workspaceSlug = null,
            pinned = existing?.pinned ?: false,
            lastOpenedAt = existing?.lastOpenedAt,
            catalogHash = existing?.catalogHash,
            tier = BundledApps.TIER_BUNDLED,
            originId = existing?.originId ?: BundledApps.originId(id),
        )
        db.webApps().upsert(entity)
        // Review: a capability dropped from the manifest loses its grant (and what it started).
        revokeCapsNotRequired(entity.id, capabilities)
        syncHandlers()
        return entity.toModel()
    }

    suspend fun applyBundledConsent(
        bundledId: String,
        shown: List<Capability>,
        selected: List<Capability>,
    ): InstalledWebApp {
        val installed = ensureBundledApp(bundledId)
        writeConsentGrants(installed.id, shown, selected)
        return getApp(installed.id) ?: installed
    }

    suspend fun migrateLegacyIds() {
        for (app in db.webApps().all()) {
            val ids = runCatching {
                capsJson.parseToJsonElement(app.requiredCapabilitiesJson).jsonArray.map { it.jsonPrimitive.content }
            }.getOrDefault(emptyList())
            val expanded = ids.flatMap { Capability.migrateStoredId(it) }.distinct()
            val originId: String = app.originId ?: when {
                app.tier == "bundled" -> {
                    val bundledId = BundledApps.idFromWebAppId(app.id)
                    bundledId?.let { BundledApps.originId(it) } ?: app.id
                }
                app.workspaceSlug != null -> app.workspaceSlug!!
                else -> app.id.takeLast(12)
            }
            if (expanded.map { it.id } != ids || app.originId != originId) {
                db.webApps().upsert(
                    app.copy(
                        requiredCapabilitiesJson = JsonArray(expanded.map { JsonPrimitive(it.id) }).toString(),
                        originId = originId,
                        filesAllAccess = false,
                    ),
                )
            } else if (app.filesAllAccess) {
                db.webApps().upsert(app.copy(filesAllAccess = false))
            }
            for (row in db.grants().forApp(app.id)) {
                val next = Capability.migrateStoredId(row.capability)
                if (next.size == 1 && next[0].id == row.capability) continue
                db.grants().delete(app.id, row.capability)
                for (cap in next) {
                    db.grants().upsert(GrantEntity(app.id, cap.id, row.level, deniedByUser = row.deniedByUser))
                }
            }
        }
    }

    fun reasonFor(app: InstalledWebApp, cap: Capability): String = reasonFor(app.id, app.workspaceSlug, cap)

    /** The manifest `reason` for [cap], or its catalog description when there is none. */
    fun reasonFor(webAppId: String, workspaceSlug: String?, cap: Capability): String {
        val dir = when {
            workspaceSlug != null -> DeftApp.instance.myApps.projectDir(workspaceSlug)
            else -> {
                val id = BundledApps.idFromWebAppId(webAppId) ?: return cap.description
                BundledApps.dir(DeftApp.instance, id)
            }
        }
        val file = java.io.File(dir, "manifest.json")
        if (!file.isFile) return cap.description
        val parsed = runCatching { DeftManifestParser.parse(file.readText()) }.getOrNull()
            ?: return cap.description
        return parsed.capabilities.firstOrNull { it.first == cap }?.second ?: cap.description
    }

    private fun manifestNetwork(webAppId: String, workspaceSlug: String?): List<String> =
        servedManifest(webAppId, workspaceSlug)?.network ?: emptyList()

    /**
     * [ConsentBinding.hash] of the manifest the host serves for this app (overlay wins),
     * or null when there is none on disk. The consent screen records it and checks it on Allow.
     */
    fun servedManifestHash(webAppId: String, workspaceSlug: String?): String? {
        val dir = when {
            workspaceSlug != null -> DeftApp.instance.myApps.projectDir(workspaceSlug)
            else -> {
                val id = BundledApps.idFromWebAppId(webAppId) ?: return null
                BundledApps.dir(DeftApp.instance, id)
            }
        }
        val file = app.deft.workspace.MergedAppTree.of(DeftApp.instance, dir, webAppId).manifestFile()
        if (!file.isFile) return null
        return runCatching { ConsentBinding.hash(file.readBytes()) }.getOrNull()
    }

    /** The merged manifest (overlay wins), the same one the host serves; null when unreadable. */
    private fun servedManifest(webAppId: String, workspaceSlug: String?): app.deft.registry.DeftManifest? {
        val dir = when {
            workspaceSlug != null -> DeftApp.instance.myApps.projectDir(workspaceSlug)
            else -> {
                val id = BundledApps.idFromWebAppId(webAppId) ?: return null
                BundledApps.dir(DeftApp.instance, id)
            }
        }
        val file = app.deft.workspace.MergedAppTree.of(DeftApp.instance, dir, webAppId).manifestFile()
        if (!file.isFile) return null
        return runCatching { DeftManifestParser.parse(file.readText()) }.getOrNull()
    }

    /** The manifest `background` the host serves, or null (URL apps have none). */
    fun backgroundFor(app: InstalledWebApp): app.deft.registry.DeftBackground? =
        servedManifest(app.id, app.workspaceSlug)?.background

    /** The manifest `wallpaper` the host serves, or null (URL apps have none). */
    fun wallpaperFor(app: InstalledWebApp): app.deft.registry.DeftWallpaper? =
        servedManifest(app.id, app.workspaceSlug)?.wallpaper

    /** The manifest `keyboard` the host serves, or null (URL apps have none). */
    fun keyboardFor(app: InstalledWebApp): app.deft.registry.DeftKeyboard? =
        servedManifest(app.id, app.workspaceSlug)?.keyboard

    /** The manifest `widget` the host serves, or null (URL apps have none). */
    fun widgetFor(app: InstalledWebApp): app.deft.registry.DeftWidget? =
        servedManifest(app.id, app.workspaceSlug)?.widget

    /** The manifest `tools` the host serves (all declared, consented or not), or empty (URL apps have none). */
    fun toolsFor(app: InstalledWebApp): List<app.deft.registry.DeftTool> =
        servedManifest(app.id, app.workspaceSlug)?.tools.orEmpty()

    /** The manifest `handles` the host serves, or null (URL apps have none). */
    fun handlesFor(app: InstalledWebApp): app.deft.registry.DeftHandles? =
        servedManifest(app.id, app.workspaceSlug)?.handles

    /** Re-evaluates which role components are enabled, off the caller's thread. */
    fun syncRoles() {
        runCatching { app.deft.roles.RoleComponents.syncSoon(DeftApp.instance) }
    }

    /**
     * Manifest `passkeys.rpIds`, but only when the manifest also declares `webauthn`. Read from the served
     * manifest on every call, like [handlesFor]; the website's own opt-in list is what binds an rpId to the app.
     */
    fun passkeyRpIdsFor(app: InstalledWebApp): List<String> = passkeyRpIdsFor(app.id, app.workspaceSlug)

    fun passkeyRpIdsFor(webAppId: String, workspaceSlug: String?): List<String> {
        val parsed = servedManifest(webAppId, workspaceSlug) ?: return emptyList()
        if (parsed.capabilities.none { it.first == Capability.WEBAUTHN }) return emptyList()
        return parsed.passkeyRpIds
    }

    /** Re-evaluates which "Open with" aliases are enabled, off the caller's thread. */
    fun syncHandlers() {
        runCatching { app.deft.ui.handlers.HandlerAliases.syncSoon(DeftApp.instance) }
    }

    /** The app's last background runs, newest first, for the dashboard. */
    fun observeBackgroundRuns(webAppId: String, limit: Int = 10): Flow<List<UsageLogEntity>> =
        db.usageLog().observeForMethod(webAppId, app.deft.background.BackgroundRunner.AUDIT_METHOD, limit)

    /** When the app's runs since [since] ended, to seed the hourly limit after a restart. */
    suspend fun backgroundRunTimes(webAppId: String, since: Long): List<Long> =
        db.usageLog().timestampsSince(
            webAppId,
            app.deft.background.BackgroundRunner.AUDIT_METHOD,
            since,
            app.deft.background.BackgroundRunner.RAN_OUTCOMES,
        )

    /**
     * Manifest `embeds`, but only when the manifest also declares `web.embed`;
     * without it there is nothing to ask about or serve.
     */
    private fun manifestEmbeds(webAppId: String, workspaceSlug: String?): List<String> {
        val parsed = servedManifest(webAppId, workspaceSlug) ?: return emptyList()
        if (parsed.capabilities.none { it.first == Capability.WEB_EMBED }) return emptyList()
        return parsed.embeds
    }

    private suspend fun resolveEmbeds(webAppId: String, workspaceSlug: String?): EmbedConsent.Resolution {
        val dao = db.networkConsent()
        val resolution = EmbedConsent.resolve(
            manifestEmbeds(webAppId, workspaceSlug),
            dao.bySource(webAppId, EmbedConsent.SOURCE_APPROVED),
            dao.bySource(webAppId, EmbedConsent.SOURCE_REFUSED),
        )
        resolution.rewrite?.let { storeEmbeds(webAppId, it) }
        return resolution
    }

    private suspend fun storeEmbeds(webAppId: String, sets: Pair<List<String>, List<String>>) {
        db.withTransaction {
            val dao = db.networkConsent()
            dao.deleteSource(webAppId, EmbedConsent.SOURCE_APPROVED)
            dao.deleteSource(webAppId, EmbedConsent.SOURCE_REFUSED)
            dao.insertAll(
                sets.first.map { NetworkConsentEntity(webAppId, it, EmbedConsent.SOURCE_APPROVED) } +
                    sets.second.map { NetworkConsentEntity(webAppId, it, EmbedConsent.SOURCE_REFUSED) },
            )
        }
    }

    /** Declared embed origins nobody decided on yet; non-empty means the consent screen must run. */
    suspend fun unconsentedEmbeds(webAppId: String, workspaceSlug: String?): List<String> =
        resolveEmbeds(webAppId, workspaceSlug).pending

    /** Called when the user taps Allow on a consent screen that listed [shown] with [ticked] left ticked. */
    suspend fun recordEmbedConsent(webAppId: String, workspaceSlug: String?, shown: List<String>, ticked: List<String>) {
        val dao = db.networkConsent()
        storeEmbeds(
            webAppId,
            EmbedConsent.afterConsent(
                manifestEmbeds(webAppId, workspaceSlug),
                dao.bySource(webAppId, EmbedConsent.SOURCE_APPROVED),
                dao.bySource(webAppId, EmbedConsent.SOURCE_REFUSED),
                shown,
                ticked,
            ),
        )
    }

    /** Every declared embed origin with whether the user approved it (dashboard). */
    suspend fun embedChoices(webAppId: String, workspaceSlug: String?): List<Pair<String, Boolean>> {
        val declared = manifestEmbeds(webAppId, workspaceSlug)
        val approved = resolveEmbeds(webAppId, workspaceSlug).approved.toSet()
        return declared.distinct().map { it to (it in approved) }
    }

    /** The dashboard switch for one declared embed origin. */
    suspend fun setEmbedApproved(webAppId: String, workspaceSlug: String?, origin: String, allow: Boolean) {
        val dao = db.networkConsent()
        storeEmbeds(
            webAppId,
            EmbedConsent.toggle(
                manifestEmbeds(webAppId, workspaceSlug),
                dao.bySource(webAppId, EmbedConsent.SOURCE_APPROVED),
                dao.bySource(webAppId, EmbedConsent.SOURCE_REFUSED),
                origin,
                allow,
            ),
        )
    }

    /**
     * The tools the user consented to are rows `name@fingerprint` in `network_consent` under
     * [AgentToolConsent.SOURCE_TOOL] (no schema change), so an edited tool is asked again. One row under
     * [AgentToolConsent.SOURCE_OFF] means the dashboard switch is off: the agent sees none of the app's tools.
     */
    private fun declaredTools(webAppId: String, workspaceSlug: String?): List<app.deft.registry.DeftTool> {
        val manifest = servedManifest(webAppId, workspaceSlug)
        val tools = manifest?.tools.orEmpty()
        if (tools.isEmpty()) return tools
        // The consent key covers the handler code too: a script the agent rewrites after consent is asked again.
        val dir = when {
            workspaceSlug != null -> DeftApp.instance.myApps.projectDir(workspaceSlug)
            else -> BundledApps.idFromWebAppId(webAppId)?.let { BundledApps.dir(DeftApp.instance, it) } ?: return emptyList()
        }
        val files = app.deft.workspace.MergedAppTree.of(DeftApp.instance, dir, webAppId).files()
        return tools.map { it.withCode(app.deft.registry.DeftToolCode.hash(it, files, manifest?.dependencies.orEmpty())) }
    }

    /** Declared tools the user has not consented to yet (new or changed); non-empty means the consent screen must run. */
    suspend fun unconsentedTools(webAppId: String, workspaceSlug: String?): List<app.deft.registry.DeftTool> {
        val consented = db.networkConsent().bySource(webAppId, AgentToolConsent.SOURCE_TOOL).toSet()
        return declaredTools(webAppId, workspaceSlug).filter { it.consentKey() !in consented }
    }

    /** Called when the user taps Allow on a consent screen that listed [shown]; consent for tools no longer declared is dropped. */
    suspend fun recordToolConsent(webAppId: String, workspaceSlug: String?, shown: List<app.deft.registry.DeftTool>) {
        val dao = db.networkConsent()
        val before = dao.bySource(webAppId, AgentToolConsent.SOURCE_TOOL).toSet()
        val shownKeys = shown.map { it.consentKey() }.toSet()
        val keep = declaredTools(webAppId, workspaceSlug).map { it.consentKey() }.filter { it in before || it in shownKeys }
        db.withTransaction {
            dao.deleteSource(webAppId, AgentToolConsent.SOURCE_TOOL)
            dao.insertAll(keep.map { NetworkConsentEntity(webAppId, it, AgentToolConsent.SOURCE_TOOL) })
            // Allowing tools on the consent screen also turns the switch back on.
            if (shown.isNotEmpty()) dao.deleteSource(webAppId, AgentToolConsent.SOURCE_OFF)
        }
    }

    /** The dashboard switch "Let the agent use this app's tools". */
    suspend fun toolsEnabled(webAppId: String): Boolean =
        db.networkConsent().bySource(webAppId, AgentToolConsent.SOURCE_OFF).isEmpty()

    suspend fun setToolsEnabled(webAppId: String, enabled: Boolean) {
        val dao = db.networkConsent()
        db.withTransaction {
            dao.deleteSource(webAppId, AgentToolConsent.SOURCE_OFF)
            if (!enabled) dao.insertAll(listOf(NetworkConsentEntity(webAppId, AgentToolConsent.OFF_MARK, AgentToolConsent.SOURCE_OFF)))
        }
    }

    /** Tools declared by the app's served manifest and consented to, as the agent may call them; empty while the switch is off. */
    suspend fun agentToolsFor(app: InstalledWebApp): List<app.deft.registry.DeftTool> {
        if (app.workspaceSlug == null || !toolsEnabled(app.id)) return emptyList()
        val consented = db.networkConsent().bySource(app.id, AgentToolConsent.SOURCE_TOOL).toSet()
        return declaredTools(app.id, app.workspaceSlug).filter { it.consentKey() in consented }
    }

    /** Every declared tool with whether the user consented, for the dashboard. */
    suspend fun toolChoices(webAppId: String, workspaceSlug: String?): List<Pair<app.deft.registry.DeftTool, Boolean>> {
        val consented = db.networkConsent().bySource(webAppId, AgentToolConsent.SOURCE_TOOL).toSet()
        return declaredTools(webAppId, workspaceSlug).map { it to (it.consentKey() in consented) }
    }

    /** Approved embed origins, only while `web.embed` is declared **and** granted. */
    private suspend fun embedsFor(app: InstalledWebApp): List<String> {
        if (!declaredAndGranted(app.id, Capability.WEB_EMBED)) return emptyList()
        return resolveEmbeds(app.id, app.workspaceSlug).approved
    }

    private suspend fun resolveNetwork(webAppId: String, workspaceSlug: String?): NetworkConsent.Resolution {
        val resolution = NetworkConsent.resolve(
            manifestNetwork(webAppId, workspaceSlug),
            db.networkConsent().forApp(webAppId),
        )
        resolution.rewrite?.let { storeNetworkConsent(webAppId, it) }
        return resolution
    }

    private suspend fun storeNetworkConsent(webAppId: String, rows: List<String>) {
        db.withTransaction {
            // Only consent-screen rows; servers the user typed are kept.
            db.networkConsent().deleteManifestRows(webAppId)
            db.networkConsent().insertAll(rows.map { NetworkConsentEntity(webAppId, it, NetworkConsent.SOURCE_MANIFEST) })
        }
    }

    /**
     * `connect-src` origins: declared in the manifest **and** allowed on the consent screen, then servers the user added when `network.user` is declared and granted.
     */
    suspend fun networkFor(app: InstalledWebApp): List<String> {
        val consented = resolveNetwork(app.id, app.workspaceSlug).served
        val userGranted = declaredAndGranted(app.id, Capability.NETWORK_USER)
        val user = if (userGranted) db.networkConsent().userFor(app.id) else emptyList()
        return NetworkConsent.served(consented, user, userGranted)
    }

    /**
     * Everything the app's CSP is built from. `network.open` counts only when the
     * app declares it **and** the user granted it.
     */
    suspend fun cspPolicyFor(app: InstalledWebApp): CspPolicy {
        val open = declaredAndGranted(app.id, Capability.NETWORK_OPEN)
        // 'wasm-unsafe-eval' only for an app that declares and holds the grant.
        val wasm = declaredAndGranted(app.id, Capability.WASM)
        // The dashboard's "Block network" switch drops every remote origin.
        if (networkBlocked(app.id)) {
            return CspPolicy(flags = buildSet {
                add(CspFlag.BLOCKED)
                if (wasm) add(CspFlag.WASM)
            })
        }
        return CspPolicy(
            connectOrigins = networkFor(app),
            flags = buildSet {
                if (open) add(CspFlag.OPEN_NETWORK)
                if (wasm) add(CspFlag.WASM)
            },
            // Approved embed origins only with web.embed declared and granted.
            embedOrigins = embedsFor(app),
        )
    }

    /**
     * Sockets to servers outside the local network need `net.sockets` **and**
     * `network.open`, each declared and granted, the same rule as [cspPolicyFor].
     */
    suspend fun internetSocketsAllowed(webAppId: String): Boolean =
        declaredAndGranted(webAppId, Capability.NET_SOCKETS) && declaredAndGranted(webAppId, Capability.NETWORK_OPEN)

    /** Reads the current row, so a stale [InstalledWebApp] cannot keep a capability alive. */
    private suspend fun declaredAndGranted(webAppId: String, cap: Capability): Boolean {
        val row = getApp(webAppId) ?: return false
        return cap in row.requiredCapabilities && grantLevel(webAppId, cap).isGranted()
    }

    /** The per-app network log. */
    val networkLog: NetworkLogStore by lazy { NetworkLogStore(db.networkLog()) }

    /** The dashboard's "Block network" switch; on means no remote request and no `net.*` call. */
    suspend fun networkBlocked(webAppId: String): Boolean =
        db.networkConsent().bySource(webAppId, NetworkLog.SOURCE_BLOCK).isNotEmpty()

    suspend fun setNetworkBlocked(webAppId: String, blocked: Boolean) {
        val dao = db.networkConsent()
        db.withTransaction {
            dao.deleteSource(webAppId, NetworkLog.SOURCE_BLOCK)
            if (blocked) dao.insertAll(listOf(NetworkConsentEntity(webAppId, NetworkLog.BLOCK_MARK, NetworkLog.SOURCE_BLOCK)))
        }
    }

    /** Servers the user added for this app on the dashboard. */
    suspend fun userServers(webAppId: String): List<String> = db.networkConsent().userFor(webAppId)

    /** Adds a server; returns a plain error for the dashboard, or null when added. */
    suspend fun addUserServer(webAppId: String, input: String): String? {
        val origin = NetworkConsent.userOrigin(input)
            ?: return "Use an address like https://example.com or https://example.com:8443"
        val current = db.networkConsent().userFor(webAppId)
        if (origin in current) return null
        if (current.size >= NetworkConsent.MAX_USER_SERVERS) {
            return "You can add up to ${NetworkConsent.MAX_USER_SERVERS} servers"
        }
        db.networkConsent().insertAll(listOf(NetworkConsentEntity(webAppId, origin, NetworkConsent.SOURCE_USER)))
        return null
    }

    suspend fun removeUserServer(webAppId: String, origin: String) {
        db.networkConsent().deleteUser(webAppId, origin)
    }

    /** Declared origins the user has not allowed yet; non-empty means the consent screen must run. */
    suspend fun unconsentedNetwork(webAppId: String, workspaceSlug: String?): List<String> =
        resolveNetwork(webAppId, workspaceSlug).pending

    /** Called when the user taps Allow on a consent screen that listed [shown]. */
    suspend fun recordNetworkConsent(webAppId: String, workspaceSlug: String?, shown: List<String>) {
        storeNetworkConsent(
            webAppId,
            NetworkConsent.afterConsent(
                manifestNetwork(webAppId, workspaceSlug),
                db.networkConsent().forApp(webAppId),
                shown,
            ),
        )
    }

    suspend fun applyWorkspaceConsent(
        slug: String,
        shown: List<Capability>,
        selected: List<Capability>,
    ): InstalledWebApp {
        val installed = ensureWorkspaceApp(slug)
        writeConsentGrants(installed.id, shown, selected)
        return getApp(installed.id) ?: installed
    }

    private suspend fun writeConsentGrants(
        webAppId: String,
        shown: List<Capability>,
        selected: List<Capability>,
    ) {
        val chosen = selected.toSet()
        val grants = linkedMapOf<Capability, GrantLevel>()
        for (cap in shown) {
            when {
                cap !in chosen -> grants[cap] = GrantLevel.DENIED
                // A ticked box plus Allow is the grant; there is no second prompt.
                else -> grants[cap] = cap.defaultGrant
            }
        }
        writeGrants(webAppId, grants)
        if (shown.any { it.id.startsWith("files.") }) {
            setFilesAllAccess(webAppId, false)
        }
        syncPeriodic(webAppId)
        syncHandlers()
    }

    suspend fun ungrantedCapabilities(webAppId: String, manifest: List<Capability>): List<Capability> {
        val known = db.grants().forApp(webAppId).map { it.capability }.toSet()
        return manifest.filter { it.id !in known }
    }

    /** Granted **and** still declared: a row for a capability the manifest dropped counts for nothing. */
    suspend fun grantedCapabilities(webAppId: String): List<Capability> {
        val declared = getApp(webAppId)?.requiredCapabilities ?: return emptyList()
        return db.grants().forApp(webAppId).mapNotNull { row ->
            if (row.level == GrantLevel.DENIED.name) null else Capability.fromId(row.capability)?.takeIf { it in declared }
        }
    }

    /** Review: the capabilities the app's current manifest declares (fresh row; empty when not installed). */
    suspend fun declaredCapabilities(webAppId: String): List<Capability> =
        getApp(webAppId)?.requiredCapabilities ?: emptyList()

    private suspend fun writeGrants(webAppId: String, grants: Map<Capability, GrantLevel>) {
        for ((cap, level) in grants) {
            // Install and the consent screen never grant special access. A new row is
            // DENIED ("asks later"); an existing row (granted from the dashboard) is left alone.
            if (cap.risk == Risk.SPECIAL) {
                if (db.grants().get(webAppId, cap.id) == null) {
                    db.grants().upsert(GrantEntity(webAppId, cap.id, GrantLevel.DENIED.name))
                }
                continue
            }
            if (level.isGranted()) {
                cap.exclusiveGroup?.let { replaceGroupHolder(it, webAppId) }
            }
            // A consent-screen "no" is not the user's denial: the app may ask at the point of use.
            db.grants().upsert(GrantEntity(webAppId, cap.id, level.name, deniedByUser = false))
            if (level == GrantLevel.DENIED) afterRevoke(webAppId, cap)
        }
        if (grants.values.any { it == GrantLevel.DENIED }) {
            purgeDisallowedEvents(webAppId)
            recheckForeground(webAppId)
        }
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        val app = db.webApps().get(id) ?: return
        db.webApps().upsert(app.copy(pinned = pinned))
    }

    suspend fun touchWorkspaceOpened(id: String) {
        val app = db.webApps().get(id) ?: return
        db.webApps().upsert(app.copy(lastOpenedAt = System.currentTimeMillis()))
    }

    suspend fun bySlug(slug: String): InstalledWebApp? = db.webApps().bySlug(slug)?.toModel()

    suspend fun replaceGroupHolder(group: String, newHolderId: String) {
        val displaced = linkedSetOf<String>()
        for (cap in Capability.entries.filter { it.exclusiveGroup == group }) {
            for (row in db.grants().holdersOf(cap.id)) {
                if (row.webAppId != newHolderId) {
                    db.grants().deny(row.webAppId, cap.id)
                    // Review: the same revoke path as the dashboard toggle (setGrant DENIED).
                    afterRevoke(row.webAppId, cap)
                    displaced += row.webAppId
                }
            }
        }
        // Review: the old holder's queued SMS / call events must not reach it later, and what it
        // started with the group (listeners, services) ends. Runs before the new holder's row is
        // written, so the `nfc.hce` AIDs go too.
        for (id in displaced) {
            purgeDisallowedEvents(id)
            recheckForeground(id)
        }
        // Review: the old holder's AIDs must not reach the new holder.
        if (displaced.isNotEmpty() && group == Capability.NFC_HCE.exclusiveGroup) {
            runCatching { app.deft.nfc.DeftHostApduService.clearAids(DeftApp.instance) }
        }
        if (displaced.isNotEmpty()) syncRoles()
    }

    suspend fun replaceSmsHolder(newHolderId: String) = replaceGroupHolder("sms", newHolderId)

    suspend fun smsHolderId(): String? = holderOfGroup("sms")

    /** The one app granted NFC card emulation. */
    suspend fun hceHolderId(): String? = holderOfGroup("nfc.hce")

    /** The one app granted payment card emulation. */
    private suspend fun paymentHolderId(): String? = Capability.NFC_HCE_PAYMENT.exclusiveGroup?.let { holderOfGroup(it) }

    suspend fun replaceTelephonyHolder(newHolderId: String) = replaceGroupHolder("calls", newHolderId)

    suspend fun telephonyHolderId(): String? = holderOfGroup("calls")

    private suspend fun holderOfGroup(group: String): String? {
        for (cap in Capability.entries.filter { it.exclusiveGroup == group }) {
            db.grants().holdersOf(cap.id).firstOrNull()?.let { return it.webAppId }
        }
        return null
    }

    suspend fun grantsForApp(webAppId: String) = db.grants().forApp(webAppId)

    /** Folder names of installed apps whose `storage.large` grant is on. */
    suspend fun largeStorageSlugs(): Set<String> = allApps()
        .filter { app -> app.workspaceSlug != null && grantLevel(app.id, Capability.STORAGE_LARGE).isGranted() }
        .mapNotNull { it.workspaceSlug }
        .toSet()

    suspend fun grantLevel(webAppId: String, capability: Capability): GrantLevel {
        val row = db.grants().get(webAppId, capability.id) ?: return GrantLevel.DENIED
        return runCatching { GrantLevel.valueOf(row.level) }.getOrDefault(GrantLevel.DENIED)
    }

    /** True when the user turned this special grant off (not "asks later" from the consent screen). */
    suspend fun specialDeniedByUser(webAppId: String, capability: Capability): Boolean =
        db.grants().get(webAppId, capability.id)?.deniedByUser == true

    /**
     * True when the user said no for good (see [isUserDenial]): the dashboard's off switch, the review's removal, or
     * "Don't ask again" at the point of use. "Not now" and an unticked consent box are not, so the app may ask later.
     */
    suspend fun deniedByUser(webAppId: String, capability: Capability): Boolean {
        val row = db.grants().get(webAppId, capability.id)
        return isUserDenial(row?.level, row?.deniedByUser == true)
    }

    /**
     * Point-of-use refusal of plain [capabilities]: "Not now" ([byUser] false) or "Don't ask again" ([byUser] true).
     * Both write DENIED; only the second is the user's denial.
     */
    suspend fun refuseConsent(
        slug: String?,
        bundledId: String?,
        capabilities: List<Capability>,
        byUser: Boolean,
    ) {
        val installed = when {
            slug != null -> ensureWorkspaceApp(slug)
            bundledId != null -> ensureBundledApp(bundledId)
            else -> return
        }
        for (cap in capabilities) setGrant(installed.id, cap, GrantLevel.DENIED, byUser = byUser)
    }

    /**
     * Debug builds only (a no-op in release): forgets every permission decision of [webAppId], so each capability is
     * asked again. Also clears the app's ask cooldowns and tap-ask state. An open host keeps its grants until reopened.
     */
    suspend fun resetGrants(webAppId: String) {
        if (!BuildConfig.DEBUG) return
        for (row in db.grants().forApp(webAppId)) {
            Capability.fromId(row.capability)?.let { afterRevoke(webAppId, it) }
        }
        db.grants().deleteForApp(webAppId)
        app.deft.bridge.SpecialAsk.forgetApp(webAppId)
        app.deft.bridge.OsPermissions.forgetApp(webAppId)
        app.deft.bridge.UserGesture.forgetAsks(webAppId)
        purgeDisallowedEvents(webAppId)
        recheckForeground(webAppId)
        syncPeriodic(webAppId)
        syncHandlers()
        syncRoles()
    }

    /**
     * [byUser] is false only for the 90-day auto-revoke: that removal is not the user's denial, so the app may ask again.
     */
    suspend fun setGrant(webAppId: String, capability: Capability, level: GrantLevel, byUser: Boolean = true) {
        // Special access is granted only through [grantSpecial], from the launcher's own sheet after the screen lock.
        if (capability.risk == Risk.SPECIAL && level.isGranted()) return
        if (level.isGranted()) {
            capability.exclusiveGroup?.let { replaceGroupHolder(it, webAppId) }
        }
        // A turn-off by the user is remembered (see GrantEntity.deniedByUser); a revoke by the launcher is not.
        db.grants().upsert(
            GrantEntity(
                webAppId,
                capability.id,
                level.name,
                deniedByUser = byUser && level == GrantLevel.DENIED,
            ),
        )
        if (level == GrantLevel.DENIED) {
            afterRevoke(webAppId, capability)
            purgeDisallowedEvents(webAppId)
            // Revoking `foreground` (or the type's own grant) stops the service.
            recheckForeground(webAppId)
        }
        if (capability == Capability.BACKGROUND) syncPeriodic(webAppId)
        // Files only reach an app that can read its share inbox.
        if (capability == Capability.SHARE_RECEIVE) syncHandlers()
        // A role's launcher component goes off with its last holder.
        if (capability.risk == Risk.SPECIAL) syncRoles()
    }

    /**
     * Schedules or cancels the app's `periodic:N` work to match its manifest and its
     * `background` grant. Never fails the caller (WorkManager may be missing in tests).
     */
    suspend fun syncPeriodic(webAppId: String) {
        try {
            app.deft.background.PeriodicTriggers.sync(DeftApp.instance, this, webAppId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AppRepository", "periodic sync failed", e)
        }
    }

    /**
     * The one app holding a special exclusive group, or null. Unlike the SMS role,
     * a special group is never taken over: [grantSpecial] refuses while someone holds it.
     */
    suspend fun specialHolder(group: String): String? = holderOfGroup(group)

    /**
     * The only way a special capability becomes granted. Called by
     * `SpecialGrantActivity` after the user confirmed with the screen lock.
     */
    suspend fun grantSpecial(webAppId: String, capability: Capability): SpecialGrantResult {
        if (capability.risk != Risk.SPECIAL) return SpecialGrantResult.Refused
        val result = db.withTransaction {
            val app = db.webApps().get(webAppId) ?: return@withTransaction SpecialGrantResult.Refused
            if (capability !in app.declaredCapabilities()) return@withTransaction SpecialGrantResult.Refused
            val group = capability.exclusiveGroup
            val holder = group?.let { holderOfGroup(it) }
            if (holder != null && holder != webAppId) {
                val name = db.webApps().get(holder)?.name ?: "Another app"
                return@withTransaction SpecialGrantResult.HeldBy(holder, name)
            }
            db.grants().upsert(
                GrantEntity(webAppId, capability.id, capability.defaultGrant.name, System.currentTimeMillis()),
            )
            SpecialGrantResult.Granted
        }
        return result
    }

    /** Granted special rows, for the daily review. */
    suspend fun grantedSpecial(): List<GrantEntity> =
        db.grants().allGranted().filter { Capability.fromId(it.capability)?.risk == Risk.SPECIAL }

    /** Last successful bridge or web use of [capability] by the app, or null. */
    suspend fun lastUsed(webAppId: String, capability: String): Long? = db.usageLog().lastOk(webAppId, capability)

    /** "Keep" on the review reminder. */
    suspend fun keepGrant(webAppId: String, capability: Capability) {
        db.grants().touch(webAppId, capability.id, System.currentTimeMillis())
    }

    /** One-time cleanup of grant rows written by the removed first-use prompt. */
    suspend fun migrateFirstUseGrants() {
        db.grants().askToGranted()
        db.grants().deleteOnce()
    }

    suspend fun log(
        webAppId: String?,
        source: String,
        capability: String,
        method: String?,
        outcome: String,
        metadata: String? = null,
    ) {
        db.usageLog().insert(
            UsageLogEntity(
                timestamp = System.currentTimeMillis(),
                webAppId = webAppId,
                source = source,
                capability = capability,
                method = method,
                outcome = outcome,
                metadata = metadata,
            ),
        )
    }

    suspend fun enqueueEvent(webAppId: String, event: String, payloadJson: String) {
        db.pendingEvents().insert(
            PendingEventEntity(
                webAppId = webAppId,
                event = event,
                payloadJson = payloadJson,
                timestamp = System.currentTimeMillis(),
            ),
        )
        db.pendingEvents().trim(webAppId, 500)
    }

    /**
     * Review: drops the app's queued events it may no longer receive (see
     * [app.deft.catalog.BridgeCatalog.eventAllowed]). Called after every revoke.
     */
    private suspend fun purgeDisallowedEvents(webAppId: String) {
        val allowed = grantedCapabilities(webAppId).toSet()
        val drop = db.pendingEvents().forApp(webAppId)
            .filterNot { app.deft.catalog.BridgeCatalog.eventAllowed(it.event, allowed) }
            .map { it.id }
        if (drop.isNotEmpty()) db.pendingEvents().deleteIds(drop)
    }

    suspend fun takePendingEvents(webAppId: String): List<PendingEventEntity> {
        val rows = db.pendingEvents().forApp(webAppId)
        for (row in rows) db.pendingEvents().delete(row.id)
        return rows
    }

    suspend fun uninstall(id: String) {
        val ctx = DeftApp.instance
        val row = db.webApps().get(id)
        for (alarm in db.alarms().forApp(id)) {
            app.deft.alarms.AlarmScheduler.cancel(ctx, alarm.id)
        }
        if (row != null) {
            releaseFilesTree(row.filesTreeUri)
            GeolocationPermissions.getInstance().clear(row.origin)
        }
        BridgeService.instanceOrNull()?.releaseBluetooth(id)
        BridgeService.instanceOrNull()?.releaseNetwork(id)
        // Before the profile is deleted; a running background page still uses it.
        BridgeService.instanceOrNull()?.cancelBackground(id)
        app.deft.background.PeriodicTriggers.cancel(ctx, id)
        // The distributor stops accepting messages for the app.
        runCatching { app.deft.push.PushRegistry.unregister(ctx, id) }
        runCatching { app.deft.special.SpecialUse.stopAll(ctx, id) }
        runCatching { app.deft.background.AppForegroundService.stop(ctx, id, null) }
        // Background location watches and geofences (proximity alerts) go.
        runCatching { app.deft.location.BackgroundLocation.forgetApp(ctx, id) }
        // The app's accounts in system Settings go, with their syncs.
        runCatching { app.deft.account.DeftAccounts.forgetOwner(ctx, row?.originId ?: id) }
        // Review: the app's Keystore keys go with it, unless another installed row shares its origin id.
        val owner = row?.originId ?: id
        if (db.webApps().all().none { it.id != id && (it.originId ?: it.id) == owner }) {
            runCatching { app.deft.bridge.modules.KeystoreModule.forgetOwner(owner) }
        }
        runCatching { app.deft.special.SpecialReviewRunner.forgetApp(ctx, id) }
        if (row != null) {
            app.deft.webview.DeftWebViewConfig.deleteProfile(
                app.deft.webview.DeftWebViewConfig.profileName(row.originId, row.origin),
            )
        }
        db.pendingEvents().deleteForApp(id)
        db.recentLaunches().deleteWeb(id)
        db.alarms().deleteForApp(id)
        db.grants().deleteForApp(id)
        db.networkLog().deleteForApp(id)
        // The app's downloaded asset packs go with it.
        runCatching { app.deft.workspace.AssetPacks.root(ctx, id).deleteRecursively() }
        // Review: live listeners end with the app; with no `nfc.hce` holder left its AIDs go too.
        runCatching { app.deft.bridge.RevokeStops.recheck(id) }
        if (hceHolderId() == null) runCatching { app.deft.nfc.DeftHostApduService.clearAids(ctx) }
        if (paymentHolderId() == null) runCatching { app.deft.nfc.DeftPaymentApduService.clearAids(ctx) }
        db.networkConsent().deleteForApp(id)
        runCatching { app.deft.bridge.OriginNotifications.removeAll(ctx, id) }
        runCatching { app.deft.tiles.TileSlots.release(ctx, id) }
        runCatching { app.deft.widgets.WidgetStore.get(ctx).remove(id) }
        runCatching { app.deft.share.ShareInbox.forFilesDir(ctx.filesDir).sweep(System.currentTimeMillis(), id) }
        runCatching { app.deft.ui.handlers.HandlerDefaults(ctx).removeApp(id) }
        db.webApps().delete(id)
        syncRoles()
        syncHandlers()
    }

    suspend fun clearFilesTreeUri(id: String) {
        val app = db.webApps().get(id) ?: return
        releaseFilesTree(app.filesTreeUri)
        db.webApps().upsert(app.copy(filesTreeUri = null))
    }

    private fun releaseFilesTree(uriString: String?) {
        val uri = uriString?.let { Uri.parse(it) } ?: return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            DeftApp.instance.contentResolver.releasePersistableUriPermission(uri, flags)
        }
    }

    suspend fun recordWebLaunch(app: WebAppEntity) {
        upsertRecent(
            RecentLaunchEntity(
                key = RecentLaunchEntity.webKey(app.id),
                kind = RecentLaunchEntity.KIND_WEB,
                webAppId = app.id,
                packageName = null,
                activityName = null,
                label = app.name,
                launchedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun recordNativeLaunch(packageName: String, activityName: String, label: String) {
        upsertRecent(
            RecentLaunchEntity(
                key = RecentLaunchEntity.nativeKey(packageName, activityName),
                kind = RecentLaunchEntity.KIND_NATIVE,
                webAppId = null,
                packageName = packageName,
                activityName = activityName,
                label = label,
                launchedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun upsertRecent(row: RecentLaunchEntity) {
        db.recentLaunches().upsert(row)
        db.recentLaunches().trim(RecentLaunchEntity.KEEP)
    }

    suspend fun setFilesAllAccess(id: String, all: Boolean) {
        val app = db.webApps().get(id) ?: return
        db.webApps().upsert(app.copy(filesAllAccess = all))
    }

    suspend fun setFilesTreeUri(id: String, uri: String?) {
        val app = db.webApps().get(id) ?: return
        db.webApps().upsert(app.copy(filesTreeUri = uri))
    }

    suspend fun insertAlarm(row: AlarmEntity) = db.alarms().upsert(row)

    suspend fun getAlarm(id: String): AlarmEntity? = db.alarms().get(id)

    suspend fun deleteAlarm(id: String) = db.alarms().delete(id)

    suspend fun alarmsForApp(webAppId: String): List<AlarmEntity> = db.alarms().forApp(webAppId)

    suspend fun pendingAlarms(): List<AlarmEntity> = db.alarms().all()

    private fun WebAppEntity.toModel(): InstalledWebApp {
        val caps = declaredCapabilities()
        return InstalledWebApp(
            id = id,
            name = name,
            startUrl = startUrl,
            origin = origin,
            manifestUrl = manifestUrl,
            requiredCapabilities = caps,
            filesAllAccess = filesAllAccess,
            filesTreeUri = filesTreeUri,
            confirmBiometric = confirmBiometric,
            workspaceSlug = workspaceSlug,
            tier = tier,
            originId = originId,
        )
    }
}

fun WebAppEntity.declaredCapabilities(): List<Capability> {
    val ids = runCatching {
        capsJson.parseToJsonElement(requiredCapabilitiesJson)
            .jsonArray
            .map { it.jsonPrimitive.content }
    }.getOrDefault(emptyList())
    return ids.mapNotNull { Capability.fromId(it) }
}

private val capsJson = Json { ignoreUnknownKeys = true }

fun originOf(url: String): String {
    val uri = Uri.parse(url)
    val scheme = uri.scheme ?: return ""
    val authority = uri.authority ?: return ""
    return "$scheme://$authority"
}

/** Scheme + host + non-default port, lowercased. Use for lock comparisons. */
fun canonicalOrigin(url: String): String {
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase() ?: return originOf(url).lowercase()
    val host = uri.host?.lowercase() ?: return originOf(url).lowercase()
    val port = uri.port
    val drop = port <= 0 || (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
    return if (drop) "$scheme://$host" else "$scheme://$host:$port"
}

fun originMatches(promptOrigin: String, installedOrigin: String): Boolean {
    return canonicalOrigin(promptOrigin) == canonicalOrigin(installedOrigin)
}
