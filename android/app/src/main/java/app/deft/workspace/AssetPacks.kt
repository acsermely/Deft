package app.deft.workspace

import android.content.Context
import app.deft.registry.DeftAssetPack
import app.deft.registry.DeftManifestParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Asset packs. A pack is a zip named in the manifest with its sha256 and
 * size. After a consent line that shows the size, the launcher downloads it, checks size and
 * hash, extracts it into the app's private tree (`filesDir/deft/packs/<app>/<name>/`) and serves
 * it same-origin under `/_deft/packs/<name>/<path>`. The hash sits in `manifest.json`, which a
 * signed package lists in its signed file list, so a signed app's packs are covered by its
 * signature. Packs count against the app's package cap (larger with `storage.large`).
 *
 * Packs hold data, never code: HTML, scripts, styles and SVG are refused, so everything that runs
 * is still in the package and linted.
 */
object AssetPacks {
    /** Written last into an installed pack folder; its text is the zip's sha256. */
    const val MARKER = "deft-pack.sha256"
    const val MAX_PACK_FILES = 20_000
    const val MAX_DEPTH = 12

    /** Data and media only. */
    private val PACK_EXT = setOf(
        "png", "webp", "jpg", "jpeg", "gif", "avif", "bmp", "ico",
        "mp3", "ogg", "opus", "m4a", "aac", "wav", "flac", "mp4", "webm",
        "woff2", "woff", "ttf", "otf",
        "json", "txt", "csv", "xml", "md",
        "glb", "gltf", "bin", "pbf", "ktx2", "basis",
    )
    private val SEGMENT = Regex("^[a-z0-9][a-z0-9._-]*$")
    private val json = Json { ignoreUnknownKeys = true }

    fun root(context: Context, webAppId: String): File =
        File(context.filesDir, "deft/packs/" + dirName(webAppId))

    fun dirName(webAppId: String): String = webAppId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** The packs a served manifest declares; empty when it has none or cannot be parsed. */
    fun packsIn(manifest: File): List<DeftAssetPack> {
        if (!manifest.isFile) return emptyList()
        return runCatching {
            DeftManifestParser.parseAssetPacks(json.parseToJsonElement(manifest.readText()).jsonObject["assetPacks"])
        }.getOrDefault(emptyList())
    }

    fun installed(root: File, pack: DeftAssetPack): Boolean =
        runCatching { File(root, "${pack.name}/$MARKER").readText().trim() == pack.sha256 }.getOrDefault(false)

    fun missing(root: File, packs: List<DeftAssetPack>): List<DeftAssetPack> = packs.filter { !installed(root, it) }

    fun totalBytes(packs: List<DeftAssetPack>): Long = packs.sumOf { it.size }

    /**
     * Packs plus the app's own files must fit in [caps] (the normal package cap, or the
     * `storage.large` one when the app declares and holds it).
     */
    fun requireQuota(packs: List<DeftAssetPack>, appBytes: Long, caps: DeftPaths.Caps) {
        val total = totalBytes(packs) + appBytes
        if (total > caps.packageBytes) {
            throw IllegalArgumentException(
                "asset packs and app files are $total bytes; this app may use ${caps.packageBytes}" +
                    if (caps.packageBytes <= DeftPaths.normalCaps().packageBytes) " (declare storage.large for more)" else "",
            )
        }
    }

    /** A path inside a pack: lowercase segments, no dotfiles, a data or media type. */
    fun packPath(relative: String): String {
        val parts = relative.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > MAX_DEPTH) throw IllegalArgumentException("invalid pack path: $relative")
        for (part in parts) {
            if (part == "." || part == ".." || !SEGMENT.matches(part)) throw IllegalArgumentException("invalid pack path: $relative")
        }
        val ext = parts.last().substringAfterLast('.', "")
        if (ext !in PACK_EXT) throw IllegalArgumentException("pack files must be data or media, not .$ext: $relative")
        return parts.joinToString("/")
    }

    /** Streams [input] into [dest], at most [pack] size bytes, and checks the size and sha256. */
    fun receive(input: InputStream, dest: File, pack: DeftAssetPack) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        dest.parentFile?.mkdirs()
        dest.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > pack.size) throw IllegalArgumentException("asset pack ${pack.name} is larger than its declared ${pack.size} bytes")
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        if (total != pack.size) throw IllegalArgumentException("asset pack ${pack.name} is $total bytes, not the declared ${pack.size}")
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex != pack.sha256) throw IllegalArgumentException("asset pack ${pack.name} does not match its sha256")
    }

    /**
     * Extracts a verified pack zip into [root]/<name>: into a staging folder first, then swapped
     * in, so a bad zip leaves the installed version as it was. Extracted bytes are bounded by
     * [maxExtractedBytes]; each file by [maxFileBytes].
     */
    fun install(root: File, pack: DeftAssetPack, zip: File, maxExtractedBytes: Long, maxFileBytes: Long) {
        root.mkdirs()
        val staging = File(root, ".staging-${pack.name}-${System.nanoTime()}")
        try {
            extract(zip, staging, maxExtractedBytes, maxFileBytes)
            File(staging, MARKER).writeText(pack.sha256)
            val dir = File(root, pack.name)
            val retired = File(root, ".old-${pack.name}-${System.nanoTime()}")
            if (dir.exists() && !dir.renameTo(retired)) throw IllegalStateException("could not move the old pack aside")
            if (!staging.renameTo(dir)) {
                retired.takeIf { it.exists() }?.renameTo(dir)
                throw IllegalStateException("could not move the pack into place")
            }
            retired.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extract(zip: File, dest: File, maxExtractedBytes: Long, maxFileBytes: Long) {
        dest.mkdirs()
        val base = dest.canonicalFile
        var total = 0L
        var files = 0
        val seen = mutableSetOf<String>()
        try {
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val entry = z.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith("/") || name.contains("..")) throw IllegalArgumentException("zip-slip: $name")
                    val rel = packPath(name)
                    if (rel == MARKER || !seen.add(rel)) throw IllegalArgumentException("duplicate or reserved pack entry: $rel")
                    if (++files > MAX_PACK_FILES) throw IllegalArgumentException("asset pack has more than $MAX_PACK_FILES files")
                    val out = File(base, rel).canonicalFile
                    if (!out.path.startsWith(base.path + File.separator)) throw IllegalArgumentException("zip-slip: $name")
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var size = 0L
                        while (true) {
                            val n = z.read(buf)
                            if (n < 0) break
                            size += n
                            total += n
                            if (size > maxFileBytes) throw IllegalArgumentException("$rel is larger than $maxFileBytes bytes")
                            if (total > maxExtractedBytes) throw IllegalArgumentException("asset pack unpacks to more than $maxExtractedBytes bytes")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
        } catch (e: ZipException) {
            throw IllegalArgumentException("asset pack is not a valid zip: ${e.message}", e)
        }
        if (files == 0) throw IllegalArgumentException("asset pack is empty")
    }

    /**
     * The file for `/_deft/packs/<name>/<path>`: only a pack the served manifest declares, only
     * once that exact version (its sha256) is installed. Null otherwise.
     */
    fun serve(root: File, packs: List<DeftAssetPack>, rest: String): File? {
        val name = rest.substringBefore('/')
        val path = rest.substringAfter('/', "")
        val pack = packs.firstOrNull { it.name == name } ?: return null
        if (!installed(root, pack)) return null
        val rel = runCatching { packPath(path) }.getOrNull() ?: return null
        val base = File(root, name).canonicalFile
        val file = File(base, rel).canonicalFile
        if (!file.path.startsWith(base.path + File.separator) || !file.isFile) return null
        return file
    }

    /** Removes installed packs the manifest no longer declares, and leftovers of failed installs. */
    fun sweep(root: File, packs: List<DeftAssetPack>) {
        val keep = packs.map { it.name }.toSet()
        root.listFiles()?.forEach { child -> if (child.name !in keep) child.deleteRecursively() }
    }

    // DEVICE-UNCHECKED (asset pack download): built and unit-tested, but the developer
    // has not checked it on a real device yet. Check: an app with a declared pack shows its size on the consent screen, downloads it on Allow and reads a file from /_deft/packs/<name>/.
    // Remove this note once checked.
    /**
     * Downloads, verifies and installs every pack of [manifest] that is not installed yet, after
     * the user allowed it on the consent screen. Throws on any failure; installed packs stay.
     */
    fun downloadMissing(context: Context, webAppId: String, manifest: File, appBytes: Long, caps: DeftPaths.Caps) {
        val packs = packsIn(manifest)
        val root = root(context, webAppId)
        requireQuota(packs, appBytes, caps)
        for (pack in missing(root, packs)) {
            val tmp = File(context.cacheDir, "packs/${dirName(webAppId)}-${pack.name}-${System.nanoTime()}.zip")
            try {
                httpGet(pack.url) { input -> receive(input, tmp, pack) }
                install(root, pack, tmp, maxExtractedBytes = caps.packageBytes, maxFileBytes = caps.binaryBytes)
            } finally {
                tmp.delete()
            }
        }
        sweep(root, packs)
    }

    /** Packs the app's served manifest declares that are not installed yet (the consent screen lists them). */
    fun pendingFor(context: Context, webAppId: String, slug: String?): List<DeftAssetPack> {
        val manifest = AppDeps.manifestFor(context, webAppId, slug) ?: return emptyList()
        return missing(root(context, webAppId), packsIn(manifest))
    }

    /**
     * [downloadMissing] for an app as the host serves it. [large]: the app declares `storage.large`
     * and holds it (or the user ticked it on the same screen), so the larger caps apply.
     */
    fun downloadFor(context: Context, webAppId: String, slug: String?, large: Boolean) {
        val manifest = AppDeps.manifestFor(context, webAppId, slug) ?: return
        val base = slug?.let { app.deft.DeftApp.instance.myApps.projectDir(it) }
            ?: BundledApps.idFromWebAppId(webAppId)?.let { BundledApps.dir(context, it) }
        val appBytes = listOfNotNull(base, OverlayStore.dir(context, webAppId)).sumOf { dirBytes(it) }
        val caps = if (large) DeftPaths.largeCaps(context.filesDir.usableSpace) else DeftPaths.normalCaps()
        downloadMissing(context, webAppId, manifest, appBytes, caps)
    }

    private fun dirBytes(dir: File): Long =
        if (!dir.isDirectory) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun httpGet(url: String, read: (InputStream) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            val code = conn.responseCode
            if (code in 300..399) {
                throw IllegalArgumentException("$url redirects to ${conn.getHeaderField("Location").orEmpty()}. Declare the final URL instead.")
            }
            if (code != 200) throw IllegalArgumentException("$url returned HTTP $code")
            conn.inputStream.use(read)
        } finally {
            conn.disconnect()
        }
    }
}
