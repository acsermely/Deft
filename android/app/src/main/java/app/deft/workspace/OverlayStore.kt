package app.deft.workspace

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Sparse overlay next to an installed app. overlay.json sits beside the file tree,
 * not inside it, so it is not served to the page.
 */
object OverlayStore {
    data class Entry(val path: String, val baseSha256: String?)

    fun dir(context: android.content.Context, appId: String): File =
        File(context.filesDir, "deft/overlay/$appId")

    fun metaFile(overlayDir: File): File =
        File(overlayDir.parentFile, "${overlayDir.name}.json")

    fun deleted(overlayDir: File): Set<String> = read(overlayDir).deleted

    fun read(overlayDir: File): Meta {
        val file = metaFile(overlayDir)
        if (!file.isFile) return Meta(baseVersion = "", files = emptyMap(), deleted = emptySet())
        val obj = JSONObject(file.readText())
        val files = linkedMapOf<String, Entry>()
        val filesObj = obj.optJSONObject("files") ?: JSONObject()
        for (key in filesObj.keys()) {
            val item = filesObj.optJSONObject(key) ?: continue
            val raw = item.opt("base_sha256")
            val hash = if (raw == null || raw == JSONObject.NULL) null else raw.toString()
            files[key] = Entry(key, hash)
        }
        val deleted = mutableSetOf<String>()
        val arr = obj.optJSONArray("deleted") ?: JSONArray()
        for (i in 0 until arr.length()) deleted.add(arr.getString(i))
        return Meta(obj.optString("base_version"), files, deleted)
    }

    fun writeFile(overlayDir: File, relative: String, bytes: ByteArray, baseSha256: String?, baseVersion: String) {
        val rel = DeftPaths.parse(relative).normalized
        val dest = File(overlayDir, rel)
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        val meta = read(overlayDir)
        val files = meta.files.toMutableMap()
        val prior = files[rel]
        files[rel] = Entry(rel, if (prior != null) prior.baseSha256 else baseSha256)
        val deleted = meta.deleted.toMutableSet()
        deleted.remove(rel)
        save(overlayDir, meta.copy(baseVersion = baseVersion.ifBlank { meta.baseVersion }, files = files, deleted = deleted))
    }

    fun deletePath(overlayDir: File, relative: String, baseVersion: String) {
        val rel = DeftPaths.parse(relative).normalized
        File(overlayDir, rel).delete()
        val meta = read(overlayDir)
        val files = meta.files.toMutableMap()
        files.remove(rel)
        val deleted = meta.deleted.toMutableSet()
        deleted.add(rel)
        save(overlayDir, meta.copy(baseVersion = baseVersion.ifBlank { meta.baseVersion }, files = files, deleted = deleted))
    }

    fun firstConflict(baseDir: File, overlayDir: File): String? {
        val meta = read(overlayDir)
        for (entry in meta.files.values) {
            val base = File(baseDir, entry.path)
            val now = if (base.isFile) sha256(base) else null
            if (entry.baseSha256 != now) return entry.path
        }
        return null
    }

    fun keepMine(baseDir: File, overlayDir: File, path: String) {
        val rel = DeftPaths.parse(path).normalized
        val meta = read(overlayDir)
        val entry = meta.files[rel] ?: return
        val base = File(baseDir, rel)
        val hash = if (base.isFile) sha256(base) else null
        val files = meta.files.toMutableMap()
        files[rel] = entry.copy(baseSha256 = hash)
        save(overlayDir, meta.copy(files = files))
    }

    fun takeUpstream(overlayDir: File, path: String) {
        val rel = DeftPaths.parse(path).normalized
        File(overlayDir, rel).delete()
        val meta = read(overlayDir)
        val files = meta.files.toMutableMap()
        files.remove(rel)
        save(overlayDir, meta.copy(files = files))
    }

    fun reset(overlayDir: File) {
        overlayDir.deleteRecursively()
        metaFile(overlayDir).delete()
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(raw: ByteArray): String = raw.joinToString("") { "%02x".format(it) }

    private fun save(overlayDir: File, meta: Meta) {
        val files = JSONObject()
        meta.files.values.sortedBy { it.path }.forEach { entry ->
            files.put(entry.path, JSONObject().put("base_sha256", entry.baseSha256 ?: JSONObject.NULL))
        }
        val deleted = JSONArray()
        meta.deleted.sorted().forEach { deleted.put(it) }
        val obj = JSONObject()
            .put("base_version", meta.baseVersion)
            .put("files", files)
            .put("deleted", deleted)
        val file = metaFile(overlayDir)
        file.parentFile?.mkdirs()
        file.writeText(obj.toString())
    }

    data class Meta(
        val baseVersion: String,
        val files: Map<String, Entry>,
        val deleted: Set<String>,
    )
}
