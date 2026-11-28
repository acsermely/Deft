package app.deft.workspace

import android.content.Context
import app.deft.DeftApp
import app.deft.registry.DeftDependency
import java.io.File

// DEVICE-UNCHECKED (hash-pinned .wasm dependency): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: samples/wasm-dep downloads add@1.0.0.wasm on the consent screen (after the samples Worker is deployed) and shows add(2, 40) = 42.
// Remove this note once checked.
/**
 * Which manifest decides an app's CDN dependencies, and fetching the missing
 * ones after the user confirmed the consent screen.
 */
object AppDeps {
    /** The overlay manifest wins over the base. Null for apps the host does not serve from disk. */
    fun manifestFor(context: Context, webAppId: String, slug: String?): File? {
        val base = slug?.let { DeftApp.instance.myApps.projectDir(it) }
            ?: BundledApps.idFromWebAppId(webAppId)?.let { BundledApps.dir(context, it) }
            ?: return null
        val overlay = File(OverlayStore.dir(context, webAppId), "manifest.json")
        return if (overlay.isFile) overlay else File(base, "manifest.json")
    }

    /** Hashes may be written only into a local app's own manifest; signed and bundled ones stay as shipped. */
    fun pinAllowed(context: Context, slug: String?, manifest: File): Boolean {
        if (slug == null) return false
        if (File(context.filesDir, "deft/keys/$slug.pub").isFile) return false
        return manifest.parentFile?.canonicalFile == DeftApp.instance.myApps.projectDir(slug)
    }

    fun missing(context: Context, webAppId: String, slug: String?): List<DeftDependency> {
        val manifest = manifestFor(context, webAppId, slug) ?: return emptyList()
        return DepStore.missing(context, DepStore.dependenciesIn(manifest))
    }

    /** Downloads and verifies every missing file, pins new hashes, then sweeps. Throws on any failure. */
    fun downloadMissing(context: Context, webAppId: String, slug: String?) {
        val manifest = manifestFor(context, webAppId, slug) ?: return
        val missing = DepStore.missing(context, DepStore.dependenciesIn(manifest))
        if (missing.isEmpty()) return
        val pin = pinAllowed(context, slug, manifest)
        // WebAssembly is never pinned on first download.
        DepStore.requirePinnedWasm(missing)
        missing.firstOrNull { it.integrity == null && !pin }?.let {
            throw IllegalArgumentException("${it.url} has no hash in manifest.json")
        }
        val fetched = missing.map { DepStore.download(context, it) }
        if (pin) DepStore.pin(manifest, fetched.filter { f -> missing.any { it.url == f.url && it.integrity == null } })
        DepStore.sweep(context)
    }
}
