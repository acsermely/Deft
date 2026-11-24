package app.deft.share

/**
 * Which inbound `content://` URIs the launcher may read on another app's behalf (share-in,
 * "Open with", `intents.startForResult` results).
 *
 * The launcher holds contacts, SMS, call log, media and calendar permissions and owns its own
 * FileProvider, so reading any URI an app hands it would be a confused deputy: a permissionless
 * app could make it copy protected data into a web app's inbox. A URI is read only when:
 *
 * - it is `content://` and its provider is not the launcher's own; and
 * - Android recorded a URI grant to the launcher for it (`Context.checkUriPermission` for our
 *   own pid/uid only looks at the grant table, never at our permissions), which the sender can
 *   only create for data it can read itself. The grant flag on the intent proves nothing:
 *   Android skips the sender check when the receiver already holds the provider's permission;
 * - or, with no recorded grant, the provider is a third-party, exported provider with no read
 *   permission (top level or path): anyone may read it, so our permissions add nothing. System
 *   providers are excluded because some (MediaStore) enforce our permissions in code.
 *
 * A provider guarded by a permission the launcher holds (contacts as vCard, say) is therefore
 * refused: Android records no grant in that case, so a real share cannot be told from a forged one.
 */
object UriIntake {
    data class Provider(
        val packageName: String,
        val exported: Boolean,
        /** `readPermission`, or any `<path-permission>` with a read permission. */
        val readProtected: Boolean,
        val system: Boolean,
    )

    fun allowed(scheme: String?, provider: Provider?, ownPackage: String, granted: Boolean): Boolean {
        if (scheme != "content") return false
        if (provider?.packageName == ownPackage) return false
        if (granted) return true
        provider ?: return false
        return provider.exported && !provider.readProtected && !provider.system
    }
}
