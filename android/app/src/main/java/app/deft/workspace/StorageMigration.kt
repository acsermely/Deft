package app.deft.workspace

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import app.deft.catalog.Capability
import app.deft.registry.WebAppManifest
import kotlinx.serialization.json.Json
import java.io.File

object StorageMigration {
    const val PREF = "fl_storage"
    const val PREF_NEEDS_PICKER = "needs_documents_picker"
    const val PREF_NOTICE = "removed_url_apps_notice"
    private const val MARKER = "deft/.migrated-v1"

    data class Report(
        val copied: List<String>,
        val skipped: List<String>,
        val canvasMoved: Boolean,
        val needsPicker: Boolean,
    ) {
        fun text(): String = buildString {
            appendLine("Deft storage migration")
            if (copied.isEmpty() && skipped.isEmpty() && !canvasMoved) {
                appendLine("Nothing to copy.")
            }
            if (canvasMoved) appendLine("Moved home canvas to deft/canvas/home.")
            copied.forEach { appendLine("copied $it") }
            skipped.forEach { appendLine("skipped $it") }
            if (needsPicker) {
                appendLine("Documents/myapps was not readable. Pick the folder to import remaining apps.")
            }
        }
    }

    fun marker(context: Context): File = File(context.filesDir, MARKER)

    fun reportFile(context: Context): File = File(context.filesDir, "deft/migration.txt")

    fun localRoot(context: Context): File = File(context.filesDir, "deft/local").canonicalFile

    fun canvasRoot(context: Context): File = File(context.filesDir, "deft/canvas/home").canonicalFile

    fun bundledRoot(context: Context): File = File(context.filesDir, "deft/bundled").canonicalFile

    fun needsPicker(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(PREF_NEEDS_PICKER, false)

    fun markUrlAppsNoticeSeen(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_NOTICE, true)
            .apply()
    }

    fun setNeedsPicker(context: Context, value: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_NEEDS_PICKER, value)
            .apply()
    }

    fun run(context: Context): Report {
        val marker = marker(context)
        localRoot(context).mkdirs()
        canvasRoot(context).mkdirs()
        val copied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val canvasMoved = migrateOldCanvas(context)
        rewriteLegacyManifests(localRoot(context))

        if (marker.isFile) {
            return Report(copied, skipped, canvasMoved, needsPicker(context))
        }

        val docs = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "myapps",
        )
        var needsPicker = false
        if (docs.isDirectory) {
            val children = docs.listFiles()
            if (children == null) {
                needsPicker = true
            } else {
                for (folder in children) {
                    if (!folder.isDirectory) continue
                    val slug = runCatching { MyAppsStore.requireSlug(folder.name) }.getOrNull()
                    if (slug == null) {
                        skipped += "${folder.name}: slug must be 1-40 chars [a-z0-9-]"
                        continue
                    }
                    val result = copyProject(folder, File(localRoot(context), slug))
                    copied += result.copied
                    skipped += result.skipped
                }
            }
        }
        setNeedsPicker(context, needsPicker)
        rewriteLegacyManifests(localRoot(context))
        val report = Report(copied, skipped, canvasMoved, needsPicker)
        writeReport(context, report)
        marker.parentFile?.mkdirs()
        marker.writeText("v1\n")
        return report
    }

    fun rewriteLegacyManifests(root: File) {
        if (!root.isDirectory) return
        root.listFiles()?.forEach { folder ->
            if (!folder.isDirectory) return@forEach
            val file = File(folder, "manifest.json")
            if (!file.isFile) return@forEach
            val raw = runCatching { file.readText() }.getOrNull() ?: return@forEach
            runCatching {
                val old = org.json.JSONObject(raw)
                // Only old-format manifests: they carry requiredCapabilities and no "deft" version key.
                if (!old.has("requiredCapabilities") || old.has("deft")) return@forEach
                val parsed = Json { ignoreUnknownKeys = true }
                    .decodeFromString(WebAppManifest.serializer(), raw)
                val name = (parsed.name ?: parsed.short_name ?: folder.name).take(30)
                val caps = parsed.requiredCapabilities
                    .flatMap { Capability.migrateStoredId(it) }
                    .distinct()
                val next = org.json.JSONObject()
                next.put("deft", 1)
                next.put("id", folder.name)
                next.put("name", name)
                next.put("version", old.optString("version", "0.1.0"))
                next.put("profile", "myapp")
                next.put("start_url", parsed.start_url ?: "./index.html")
                next.put(
                    "capabilities",
                    org.json.JSONArray(
                        caps.map { cap ->
                            org.json.JSONObject()
                                .put("id", cap.id)
                                .put("reason", "Migrated from an older capability id")
                        },
                    ),
                )
                next.put("network", old.optJSONArray("network") ?: org.json.JSONArray())
                // Keep fields this migration does not own (icons, description, ...).
                for (key in old.keys()) {
                    if (key == "requiredCapabilities" || key == "short_name" || next.has(key)) continue
                    next.put(key, old.get(key))
                }
                file.writeText(next.toString(2) + "\n")
            }
        }
    }

    fun importFromTree(context: Context, treeUri: Uri): Report {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("could not open folder")
        val copied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val dirs = if (tree.name.equals("myapps", ignoreCase = true) ||
            tree.listFiles().any { it.isDirectory && DocumentFile.fromSingleUri(context, it.uri)?.findFile("manifest.json") != null }
        ) {
            tree.listFiles().filter { it.isDirectory }
        } else {
            listOf(tree)
        }
        for (folder in dirs) {
            if (!folder.isDirectory) continue
            val name = folder.name ?: continue
            // The display name comes from the document provider, so it is untrusted input.
            val slug = runCatching { MyAppsStore.requireSlug(name) }.getOrNull()
            if (slug == null) {
                skipped += "$name: slug must be 1-40 chars [a-z0-9-]"
                continue
            }
            val dest = File(localRoot(context), slug)
            val result = copyDocumentProject(context, folder, dest)
            copied += result.copied
            skipped += result.skipped
        }
        setNeedsPicker(context, false)
        val report = Report(copied, skipped, canvasMoved = false, needsPicker = false)
        writeReport(context, report)
        return report
    }

    private fun migrateOldCanvas(context: Context): Boolean {
        val old = File(context.filesDir, "home-canvas")
        val dest = canvasRoot(context)
        if (!old.isDirectory) return false
        if (File(dest, "index.html").isFile) return false
        dest.mkdirs()
        old.copyRecursively(dest, overwrite = false)
        return true
    }

    private data class CopyResult(val copied: List<String>, val skipped: List<String>)

    private fun copyProject(src: File, dest: File): CopyResult {
        val copied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        dest.mkdirs()
        src.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val rel = file.relativeTo(src).path.replace('\\', '/')
            val parsed = runCatching { DeftPaths.parse(rel.lowercase()) }
            if (parsed.isFailure) {
                skipped += "${src.name}/$rel: ${parsed.exceptionOrNull()?.message}"
                return@forEach
            }
            val target = File(dest, parsed.getOrThrow().normalized)
            if (target.exists()) {
                skipped += "${src.name}/$rel: already present"
                return@forEach
            }
            runCatching {
                DeftPaths.requireContentSize(parsed.getOrThrow(), file.length())
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = false)
                copied += "${dest.name}/${parsed.getOrThrow().normalized}"
            }.onFailure { skipped += "${src.name}/$rel: ${it.message}" }
        }
        return CopyResult(copied, skipped)
    }

    private fun copyDocumentProject(context: Context, src: DocumentFile, dest: File): CopyResult {
        val copied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        dest.mkdirs()
        fun walk(dir: DocumentFile, prefix: String) {
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    walk(child, rel)
                    continue
                }
                val parsed = runCatching { DeftPaths.parse(rel.lowercase()) }
                if (parsed.isFailure) {
                    skipped += "${dest.name}/$rel: ${parsed.exceptionOrNull()?.message}"
                    continue
                }
                val target = File(dest, parsed.getOrThrow().normalized)
                if (target.exists()) {
                    skipped += "${dest.name}/$rel: already present"
                    continue
                }
                runCatching {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        val bytes = input.readBytes()
                        DeftPaths.requireContentSize(parsed.getOrThrow(), bytes.size.toLong())
                        target.parentFile?.mkdirs()
                        target.writeBytes(bytes)
                    } ?: error("could not read $rel")
                    copied += "${dest.name}/${parsed.getOrThrow().normalized}"
                }.onFailure { skipped += "${dest.name}/$rel: ${it.message}" }
            }
        }
        walk(src, "")
        return CopyResult(copied, skipped)
    }

    private fun writeReport(context: Context, report: Report) {
        val file = reportFile(context)
        file.parentFile?.mkdirs()
        file.writeText(report.text())
    }
}
