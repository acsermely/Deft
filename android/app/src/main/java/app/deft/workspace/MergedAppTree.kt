package app.deft.workspace

import java.io.File

/**
 * The files an app actually consists of: an overlay file wins over the base file,
 * and a path the overlay marks deleted is absent. This is a read model, not a third copy on
 * disk. The host, the agent preview, the verifier, lint and the network allow-list all read
 * through it, so they cannot disagree about what the app is.
 */
class MergedAppTree(val base: File, val overlay: File?) {
    private val baseRoot = base.canonicalFile
    // Checked on every read: the first overlay write can happen while the app is open.
    private val overlayRoot: File? get() = overlay?.takeIf { it.isDirectory }?.canonicalFile

    /** Overlay-relative paths the overlay deleted from the base. */
    fun deleted(): Set<String> =
        overlay?.takeIf { OverlayStore.metaFile(it).isFile }?.let { OverlayStore.deleted(it) }.orEmpty()

    /**
     * The file served for [relative], or null. Directories resolve to their `index.html`.
     * Paths that escape either root are null. [deleted] lets a caller pass a cached set.
     */
    fun resolve(relative: String, deleted: Set<String> = deleted()): File? {
        val rel = relative.replace('\\', '/').trimStart('/')
        if (rel in deleted) return null
        val over = overlayRoot
        val fromOverlay = over?.let { inside(it, rel) }?.takeIf { it.isFile }
        var file = fromOverlay ?: inside(baseRoot, rel) ?: return null
        if (file.isDirectory) {
            val index = if (rel.isEmpty()) "index.html" else "${rel.trimEnd('/')}/index.html"
            if (index in deleted) return null
            file = over?.let { inside(it, index) }?.takeIf { it.isFile }
                ?: inside(baseRoot, index) ?: return null
        }
        return file.takeIf { it.isFile }
    }

    /** The manifest the app runs with: the overlay's when it has one. */
    fun manifestFile(): File =
        overlayRoot?.let { File(it, "manifest.json") }?.takeIf { it.isFile } ?: File(baseRoot, "manifest.json")

    /** Every merged file, keyed by its app-relative path. */
    fun files(): Map<String, File> {
        val gone = deleted()
        val out = sortedMapOf<String, File>()
        fun walk(root: File) {
            if (!root.isDirectory) return
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                if (rel !in gone) out[rel] = f
            }
        }
        walk(baseRoot)
        overlayRoot?.let { walk(it) } // overlay last, so it wins
        return out
    }

    private fun inside(root: File, rel: String): File? {
        val file = File(root, rel).canonicalFile
        return file.takeIf { it.toPath().startsWith(root.toPath()) }
    }

    companion object {
        /** The merged tree for a local or packaged app; the overlay is keyed by the web app id. */
        fun of(context: android.content.Context, base: File, webAppId: String?): MergedAppTree =
            MergedAppTree(base, webAppId?.let { OverlayStore.dir(context, it) })
    }
}
