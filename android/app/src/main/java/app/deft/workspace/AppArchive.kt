package app.deft.workspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object AppArchive {
    data class ImportReport(
        val files: Int,
        val skipped: List<String>,
    )

    fun export(src: File, out: OutputStream) {
        val root = src.canonicalFile
        if (!root.isDirectory) throw IllegalArgumentException("not a directory")
        ZipOutputStream(out).use { zip ->
            root.walkTopDown().forEach { file ->
                if (!file.isFile) return@forEach
                val rel = file.relativeTo(root).path.replace('\\', '/')
                if (rel.isEmpty()) return@forEach
                val parsed = runCatching { DeftPaths.parse(rel) }.getOrNull() ?: return@forEach
                zip.putNextEntry(ZipEntry(parsed.normalized))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun import(dest: File, input: InputStream): ImportReport {
        val root = dest.canonicalFile
        root.mkdirs()
        val seen = mutableSetOf<String>()
        val skipped = mutableListOf<String>()
        var files = 0
        var totalBytes = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    try {
                        if (entry.isDirectory) continue
                        if (name.startsWith("/") || name.contains("..")) {
                            throw IllegalArgumentException("zip-slip: $name")
                        }
                        val rel = DeftPaths.parse(name)
                        if (!seen.add(rel.normalized)) {
                            throw IllegalArgumentException("duplicate zip entry: ${rel.normalized}")
                        }
                        DeftPaths.requireFileBudget(files, addingNew = true)
                        val bytes = readBounded(zip, DeftPaths.active.maxPackageBytes - totalBytes)
                            ?: throw IllegalArgumentException("package larger than ${DeftPaths.active.maxPackageBytes} bytes")
                        totalBytes += bytes.size
                        DeftPaths.requireContentSize(rel, bytes.size.toLong())
                        val outFile = File(root, rel.normalized).canonicalFile
                        if (!outFile.path.startsWith(root.path)) {
                            throw IllegalArgumentException("zip-slip: $name")
                        }
                        outFile.parentFile?.mkdirs()
                        outFile.writeBytes(bytes)
                        if (Files.isSymbolicLink(outFile.toPath())) {
                            outFile.delete()
                            throw IllegalArgumentException("symlinks are not allowed: $name")
                        }
                        files++
                    } catch (e: IllegalArgumentException) {
                        val msg = e.message.orEmpty()
                        if (msg.startsWith("zip-slip") ||
                            msg.startsWith("symlinks") ||
                            msg.startsWith("duplicate") ||
                            msg.startsWith("package larger")
                        ) {
                            throw e
                        }
                        skipped += "${name}: ${e.message}"
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: ZipException) {
            throw IllegalArgumentException("zip-slip: ${e.message}", e)
        }
        if (files == 0 && skipped.isEmpty()) {
            throw IllegalArgumentException("empty package")
        }
        return ImportReport(files = files, skipped = skipped)
    }

    /**
     * Reads the current zip entry, stopping as soon as it passes [limit] bytes.
     * Returns null when the entry is larger, so a small archive cannot inflate into memory.
     */
    fun readBounded(input: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
    }
}
