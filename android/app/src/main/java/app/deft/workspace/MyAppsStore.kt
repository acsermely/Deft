package app.deft.workspace

import android.content.Context
import app.deft.catalog.Capability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class MyAppsStore(private val context: Context) {
    private val currentFile = File(context.filesDir, "config/myapp.current")
    private val _currentSlug = MutableStateFlow(readCurrent())
    val currentSlug: StateFlow<String?> = _currentSlug.asStateFlow()
    private val _previewRevision = MutableStateFlow(0)
    val previewRevision: StateFlow<Int> = _previewRevision.asStateFlow()

    fun root(): File = StorageMigration.localRoot(context)

    fun projectDir(slug: String): File = File(root(), requireSlug(slug)).canonicalFile

    fun hasProject(slug: String): Boolean =
        runCatching { File(projectDir(slug), "manifest.json").isFile }.getOrDefault(false)

    fun listProjects(): List<ProjectInfo> {
        val dir = root()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory && SLUG.matches(it.name) && File(it, "manifest.json").isFile }
            ?.map { folder ->
                val slug = folder.name
                val name = runCatching { readManifest(slug).name }.getOrNull() ?: slug
                ProjectInfo(slug = slug, name = name, dir = folder)
            }
            ?.sortedBy { it.slug }
            .orEmpty()
    }

    fun create(slug: String): ProjectInfo {
        val clean = requireSlug(slug)
        val dir = projectDir(clean)
        if (dir.exists()) throw IllegalArgumentException("app $clean already exists")
        if (!dir.mkdirs()) throw IllegalStateException("could not create ${dir.path}")
        File(dir, "manifest.json").writeText(
            """
            {
              "deft": 1,
              "id": "$clean",
              "name": "$clean",
              "version": "0.1.0",
              "profile": "myapp",
              "start_url": "./index.html",
              "capabilities": [],
              "network": []
            }
            """.trimIndent() + "\n",
        )
        File(dir, "index.html").writeText(
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
              <title>$clean</title>
              <link rel="stylesheet" href="/_deft/v1/base.css" />
              <link rel="stylesheet" href="/_deft/v1/tokens.css" />
              <link rel="stylesheet" href="/_deft/v1/components.css" />
              <link rel="stylesheet" href="/_deft/v1/theme.css" />
              <link rel="stylesheet" href="./app.css" />
              <script type="module" src="./main.js"></script>
            </head>
            <body>
              <!-- app root -->
              <div id="app"></div>
            </body>
            </html>
            """.trimIndent() + "\n",
        )
        File(dir, "app.css").writeText(
            """
            /* page layout — keep clear of notch, camera cutout, and home indicator */
            :root {
              font-family: system-ui, sans-serif;
            }
            html {
              height: 100%;
            }
            body {
              margin: 0;
              min-height: 100dvh;
              box-sizing: border-box;
              padding-top: env(safe-area-inset-top, 0px);
              padding-right: env(safe-area-inset-right, 0px);
              padding-bottom: env(safe-area-inset-bottom, 0px);
              padding-left: env(safe-area-inset-left, 0px);
            }
            #app {
              padding: 1rem;
              box-sizing: border-box;
            }
            """.trimIndent() + "\n",
        )
        File(dir, "main.js").writeText(
            """
            import { html, render } from '/_deft/v1/dom.js';
            const root = document.getElementById('app');
            render(root, html`<main class="deft-stack"><h1>$clean</h1><p>Edit this app from the launcher agent.</p></main>`);
            """.trimIndent() + "\n",
        )
        return ProjectInfo(slug = clean, name = clean, dir = dir)
    }

    fun open(slug: String) {
        val clean = requireSlug(slug)
        val dir = projectDir(clean)
        if (!File(dir, "manifest.json").isFile) throw IllegalArgumentException("unknown app $clean")
        currentFile.parentFile?.mkdirs()
        currentFile.writeText(clean)
        _currentSlug.value = clean
    }

    fun close() {
        if (currentFile.exists()) currentFile.delete()
        _currentSlug.value = null
    }

    /** Public key text pinned when this folder was installed from a signed package, or null. */
    fun pinnedKey(slug: String): String? =
        File(context.filesDir, "deft/keys/${requireSlug(slug)}.pub").takeIf { it.isFile }?.readText()

    /**
     * Deletes every per-app path for [slug] (PARITY H2): project, overlay and its metadata,
     * pinned key, rollback copy and its key. Grants, consent rows and the WebView profile go with
     * [app.deft.data.AppRepository.uninstall].
     */
    fun deleteProject(slug: String) {
        val clean = requireSlug(slug)
        val dir = projectDir(clean)
        if (!dir.exists()) throw IllegalArgumentException("unknown app $clean")
        if (_currentSlug.value == clean) close()
        if (!dir.deleteRecursively()) throw IllegalStateException("could not delete ${dir.path}")
        val overlay = OverlayStore.dir(context, webAppId(clean))
        overlay.deleteRecursively()
        OverlayStore.metaFile(overlay).delete()
        File(context.filesDir, "deft/keys/$clean.pub").delete()
        File(context.filesDir, "deft/keys/$clean.version").delete()
        File(context.filesDir, "deft/rollback/$clean").deleteRecursively()
        File(context.filesDir, "deft/rollback/$clean.pub").delete()
        runCatching { DepStore.sweep(context) }
    }

    fun readManifest(slug: String): ParsedManifest {
        val base = parseManifestFile(File(projectDir(slug), "manifest.json"))
        val overlayFile = File(OverlayStore.dir(context, webAppId(slug)), "manifest.json")
        if (!overlayFile.isFile) return base
        val extra = runCatching { parseManifestFile(overlayFile) }.getOrNull() ?: return base
        return base.copy(capabilities = (base.capabilities + extra.capabilities).distinct())
    }

    fun mutationRoot(slug: String): File = overlayDirIfPackaged(slug) ?: projectDir(slug)

    /** What the app is made of: base plus overlay, minus deletions (PARITY H3). */
    fun tree(slug: String): MergedAppTree = MergedAppTree.of(context, projectDir(slug), webAppId(slug))

    private fun overlayDirIfPackaged(slug: String): File? {
        val clean = requireSlug(slug)
        if (!File(context.filesDir, "deft/keys/$clean.pub").isFile) return null
        return OverlayStore.dir(context, webAppId(clean))
    }

    private fun parseManifestFile(file: File): ParsedManifest {
        if (!file.isFile) throw IllegalArgumentException("missing manifest.json")
        val raw = file.readText()
        val parsed = runCatching { app.deft.registry.DeftManifestParser.parse(raw) }.getOrElse { err ->
            throw IllegalArgumentException(err.message ?: "manifest.json must use catalog capability ids")
        }
        return ParsedManifest(
            name = parsed.name,
            startUrlRel = parsed.startUrl,
            capabilities = parsed.capabilities.map { it.first },
            network = parsed.network,
            widget = parsed.widget,
        )
    }

    fun resolve(relative: String, slug: String): File {
        val root = projectDir(slug)
        val clean = relative.trim().trimStart('/')
        if (clean.isEmpty() || clean == ".") return root
        val rel = DeftPaths.parse(clean)
        val file = File(root, rel.normalized).canonicalFile
        if (!file.path.startsWith(root.path)) throw IllegalArgumentException("path escapes workspace")
        return file
    }

    fun list(relative: String, slug: String): String {
        val dir = resolve(relative.ifBlank { "." }, slug)
        if (!dir.isDirectory) throw IllegalArgumentException("not a directory")
        val base = dir.canonicalFile
        val rows = mutableListOf<String>()
        base.walkTopDown().forEach { file ->
            if (file.canonicalFile == base) return@forEach
            val rel = file.relativeTo(base).path.replace('\\', '/')
            if (rel.isEmpty()) return@forEach
            rows += if (file.isDirectory) {
                "$rel/"
            } else {
                "$rel  ${file.length()}"
            }
        }
        rows.sort()
        if (rows.isEmpty()) return "empty"
        val cap = WorkspaceFiles.LIST_MAX_ENTRIES
        return if (rows.size <= cap) {
            rows.joinToString("\n")
        } else {
            rows.take(cap).joinToString("\n") + "\n… truncated (${rows.size - cap} more)"
        }
    }

    fun read(relative: String, slug: String): String {
        val overlay = overlayDirIfPackaged(slug)
        if (overlay != null) {
            val rel = DeftPaths.parse(relative).normalized
            if (rel in OverlayStore.deleted(overlay)) throw IllegalArgumentException("not a file")
            val over = File(overlay, rel)
            if (over.isFile) {
                if (over.length() > MAX_BYTES) throw IllegalArgumentException("file too large")
                return over.readText()
            }
        }
        val file = resolve(relative, slug)
        if (!file.isFile) throw IllegalArgumentException("not a file")
        if (file.length() > MAX_BYTES) throw IllegalArgumentException("file too large")
        return file.readText()
    }

    fun write(relative: String, content: String, slug: String) {
        val rel = DeftPaths.parse(relative)
        val overlay = overlayDirIfPackaged(slug)
        if (overlay != null) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            DeftPaths.requireContentSize(rel, bytes.size.toLong())
            val prior = OverlayStore.read(overlay).files[rel.normalized]
            val baseHash = if (prior != null) {
                prior.baseSha256
            } else {
                val base = File(projectDir(slug), rel.normalized)
                if (base.isFile) OverlayStore.sha256(base) else null
            }
            OverlayStore.writeFile(overlay, rel.normalized, bytes, baseHash, "")
            bumpPreview()
            manifestWritten(rel.normalized, slug)
            return
        }
        val file = resolve(rel.normalized, slug)
        val adding = !file.isFile
        DeftPaths.requireFileBudget(DeftPaths.countFiles(projectDir(slug)), adding)
        val bytes = content.toByteArray(Charsets.UTF_8)
        DeftPaths.requireContentSize(rel, bytes.size.toLong())
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        bumpPreview()
        manifestWritten(rel.normalized, slug)
    }

    /** PARITY D2/E1: a new manifest may add, change or drop the app's `periodic:N` trigger or `handles`. */
    private fun manifestWritten(normalized: String, slug: String) {
        if (normalized == "manifest.json") {
            app.deft.background.PeriodicTriggers.syncSlugSoon(context, slug)
            // PARITY E1: `handles` may have changed.
            app.deft.ui.handlers.HandlerAliases.syncSoon(context)
        }
    }

    fun exportZip(slug: String, out: OutputStream) {
        AppHistory.export(context, projectDir(slug), out)
    }

    /** A verified package that passed every check that does not need the device's lint engine. */
    class PreparedInstall internal constructor(
        val slug: String,
        val verified: DeftPackage.Verified,
        internal val previousKey: String?,
    ) {
        /**
         * Text files for lint, keyed by path (dependency blobs and binaries excluded). A `.wasm`
         * file is listed with empty text (PARITY G2): lint only needs its path.
         */
        fun textFiles(): Map<String, String> = verified.files
            .filterKeys { !DepStore.isPackagePath(it) && (DeftPaths.isTextFileName(it.substringAfterLast('/')) || DeftPaths.isWasmFileName(it)) }
            .mapValues { if (DeftPaths.isWasmFileName(it.key)) "" else it.value.decodeToString() }
    }

    /** The folder name an app id installs under (PARITY H5). */
    private fun slugFor(appId: String): String =
        requireSlug(appId.lowercase().replace(Regex("[^a-z0-9-]"), "").take(40).ifBlank { "app" })

    /**
     * PARITY H5, steps 1–3: signature, same-author key, no downgrade, path rules and quotas.
     * Reads nothing but [bytes], the pinned key and [largeStorageSlugs]; writes nothing.
     * [largeStorageSlugs] are the apps whose `storage.large` grant is on (PARITY G3). Only those
     * apps get the larger caps, and only when their package declares `storage.large`; every other
     * package is checked against the normal caps.
     */
    fun prepareInstall(bytes: ByteArray, largeStorageSlugs: Set<String> = emptySet()): PreparedInstall {
        val freeBytes = context.filesDir.usableSpace
        val verified = DeftPackage.verify(bytes) { appId ->
            val slug = runCatching { slugFor(appId) }.getOrNull()
            if (slug != null && slug in largeStorageSlugs) DeftPaths.largeCaps(freeBytes) else null
        }
        val slug = slugFor(verified.id)
        val dir = projectDir(slug)
        val previous = pinnedKey(slug)
        if (previous == null && dir.exists() && dir.list()?.isNotEmpty() == true) {
            // No pinned key means this folder holds a local app. A package must not take it over.
            throw IllegalArgumentException("app $slug already exists and was not installed from a package")
        }
        DeftPackage.requireSameKey(previous, verified.publicKey)
        DeftPackage.requireNotDowngrade(acceptedVersion(slug), verified.version)
        return PreparedInstall(slug, verified, previous)
    }

    /**
     * PARITY H5, step 5: extract into a staging folder, then swap it into place. If anything fails
     * before the swap, the previous version is still the live one, byte for byte.
     */
    fun commitInstall(prepared: PreparedInstall): String {
        val slug = prepared.slug
        val verified = prepared.verified
        val dir = projectDir(slug)
        val staging = File(context.filesDir, "deft/staging/$slug-${System.nanoTime()}")
        staging.parentFile?.mkdirs()
        try {
            ingestPackagedDependencies(verified.files)
            DeftPackage.extract(verified, staging)
            if (File(dir, "manifest.json").isFile) {
                val backup = File(context.filesDir, "deft/rollback/$slug")
                backup.deleteRecursively()
                dir.copyRecursively(backup, overwrite = true)
                prepared.previousKey?.let { File(context.filesDir, "deft/rollback/$slug.pub").writeText(it) }
            }
            val retired = File(staging.parentFile, "${staging.name}.old")
            if (dir.exists() && !dir.renameTo(retired)) throw IllegalStateException("could not move the old version aside")
            if (!staging.renameTo(dir)) {
                retired.takeIf { it.exists() }?.renameTo(dir)
                throw IllegalStateException("could not move the new version into place")
            }
            retired.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
        val keyFile = File(context.filesDir, "deft/keys/$slug.pub")
        keyFile.parentFile?.mkdirs()
        keyFile.writeText(verified.publicKey)
        File(context.filesDir, "deft/keys/$slug.version").writeText(verified.version)
        runCatching { DepStore.sweep(context) }
        bumpPreview()
        return slug
    }

    /** Verify and install without the lint gate; callers with a lint engine use prepare + commit. */
    fun installPackage(bytes: ByteArray): String = commitInstall(prepareInstall(bytes))

    /** Last version installed under the pinned key, or null for a first install. */
    fun acceptedVersion(slug: String): String? =
        File(context.filesDir, "deft/keys/${requireSlug(slug)}.version").takeIf { it.isFile }?.readText()?.trim()

    /**
     * Spec 10.10, source 1: `deps/<algo>-<hex>` files inside a signed package go into the store
     * before any app file is written. Every dependency must be pinned; files nobody declares are rejected.
     */
    private fun ingestPackagedDependencies(files: Map<String, ByteArray>) {
        val manifest = files["manifest.json"] ?: throw IllegalArgumentException("package is missing manifest.json")
        val root = kotlinx.serialization.json.Json.parseToJsonElement(manifest.decodeToString()) as? kotlinx.serialization.json.JsonObject
            ?: throw IllegalArgumentException("manifest.json must be an object")
        val deps = app.deft.registry.DeftManifestParser.parseDependencies(root["dependencies"])
        val byPath = deps.associateBy { dep ->
            val integrity = dep.integrity
                ?: throw IllegalArgumentException("dependency ${dep.url} has no hash; packages must pin every dependency")
            DepStore.packagePath(integrity)
        }
        for ((path, body) in files) {
            if (!DepStore.isPackagePath(path)) continue
            val dep = byPath[path] ?: throw IllegalArgumentException("$path is not a declared dependency")
            DepStore.store(context, dep.integrity!!, body)
        }
    }

    fun fork(slug: String, overlayAppId: String? = null): String {
        val clean = requireSlug(slug)
        val src = projectDir(clean)
        if (!File(src, "manifest.json").isFile) throw IllegalArgumentException("unknown app $clean")
        var destSlug = "$clean-fork"
        var n = 2
        while (hasProject(destSlug)) {
            destSlug = "$clean-fork-$n"
            n++
        }
        val dest = projectDir(destSlug)
        src.copyRecursively(dest, overwrite = true)
        val overlayId = overlayAppId ?: clean
        val overlay = OverlayStore.dir(context, overlayId)
        if (overlay.isDirectory) overlay.copyRecursively(dest, overwrite = true)
        val manifestFile = File(dest, "manifest.json")
        val manifest = org.json.JSONObject(manifestFile.readText())
        manifest.put("id", destSlug)
        manifest.put("forked_from", org.json.JSONObject().put("id", clean))
        manifestFile.writeText(manifest.toString(2))
        bumpPreview()
        return destSlug
    }

    fun resetOverlay(appId: String) {
        OverlayStore.reset(OverlayStore.dir(context, appId))
        runCatching { DepStore.sweep(context) }
        bumpPreview()
    }

    fun rollbackPackage(slug: String) {
        val clean = requireSlug(slug)
        val backup = File(context.filesDir, "deft/rollback/$clean")
        if (!backup.isDirectory) throw IllegalArgumentException("nothing to roll back")
        val dir = projectDir(clean)
        dir.deleteRecursively()
        backup.copyRecursively(dir, overwrite = true)
        backup.deleteRecursively()
        val backupKey = File(context.filesDir, "deft/rollback/$clean.pub")
        if (backupKey.isFile) {
            backupKey.copyTo(File(context.filesDir, "deft/keys/$clean.pub"), overwrite = true)
            backupKey.delete()
        }
        runCatching { DepStore.sweep(context) }
        bumpPreview()
    }

    fun importZip(slug: String, input: InputStream): AppArchive.ImportReport {
        val clean = requireSlug(slug)
        val dir = projectDir(clean)
        if (dir.exists() && dir.listFiles()?.isNotEmpty() == true) {
            throw IllegalArgumentException("app $clean already exists")
        }
        val report = AppArchive.import(dir, input)
        if (!File(dir, "manifest.json").isFile) {
            dir.deleteRecursively()
            throw IllegalArgumentException("package is missing manifest.json")
        }
        bumpPreview()
        return report
    }

    fun edit(relative: String, old: String, new: String, slug: String) {
        if (old.isEmpty()) throw IllegalArgumentException("old_string required")
        if (overlayDirIfPackaged(slug) != null) {
            val text = read(relative, slug)
            val first = text.indexOf(old)
            if (first < 0) throw IllegalArgumentException("old_string not found in $relative")
            if (text.indexOf(old, first + old.length) >= 0) {
                throw IllegalArgumentException("old_string matches more than once in $relative")
            }
            write(relative, text.replaceFirst(old, new), slug)
            return
        }
        val file = resolve(relative, slug)
        if (!file.isFile) throw IllegalArgumentException("not a file")
        val text = file.readText()
        val first = text.indexOf(old)
        if (first < 0) {
            throw IllegalArgumentException(
                "old_string not found in $relative (${text.length}B). Copy the snippet from files.read/grep exactly; shorter unique blocks work better.",
            )
        }
        val second = text.indexOf(old, first + old.length)
        if (second >= 0) {
            throw IllegalArgumentException(
                "old_string matches more than once in $relative. Add surrounding lines so it matches once.",
            )
        }
        val next = text.replaceFirst(old, new)
        DeftPaths.requireContentSize(DeftPaths.parse(relative), next.toByteArray(Charsets.UTF_8).size.toLong())
        file.writeText(next)
        bumpPreview()
    }

    fun delete(relative: String, slug: String) {
        val overlay = overlayDirIfPackaged(slug)
        if (overlay != null) {
            OverlayStore.deletePath(overlay, relative, "")
            bumpPreview()
            return
        }
        val file = resolve(relative, slug)
        val root = projectDir(slug)
        if (file.canonicalFile == root) throw IllegalArgumentException("cannot delete workspace root")
        if (!file.exists()) throw IllegalArgumentException("not found")
        if (file.isDirectory) {
            if (file.listFiles()?.isNotEmpty() == true) throw IllegalArgumentException("directory not empty")
            if (!file.delete()) throw IllegalStateException("delete failed")
        } else if (!file.delete()) {
            throw IllegalStateException("delete failed")
        }
        bumpPreview()
    }

    fun bumpPreview() {
        _previewRevision.value = _previewRevision.value + 1
    }

    fun grep(pattern: String, glob: String?, context: Int = WorkspaceFiles.GREP_DEFAULT_CONTEXT, slug: String): String {
        val ctx = context.coerceIn(0, WorkspaceFiles.GREP_MAX_CONTEXT)
        val (regex, literal) = WorkspaceFiles.compileGrep(pattern)
        val root = projectDir(slug)
        val blocks = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var extra = 0
        walk(root, glob) { rel, file ->
            if (!file.isFile) return@walk
            if (file.length() > MAX_BYTES) {
                skipped += "skipped too large: $rel (${WorkspaceFiles.formatSize(file.length())})"
                return@walk
            }
            val lines = runCatching { file.readText().split('\n') }.getOrNull() ?: return@walk
            lines.forEachIndexed { i, line ->
                if (!regex.matcher(line).find()) return@forEachIndexed
                if (blocks.size >= WorkspaceFiles.GREP_MAX_HITS) {
                    extra++
                    return@forEachIndexed
                }
                blocks += WorkspaceFiles.grepHitBlock(rel, lines, i, ctx)
            }
        }
        val body = when {
            blocks.isEmpty() -> "no matches"
            ctx > 0 -> blocks.joinToString("\n--\n")
            else -> blocks.joinToString("\n")
        }
        val footer = buildList {
            if (literal) add("[treated as literal: invalid regex]")
            if (extra > 0) add("… $extra more matches. Narrow pattern or glob.")
            addAll(skipped)
        }
        return if (footer.isEmpty()) body else body + "\n" + footer.joinToString("\n")
    }

    fun glob(pattern: String, slug: String): String {
        val out = mutableListOf<String>()
        walk(projectDir(slug), pattern) { rel, file ->
            if (file.isFile) out += rel
        }
        return out.sorted().joinToString("\n").ifBlank { "no files" }
    }

    private fun walk(root: File, glob: String?, each: (rel: String, file: File) -> Unit) {
        root.walkTopDown().forEach { file ->
            val rel = file.relativeTo(root).path.replace('\\', '/')
            if (rel == "." || rel.isEmpty()) return@forEach
            if (glob != null && !WorkspaceFiles.globMatches(rel, glob)) return@forEach
            each(rel, file)
        }
    }

    private fun readCurrent(): String? {
        if (!currentFile.isFile) return null
        val slug = currentFile.readText().trim()
        return slug.takeIf { SLUG.matches(it) && File(projectDir(it), "manifest.json").isFile }
    }

    companion object {
        const val ID_PREFIX = "myapp:"
        const val HOST_SUFFIX = ".app.deft.internal"
        private val SLUG = Regex("^[a-z0-9-]{1,40}$")
        private val RESERVED = setOf("home", "home-canvas")
        val MAX_BYTES: Long get() = DeftPaths.active.maxTextBytes

        fun requireSlug(raw: String): String {
            val slug = raw.trim().lowercase()
            if (!SLUG.matches(slug)) throw IllegalArgumentException("slug must be 1-40 chars [a-z0-9-]")
            if (slug in RESERVED) throw IllegalArgumentException("slug '$slug' is reserved")
            return slug
        }

        fun webAppId(slug: String): String = ID_PREFIX + requireSlug(slug)

        /** Legacy slug origin; only for rows created before PARITY H2 and the throwaway verifier. */
        fun origin(slug: String): String = "https://${requireSlug(slug)}$HOST_SUFFIX"

        fun originFor(originId: String): String = "https://$originId$HOST_SUFFIX"

        fun startUrl(slug: String): String = origin(slug) + "/"

        fun manifestUrl(slug: String): String = origin(slug) + "/manifest.json"

        fun slugFromHost(host: String?): String? {
            val h = host?.lowercase() ?: return null
            if (!h.endsWith(HOST_SUFFIX)) return null
            return h.removeSuffix(HOST_SUFFIX).takeIf { SLUG.matches(it) }
        }
    }
}

data class ProjectInfo(
    val slug: String,
    val name: String,
    val dir: File,
)

data class ParsedManifest(
    val name: String,
    val startUrlRel: String,
    val capabilities: List<Capability>,
    /** Declared `network` origins (the manifest's, before any consent). */
    val network: List<String> = emptyList(),
    /** W1: the declared widget page and sizes; null when the app has no widget. */
    val widget: app.deft.registry.DeftWidget? = null,
)
