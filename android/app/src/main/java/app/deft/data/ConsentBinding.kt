package app.deft.data

import java.security.MessageDigest

/**
 * Binds the consent screen's Allow tap to the manifest the user saw. The screen
 * records [hash] of the served manifest (overlay wins, as the host serves it) before it reads
 * anything to show; Allow writes grants only when the manifest still has that hash. Otherwise
 * the screen opens again, so an edit made while it was open (agent, overlay, update) cannot
 * ride along on a tap meant for the older manifest.
 */
object ConsentBinding {
    fun hash(manifestBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(manifestBytes).joinToString("") { "%02x".format(it) }

    /** True only when both are known and equal: a screen that never read the manifest cannot grant. */
    fun stillShown(shownHash: String?, currentHash: String?): Boolean =
        shownHash != null && currentHash != null && shownHash == currentHash
}
