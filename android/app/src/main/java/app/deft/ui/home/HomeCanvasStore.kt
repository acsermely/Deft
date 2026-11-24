package app.deft.ui.home

import app.deft.workspace.DeftPaths
import app.deft.workspace.WorkspaceFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Private HTML/CSS/JS tree shown as the home wallpaper.
 * Agent tools write here; [revision] bumps so the WebView reloads.
 */
class HomeCanvasStore(private val root: File) {
    @Volatile private var upgradeChecked = false
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** W1: the `<deft-widget>` elements in the canvas HTML. Recomputed on every write, reset and start. */
    private val _placedWidgets = MutableStateFlow<List<WidgetPlacement>>(emptyList())
    val placedWidgets: StateFlow<List<WidgetPlacement>> = _placedWidgets.asStateFlow()

    init {
        _placedWidgets.value = scanPlacements()
    }

    fun dir(): File = root.canonicalFile

    fun ensureSeeded() {
        val index = File(dir(), HomeCanvasDefaults.INDEX)
        if (index.isFile) {
            upgradeLegacyStyles()
            return
        }
        writeBundled()
    }

    /**
     * Swaps in the bundled files only when the stored copy is byte-identical to a previous bundled default.
     * A file the user edited is never touched.
     */
    private fun upgradeLegacyStyles() {
        if (upgradeChecked) return
        upgradeChecked = true
        for (name in HomeCanvasDefaults.files.keys) {
            runCatching { upgradeLegacyFile(name) }
                .onFailure { android.util.Log.w("HomeCanvasStore", "upgrade skipped: $name", it) }
        }
    }

    private fun upgradeLegacyFile(name: String) {
        val known = LEGACY_SHA256[name] ?: return
        val file = File(dir(), name)
        if (!file.isFile || file.length() > MAX_BYTES) return
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        if (digest.joinToString("") { "%02x".format(it) } !in known) return
        val next = HomeCanvasDefaults.files.getValue(name)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(next.trimIndent() + "\n")
            java.nio.file.Files.move(
                tmp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            tmp.delete()
        }
    }

    fun reset() {
        val base = dir()
        if (base.exists()) {
            base.listFiles()?.forEach { child ->
                if (!child.deleteRecursively()) {
                    throw IllegalStateException("could not reset ${child.path}")
                }
            }
        }
        writeBundled()
        bump()
    }

    fun list(relative: String): String {
        ensureSeeded()
        val dir = resolve(relative.ifBlank { "." })
        if (!dir.isDirectory) throw IllegalArgumentException("not a directory")
        val base = dir.canonicalFile
        val rows = mutableListOf<String>()
        base.walkTopDown().forEach { file ->
            if (file.canonicalFile == base) return@forEach
            val rel = file.relativeTo(base).path.replace('\\', '/')
            if (rel.isEmpty()) return@forEach
            rows += if (file.isDirectory) "$rel/" else "$rel  ${file.length()}"
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

    fun read(relative: String): String {
        ensureSeeded()
        val file = resolve(relative)
        if (!file.isFile) throw IllegalArgumentException("not a file")
        if (file.length() > MAX_BYTES) throw IllegalArgumentException("file too large")
        return file.readText()
    }

    fun write(relative: String, content: String) {
        ensureSeeded()
        val rel = DeftPaths.parse(relative)
        val file = resolve(rel.normalized)
        val adding = !file.isFile
        DeftPaths.requireFileBudget(DeftPaths.countFiles(dir()), adding)
        val bytes = content.toByteArray(Charsets.UTF_8)
        DeftPaths.requireContentSize(rel, bytes.size.toLong())
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        bump()
    }

    fun edit(relative: String, old: String, new: String) {
        if (old.isEmpty()) throw IllegalArgumentException("old_string required")
        ensureSeeded()
        val file = resolve(relative)
        if (!file.isFile) throw IllegalArgumentException("not a file")
        val text = file.readText()
        val first = text.indexOf(old)
        if (first < 0) {
            throw IllegalArgumentException(
                "old_string not found in $relative (${text.length}B). Copy the snippet from canvas.read/grep exactly.",
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
        bump()
    }

    fun delete(relative: String) {
        ensureSeeded()
        val file = resolve(relative)
        val base = dir()
        if (file.canonicalFile == base) throw IllegalArgumentException("cannot delete canvas root")
        if (!file.exists()) throw IllegalArgumentException("not found")
        if (file.isDirectory) {
            if (file.listFiles()?.isNotEmpty() == true) throw IllegalArgumentException("directory not empty")
            if (!file.delete()) throw IllegalStateException("delete failed")
        } else if (!file.delete()) {
            throw IllegalStateException("delete failed")
        }
        bump()
    }

    fun grep(pattern: String, glob: String?, context: Int = WorkspaceFiles.GREP_DEFAULT_CONTEXT): String {
        ensureSeeded()
        val ctx = context.coerceIn(0, WorkspaceFiles.GREP_MAX_CONTEXT)
        val (regex, literal) = WorkspaceFiles.compileGrep(pattern)
        val root = dir()
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

    fun glob(pattern: String): String {
        ensureSeeded()
        val out = mutableListOf<String>()
        walk(dir(), pattern) { rel, file ->
            if (file.isFile) out += rel
        }
        return out.sorted().joinToString("\n").ifBlank { "no files" }
    }

    fun resolve(relative: String): File {
        val base = dir()
        base.mkdirs()
        val clean = relative.trim().trimStart('/')
        if (clean.isEmpty() || clean == ".") return base
        val rel = DeftPaths.parse(clean)
        val file = File(base, rel.normalized).canonicalFile
        if (!file.path.startsWith(base.path)) throw IllegalArgumentException("path escapes canvas")
        return file
    }

    private fun writeBundled() {
        val base = dir()
        if (!base.mkdirs() && !base.isDirectory) {
            throw IllegalStateException("could not create ${base.path}")
        }
        for ((name, content) in HomeCanvasDefaults.files) {
            File(base, name).writeText(content.trimIndent() + "\n")
        }
    }

    /** W1: reload the canvas without a file change (a widget app was installed, removed or edited). */
    fun refresh() = bump()

    private fun bump() {
        _placedWidgets.value = scanPlacements()
        _revision.value = _revision.value + 1
    }

    private fun scanPlacements(): List<WidgetPlacement> {
        val base = dir()
        if (!base.isDirectory) return emptyList()
        val out = mutableListOf<WidgetPlacement>()
        base.walkTopDown().forEach { file ->
            if (!file.isFile || !file.name.endsWith(".html", ignoreCase = true) || file.length() > MAX_BYTES) return@forEach
            out += CanvasWidgets.placements(file.readText())
        }
        return out
    }

    private fun walk(root: File, glob: String?, each: (rel: String, file: File) -> Unit) {
        root.walkTopDown().forEach { file ->
            val rel = file.relativeTo(root).path.replace('\\', '/')
            if (rel == "." || rel.isEmpty()) return@forEach
            if (glob != null && !WorkspaceFiles.globMatches(rel, glob)) return@forEach
            each(rel, file)
        }
    }

    companion object {
        /** SHA-256 of earlier bundled defaults (blur-era styles, and the pre-design-runtime files). Only untouched copies match. */
        private val LEGACY_SHA256: Map<String, Set<String>> = mapOf(
            HomeCanvasDefaults.INDEX to setOf(
                "6b782dd7ed886a29a5ebdfe04be20358fdbce49ad7d372eb0e8b44aa76942a5e",
                "aff087cf30aa3d024c664590dfeb9aa0cf2d6291c4f677eac8381592c656692a", // Deft 0.2.0: no widget row
            ),
            HomeCanvasDefaults.STYLES to setOf(
                "a58c511d00794cc2b4738ae8ff72cfd08dcfdf85f98afda7ed53601239d7c2dc",
                "9b5f5d705d02bdc551ec57fa9ac2f4de6aa232b5c0abc693a508ae1a087012d3",
                "b8323e610f946b77d1d2f11c7c548d7f8b5b74b133955db5651dfcf0f4300bf4", // Deft 0.2.0: big clock at the bottom
            ),
            HomeCanvasDefaults.MAIN to setOf("86a34428561659cca3ace6afcdf7e613cc9a8431db1bce06c022a8aafe4778a5"),
        )
        const val SLUG = "home-canvas"
        const val ORIGIN = "https://home.app.deft.internal"
        val MAX_BYTES: Long get() = DeftPaths.active.maxTextBytes

        fun startUrl(revision: Int): String = "$ORIGIN/?r=$revision"
    }
}
