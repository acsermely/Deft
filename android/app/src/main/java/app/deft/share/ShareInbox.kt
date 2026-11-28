package app.deft.share

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Files shared into a web app.
 *
 * A share's `content://` streams are copied once into a private per-app inbox, because the
 * sender's URI grant does not outlive [ShareToActivity]. The inbox is not a `files.*` root: the
 * app cannot list it, and each item is read exactly once with `intents.readShare {id}`, which
 * deletes it. Unread items expire after [TTL_MS]. One app holds at most [MAX_APP_ITEMS] unread
 * items and [MAX_APP_BYTES] in total; past that, new items are refused until it reads some.
 */
class ShareInbox(
    private val root: File,
    private val maxAppItems: Int = MAX_APP_ITEMS,
    private val maxAppBytes: Long = MAX_APP_BYTES,
) {
    data class Item(val id: String, val name: String, val mime: String, val size: Long)

    /**
     * The directory of one app, named by the SHA-256 hex of its full id. App ids carry a prefix and
     * a colon ("bundled:...", "myapp:..."), which a file name cannot hold, and hashing keeps every id
     * apart. Every lookup goes through here, so reads and writes always agree. Directories written
     * under the old raw-id names expire under the normal TTL sweep.
     */
    private fun appDir(webAppId: String): File {
        require(webAppId.isNotEmpty()) { "empty app id" }
        val digest = MessageDigest.getInstance("SHA-256").digest(webAppId.toByteArray(Charsets.UTF_8))
        return File(root, digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
    }

    /**
     * Copies [input] into the inbox. Returns null when it is empty, larger than [MAX_ITEM_BYTES],
     * or the app's unread items would pass [MAX_APP_ITEMS] or [MAX_APP_BYTES] (new items are
     * refused; [full] says why).
     */
    fun store(webAppId: String, name: String, mime: String, input: InputStream, now: Long): Item? {
        val dir = appDir(webAppId).apply { mkdirs() }
        val (count, used) = usage(dir, now)
        if (count >= maxAppItems || used >= maxAppBytes) return null
        val limit = minOf(MAX_ITEM_BYTES, maxAppBytes - used)
        val id = UUID.randomUUID().toString()
        val data = File(dir, "$id.bin")
        var written = 0L
        data.outputStream().use { out ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                written += n
                if (written > limit) {
                    out.close()
                    data.delete()
                    return null
                }
                out.write(buf, 0, n)
            }
        }
        if (written == 0L) {
            data.delete()
            return null
        }
        val safeName = name.replace(Regex("[\\r\\n/\\\\]"), "_").take(120).ifBlank { "shared" }
        val safeMime = mime.takeIf { it.matches(Regex("^[a-z0-9.+-]+/[a-z0-9.+*-]+$")) } ?: "application/octet-stream"
        File(dir, "$id.meta").writeText("$now\n$safeMime\n$safeName")
        return Item(id, safeName, safeMime, written)
    }

    /** True when [webAppId] has as many unread items, or bytes, as its inbox may hold. */
    fun full(webAppId: String, now: Long): Boolean {
        val (count, used) = usage(appDir(webAppId), now)
        return count >= maxAppItems || used >= maxAppBytes
    }

    /** Unread items of one app that have not expired: how many, and their total size. */
    private fun usage(dir: File, now: Long): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        dir.listFiles { f -> f.name.endsWith(".meta") }?.forEach { meta ->
            val at = runCatching { meta.readLines().first().toLong() }.getOrDefault(0L)
            if (now - at !in 0..TTL_MS) return@forEach
            count += 1
            bytes += File(dir, meta.name.removeSuffix(".meta") + ".bin").length()
        }
        return count to bytes
    }

    /** Returns the bytes and mime of one item and deletes it. Null if unknown, expired or read already. */
    fun take(webAppId: String, id: String, now: Long): Pair<ByteArray, String>? {
        if (!id.matches(Regex("^[0-9a-f-]{36}$"))) return null
        val dir = appDir(webAppId)
        val data = File(dir, "$id.bin")
        val meta = File(dir, "$id.meta")
        if (!data.isFile || !meta.isFile) return null
        val lines = meta.readLines()
        val at = lines.getOrNull(0)?.toLongOrNull() ?: 0L
        val mime = lines.getOrNull(1) ?: "application/octet-stream"
        val bytes = if (now - at in 0..TTL_MS) data.readBytes() else null
        data.delete()
        meta.delete()
        return bytes?.let { it to mime }
    }

    /** Deletes expired items for every app, and all items of [webAppId] when given (uninstall). */
    fun sweep(now: Long, webAppId: String? = null) {
        if (webAppId != null) {
            appDir(webAppId).deleteRecursively()
            return
        }
        root.listFiles()?.forEach { dir ->
            dir.listFiles { f -> f.name.endsWith(".meta") }?.forEach { meta ->
                val at = runCatching { meta.readLines().first().toLong() }.getOrDefault(0L)
                if (now - at !in 0..TTL_MS) {
                    File(dir, meta.name.removeSuffix(".meta") + ".bin").delete()
                    meta.delete()
                }
            }
        }
    }

    companion object {
        const val MAX_ITEM_BYTES = 5L * 1024 * 1024
        const val MAX_ITEMS = 10

        /** Unread items one app may have waiting at once. */
        const val MAX_APP_ITEMS = 50

        /** Total size of one app's unread items. */
        const val MAX_APP_BYTES = 50L * 1024 * 1024
        const val TTL_MS = 24L * 60 * 60 * 1000

        fun forFilesDir(filesDir: File) = ShareInbox(File(filesDir, "deft/share-inbox"))
    }
}
