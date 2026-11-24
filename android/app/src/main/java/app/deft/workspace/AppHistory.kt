package app.deft.workspace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Content-addressed history for one app folder.
 * Each agent session is one log entry. Undo restores every file in that session
 * to the bytes from before the session's first write.
 */
object AppHistory {
    private const val MAX_SESSIONS = 200
    private const val MAX_BYTES = 50L * 1024L * 1024L

    fun remember(
        context: Context,
        root: File,
        relative: String,
        sessionId: String = "default",
        actor: String = "agent",
    ) {
        store(context).remember(root, relative, sessionId, actor)
    }

    fun undo(context: Context, root: File): String = store(context).undo(root)

    fun export(context: Context, root: File, out: OutputStream) {
        store(context).export(root, out)
    }

    private fun store(context: Context) = Store(File(context.filesDir, "deft/history"))

    internal class Store(private val home: File) {
        fun remember(root: File, relative: String, sessionId: String, actor: String) {
            val rel = DeftPaths.parse(relative).normalized
            if (rel.isEmpty()) return
            val file = File(root, rel)
            val existed = file.isFile
            val hash = if (existed) storeBlob(file) else sha256(ByteArray(0))
            val sessions = read(root).toMutableList()
            val last = sessions.lastOrNull()
            val snap = Snap(rel, hash, file.isFile)
            if (last != null && last.sessionId == sessionId) {
                if (last.paths.none { it.path == rel }) last.paths.add(snap)
            } else {
                sessions.add(
                    Session(
                        time = System.currentTimeMillis(),
                        actor = actor,
                        sessionId = sessionId,
                        paths = mutableListOf(snap),
                    ),
                )
            }
            write(root, prune(root, sessions))
        }

        fun undo(root: File): String {
            val sessions = read(root).toMutableList()
            if (sessions.isEmpty()) return "nothing to undo"
            val session = sessions.removeAt(sessions.lastIndex)
            for (snap in session.paths) {
                val dest = File(root, snap.path)
                if (!snap.existed) {
                    dest.delete()
                } else {
                    val blob = File(blobDir(), snap.hash)
                    if (!blob.isFile) return "missing snapshot for ${snap.path}"
                    dest.parentFile?.mkdirs()
                    blob.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
                }
            }
            write(root, sessions)
            return "restored ${session.paths.size} file(s) from ${session.sessionId}"
        }

        fun export(root: File, out: OutputStream) {
            ZipOutputStream(out).use { zip ->
                if (root.isDirectory) {
                    root.walkTopDown().forEach { file ->
                        if (!file.isFile) return@forEach
                        val rel = file.relativeTo(root).path.replace('\\', '/')
                        val parsed = runCatching { DeftPaths.parse(rel) }.getOrNull() ?: return@forEach
                        zip.putNextEntry(ZipEntry(parsed.normalized))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                zip.putNextEntry(ZipEntry("history.json"))
                zip.write(JSONArray().apply {
                    read(root).forEach { put(it.toJson()) }
                }.toString().toByteArray())
                zip.closeEntry()
            }
        }

        private fun storeBlob(file: File): String {
            blobDir().mkdirs()
            val temp = File(blobDir(), ".tmp-${System.nanoTime()}")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                file.inputStream().use { input ->
                    temp.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            output.write(buf, 0, n)
                        }
                    }
                }
                val hash = hex(digest.digest())
                val blob = File(blobDir(), hash)
                if (!blob.isFile) {
                    if (!temp.renameTo(blob)) {
                        temp.inputStream().use { input -> blob.outputStream().use { output -> input.copyTo(output) } }
                        temp.delete()
                    }
                } else {
                    temp.delete()
                }
                return hash
            } catch (err: Exception) {
                temp.delete()
                throw err
            }
        }

        private fun prune(root: File, sessions: MutableList<Session>): List<Session> {
            val overLimit = sessions.size > MAX_SESSIONS || blobBytes() > MAX_BYTES
            val protect = if (overLimit) currentHashes(root) else emptySet()
            while (sessions.size > MAX_SESSIONS) sessions.removeAt(0)
            while (blobBytes() > MAX_BYTES && sessions.size > 1) {
                val oldest = sessions.first()
                if (oldest.paths.any { it.hash in protect }) break
                sessions.removeAt(0)
            }
            val used = sessions.flatMap { it.paths.map { snap -> snap.hash } }.toSet() + protect
            blobDir().listFiles()?.forEach { blob ->
                if (blob.isFile && blob.name !in used && !blob.name.startsWith(".tmp-")) blob.delete()
            }
            return sessions
        }

        private fun currentHashes(root: File): Set<String> {
            if (!root.isDirectory) return emptySet()
            val digest = MessageDigest.getInstance("SHA-256")
            val out = mutableSetOf<String>()
            val buf = ByteArray(8192)
            root.walkTopDown().forEach { file ->
                if (!file.isFile) return@forEach
                digest.reset()
                file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
                out.add(hex(digest.digest()))
            }
            return out
        }

        private fun blobBytes(): Long =
            blobDir().listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

        private fun read(root: File): List<Session> {
            val log = logFile(root)
            if (!log.isFile) return emptyList()
            return log.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching { Session.fromJson(JSONObject(line)) }.getOrNull()
            }
        }

        private fun write(root: File, sessions: List<Session>) {
            val log = logFile(root)
            log.parentFile?.mkdirs()
            log.writeText(sessions.joinToString("") { it.toJson().toString() + "\n" })
        }

        private fun blobDir() = File(home, "blobs")

        private fun logFile(root: File): File {
            val id = sha256(root.canonicalPath.toByteArray()).take(16)
            return File(home, "logs/$id.log")
        }
    }

    private data class Snap(val path: String, val hash: String, val existed: Boolean)

    private data class Session(
        val time: Long,
        val actor: String,
        val sessionId: String,
        val paths: MutableList<Snap>,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("time", time)
            .put("actor", actor)
            .put("session_id", sessionId)
            .put("paths", JSONArray().apply {
                paths.forEach { snap ->
                    put(
                        JSONObject()
                            .put("path", snap.path)
                            .put("hash", snap.hash)
                            .put("existed", snap.existed),
                    )
                }
            })

        companion object {
            fun fromJson(obj: JSONObject): Session {
                val paths = mutableListOf<Snap>()
                val arr = obj.optJSONArray("paths") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    paths.add(Snap(item.getString("path"), item.getString("hash"), item.optBoolean("existed")))
                }
                return Session(
                    time = obj.optLong("time"),
                    actor = obj.optString("actor", "agent"),
                    sessionId = obj.optString("session_id", "default"),
                    paths = paths,
                )
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(raw: ByteArray): String = raw.joinToString("") { "%02x".format(it) }
}
