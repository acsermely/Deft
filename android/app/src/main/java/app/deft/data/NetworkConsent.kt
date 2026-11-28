package app.deft.data

import app.deft.registry.DeftManifestParser

/**
 * Network origins are a permission, not a source edit.
 *
 * The app's manifest declares `network` origins; the consent screen records which ones the user
 * saw and allowed. The host serves `connect-src` from the intersection, so an edit that adds an
 * origin (by hand or by the agent) changes nothing until the user allows it.
 *
 * Stored rows per app:
 * - [RECORDED]: consent has been recorded at least once (so an empty set means "none allowed").
 * - [LEGACY]: the app existed before this rule; the first read adopts its current manifest.
 * - anything else: an allowed origin.
 */
object NetworkConsent {
    const val RECORDED = ""
    const val LEGACY = "*legacy*"

    /** Row came from the consent screen (manifest `network`). */
    const val SOURCE_MANIFEST = "manifest"

    /** A server the user typed on the dashboard; served only with `network.user`. */
    const val SOURCE_USER = "user"
    const val MAX_USER_SERVERS = 16

    data class Resolution(
        /** Origins to serve in `connect-src`. */
        val served: List<String>,
        /** Declared origins the user has not allowed yet. */
        val pending: List<String>,
        /** What the stored rows should be, or null when they are already right. */
        val rewrite: List<String>?,
    )

    fun resolve(manifest: List<String>, stored: List<String>): Resolution {
        val declared = manifest.distinct()
        if (LEGACY in stored) {
            // Grandfather apps installed before consent was recorded: adopt what they declare today.
            return Resolution(declared, emptyList(), listOf(RECORDED) + declared)
        }
        val allowed = stored.filter { it != RECORDED }.toSet()
        val served = declared.filter { it in allowed }
        val pending = declared.filter { it !in allowed }
        // Shrinking the manifest revokes: an origin dropped and re-added later must be asked again.
        // Nothing stored yet means consent was never recorded; there is nothing to prune.
        val pruned = listOf(RECORDED) + served
        val rewrite = pruned.takeIf { stored.isNotEmpty() && it.toSet() != stored.toSet() }
        return Resolution(served, pending, rewrite)
    }

    /** Rows to store after the user allowed [shown] on the consent screen. */
    fun afterConsent(manifest: List<String>, stored: List<String>, shown: List<String>): List<String> {
        val current = resolve(manifest, stored).served.toSet()
        val allowed = manifest.distinct().filter { it in current || it in shown }
        return listOf(RECORDED) + allowed
    }

    /**
     * What the user typed as a server, as an origin to store, or null when it is not
     * one. Same rule as manifest origins ([DeftManifestParser.validNetworkOrigin]): https, a dotted
     * public host name, optional port; one trailing slash and letter case are forgiven.
     */
    fun userOrigin(input: String): String? {
        val origin = input.trim().removeSuffix("/").lowercase()
        return origin.takeIf { DeftManifestParser.validNetworkOrigin(it) }
    }

    /** Origins for `connect-src` (and img/media): consented manifest origins, then user servers when granted. */
    fun served(consented: List<String>, userServers: List<String>, userGranted: Boolean): List<String> =
        (consented + if (userGranted) userServers else emptyList()).distinct()
}

/**
 * Which manifest `embeds` origins the user approved, one by one. Stored in the
 * `network_consent` table under [SOURCE_APPROVED] / [SOURCE_REFUSED] (no schema change), so an
 * unticked origin is not asked again on every launch, and a new one is.
 *
 * Origins the manifest no longer lists are dropped from both sets, so one removed and added back
 * later is asked again (same rule as [NetworkConsent.resolve]).
 */
object EmbedConsent {
    const val SOURCE_APPROVED = "embed"
    const val SOURCE_REFUSED = "embed-refused"

    data class Resolution(
        /** Approved and still declared: what the CSP may serve (when `web.embed` is granted). */
        val approved: List<String>,
        /** Declared, never decided: the consent screen must ask. */
        val pending: List<String>,
        /** New stored sets when rows for undeclared origins must go, or null when they are right. */
        val rewrite: Pair<List<String>, List<String>>?,
    )

    fun resolve(declared: List<String>, approved: List<String>, refused: List<String>): Resolution {
        val list = declared.distinct()
        val keepApproved = list.filter { it in approved }
        val keepRefused = list.filter { it in refused && it !in approved }
        val pending = list.filter { it !in approved && it !in refused }
        val changed = keepApproved.toSet() != approved.toSet() || keepRefused.toSet() != refused.toSet()
        return Resolution(keepApproved, pending, (keepApproved to keepRefused).takeIf { changed })
    }

    /**
     * Stored sets after the consent screen showed [shown] and the user left [ticked] ticked.
     * Earlier decisions for origins not shown are kept; only declared origins are stored.
     */
    fun afterConsent(
        declared: List<String>,
        approved: List<String>,
        refused: List<String>,
        shown: List<String>,
        ticked: List<String>,
    ): Pair<List<String>, List<String>> {
        val list = declared.distinct()
        val nextApproved = list.filter { if (it in shown) it in ticked else it in approved }
        val nextRefused = list.filter { if (it in shown) it !in ticked else it in refused && it !in approved }
        return nextApproved to nextRefused
    }

    /** Dashboard switch for one declared origin; anything not declared is ignored. */
    fun toggle(
        declared: List<String>,
        approved: List<String>,
        refused: List<String>,
        origin: String,
        allow: Boolean,
    ): Pair<List<String>, List<String>> =
        afterConsent(declared, approved, refused, shown = listOf(origin), ticked = if (allow) listOf(origin) else emptyList())
}

/** Source names of the agent-tool consent rows in `network_consent` (see [AppRepository.unconsentedTools]). */
object AgentToolConsent {
    const val SOURCE_TOOL = "tool"
    const val SOURCE_OFF = "tools-off"
    const val OFF_MARK = "off"
}
