package app.deft.bridge.modules

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.deft.DeftApp
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgePicks
import app.deft.bridge.MediaPickResult
import app.deft.bridge.BridgeRequest
import app.deft.bridge.jsonArg
import app.deft.ui.files.MediaPickActivity
import app.deft.ui.files.SaveAsActivity
import app.deft.ui.files.TreePickActivity
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.util.UUID

class FilesModule(
    private val context: Context,
) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        val app = DeftApp.instance.repository.getApp(request.webAppId)
            ?: return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "unknown app")
        return when (request.method) {
            "files.pickTree" -> pickTree(app.id, app.filesTreeUri)
            "files.pickMedia" -> pickMedia(request)
            "files.saveAs" -> saveAs(request)
            "files.listVolumes" -> withAccess(app) { mode -> listVolumes(mode, app) }
            "files.listDir" -> withAccess(app) { mode -> listDir(mode, app, request) }
            "files.stat" -> withAccess(app) { mode -> stat(mode, app, request) }
            "files.read" -> withAccess(app) { mode -> read(mode, app, request) }
            "files.write" -> withAccess(app) { mode -> write(mode, app, request) }
            "files.copy" -> withAccess(app) { mode -> copy(mode, app, request) }
            "files.move", "files.rename" -> withAccess(app) { mode -> move(mode, app, request) }
            "files.delete" -> withAccess(app) { mode -> delete(mode, app, request) }
            "files.share", "files.openWith" -> withAccess(app) { mode -> openWith(mode, app, request) }
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private enum class AccessMode { SAF, DOWNLOAD }

    private data class FileAccess(val mode: AccessMode, val treeUri: String? = null)

    private inline fun withAccess(
        app: app.deft.data.InstalledWebApp,
        block: (FileAccess) -> BridgeOutcome,
    ): BridgeOutcome {
        val tree = app.filesTreeUri
        if (!tree.isNullOrBlank()) {
            return block(FileAccess(AccessMode.SAF, tree))
        }
        return block(FileAccess(AccessMode.DOWNLOAD))
    }

    private suspend fun pickTree(webAppId: String, existing: String?): BridgeOutcome {
        val id = UUID.randomUUID().toString()
        val deferred = BridgePicks.registerTree(id)
        val intent = Intent(context, TreePickActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(TreePickActivity.EXTRA_REQUEST_ID, id)
            putExtra(TreePickActivity.EXTRA_WEB_APP_ID, webAppId)
            if (!existing.isNullOrBlank()) putExtra(TreePickActivity.EXTRA_INITIAL_URI, existing.toUri())
        }
        context.startActivity(intent)
        val uri = try {
            withTimeoutOrNull(BridgePicks.RESULT_TIMEOUT_MS) { deferred.await() }
        } finally {
            BridgePicks.forget(id)
        }
        return if (uri == null) {
            BridgeOutcome.Err(BridgeErrors.CANCELLED, "Folder picker cancelled")
        } else {
            BridgeOutcome.Ok(buildJsonObject { put("uri", JsonPrimitive(uri)) })
        }
    }

    /**
     * `files.saveAs {name, mime?, base64}` and `<a download>`. The user picks the
     * destination in the system dialog; nothing else on the device is touched.
     */
    private suspend fun saveAs(request: BridgeRequest): BridgeOutcome {
        val name = jsonArg(request.args, "name")?.replace(Regex("[\\r\\n/\\\\]"), "_")?.take(120)
            ?.ifBlank { null } ?: "download"
        val b64 = jsonArg(request.args, "base64") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "base64 required")
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "base64 is not valid")
        if (bytes.size > MAX_SAVE_BYTES) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "file larger than $MAX_SAVE_BYTES bytes")
        val id = UUID.randomUUID().toString()
        val staging = File(File(context.cacheDir, "saveas").apply { mkdirs() }, id)
        staging.writeBytes(bytes)
        val deferred = BridgePicks.registerSave(id)
        context.startActivity(
            Intent(context, SaveAsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(SaveAsActivity.EXTRA_REQUEST_ID, id)
                putExtra(SaveAsActivity.EXTRA_PATH, staging.absolutePath)
                putExtra(SaveAsActivity.EXTRA_NAME, name)
            },
        )
        val uri = try {
            withTimeoutOrNull(BridgePicks.RESULT_TIMEOUT_MS) { deferred.await() }
        } finally {
            BridgePicks.forget(id)
            staging.delete()
        }
        return if (uri == null) {
            BridgeOutcome.Err(BridgeErrors.CANCELLED, "Save cancelled")
        } else {
            BridgeOutcome.Ok(buildJsonObject { put("name", JsonPrimitive(name)) })
        }
    }

    private suspend fun pickMedia(request: BridgeRequest): BridgeOutcome {
        val id = UUID.randomUUID().toString()
        val deferred = BridgePicks.registerMedia(id)
        val mime = jsonArg(request.args, "mime") ?: "image/*"
        val intent = Intent(context, MediaPickActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MediaPickActivity.EXTRA_REQUEST_ID, id)
            putExtra(MediaPickActivity.EXTRA_MIME, mime)
        }
        context.startActivity(intent)
        val result = try {
            withTimeoutOrNull(BridgePicks.RESULT_TIMEOUT_MS) { deferred.await() }
        } finally {
            BridgePicks.forget(id)
        } ?: MediaPickResult(null, null)
        return if (result.uri == null) {
            BridgeOutcome.Err(BridgeErrors.CANCELLED, "Media picker cancelled")
        } else {
            BridgeOutcome.Ok(
                buildJsonObject {
                    put("uri", JsonPrimitive(result.uri))
                    put("mime", JsonPrimitive(result.mime ?: "application/octet-stream"))
                },
            )
        }
    }

    /** Without a picked SAF tree, path-based file methods reach Downloads only. */
    @Suppress("UNUSED_PARAMETER")
    private fun roots(webAppId: String): List<File> =
        listOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile)

    private fun resolveFile(raw: String, webAppId: String): File {
        val file = File(raw).canonicalFile
        val allowed = roots(webAppId)
        val ok = allowed.any { file.path == it.path || file.path.startsWith(it.path + "/") }
        if (!ok) throw SecurityException("path not in allowlist")
        return file
    }

    private fun resolveSaf(treeUri: String, path: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri.toUri()) ?: return null
        if (path.isBlank()) return root
        var current = root
        for (segment in path.split('/').filter { it.isNotBlank() }) {
            if (segment == "." || segment == "..") return null
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun resolveParentSaf(treeUri: String, path: String): Pair<DocumentFile, String>? {
        val normalized = path.trim('/')
        if (normalized.isEmpty()) return null
        val parts = normalized.split('/')
        val name = parts.last()
        val parentPath = parts.dropLast(1).joinToString("/")
        val parent = resolveSaf(treeUri, parentPath) ?: return null
        if (!parent.isDirectory) return null
        return parent to name
    }

    private fun listVolumes(access: FileAccess, app: app.deft.data.InstalledWebApp): BridgeOutcome {
        if (access.mode == AccessMode.SAF) {
            val root = DocumentFile.fromTreeUri(context, access.treeUri!!.toUri())
                ?: return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "invalid tree uri")
            return BridgeOutcome.Ok(
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("path", JsonPrimitive(""))
                            put("name", JsonPrimitive(root.name ?: "Folder"))
                            put("uri", JsonPrimitive(access.treeUri))
                        },
                    )
                },
            )
        }
        val arr = buildJsonArray {
            for (root in roots(app.id)) {
                add(
                    buildJsonObject {
                        put("path", JsonPrimitive(root.path))
                        put("name", JsonPrimitive(root.name.ifBlank { root.path }))
                    },
                )
            }
        }
        return BridgeOutcome.Ok(arr)
    }

    private fun listDir(access: FileAccess, app: app.deft.data.InstalledWebApp, request: BridgeRequest): BridgeOutcome {
        val path = jsonArg(request.args, "path") ?: ""
        if (path.isBlank() && access.mode != AccessMode.SAF) {
            val arr = buildJsonArray {
                for (root in roots(app.id)) {
                    add(
                        buildJsonObject {
                            put("name", JsonPrimitive(root.name.ifBlank { root.path }))
                            put("path", JsonPrimitive(root.path))
                            put("isDir", JsonPrimitive(true))
                            put("size", JsonPrimitive(0))
                        },
                    )
                }
            }
            return BridgeOutcome.Ok(arr)
        }
        if (access.mode == AccessMode.SAF) {
            val dir = resolveSaf(access.treeUri!!, path)
                ?: return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "path not in granted tree")
            if (!dir.isDirectory) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "not a directory")
            val childPathPrefix = if (path.isBlank()) "" else path.trimEnd('/') + "/"
            val arr = buildJsonArray {
                dir.listFiles()?.sortedBy { it.name?.lowercase() }?.forEach { child ->
                    val name = child.name ?: return@forEach
                    add(
                        buildJsonObject {
                            put("name", JsonPrimitive(name))
                            put("path", JsonPrimitive(childPathPrefix + name))
                            put("isDir", JsonPrimitive(child.isDirectory))
                            put("size", JsonPrimitive(if (child.isFile) child.length() else 0))
                        },
                    )
                }
            }
            return BridgeOutcome.Ok(arr)
        }
        val dir = runCatching { resolveFile(path, app.id) }.getOrElse {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, it.message ?: "path")
        }
        if (!dir.isDirectory) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "not a directory")
        val arr = buildJsonArray {
            dir.listFiles()?.sortedBy { it.name }?.forEach { child ->
                add(
                    buildJsonObject {
                        put("name", JsonPrimitive(child.name))
                        put("path", JsonPrimitive(child.path))
                        put("isDir", JsonPrimitive(child.isDirectory))
                        put("size", JsonPrimitive(if (child.isFile) child.length() else 0))
                    },
                )
            }
        }
        return BridgeOutcome.Ok(arr)
    }

    private fun stat(access: FileAccess, app: app.deft.data.InstalledWebApp, request: BridgeRequest): BridgeOutcome {
        val path = jsonArg(request.args, "path") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "path required")
        if (access.mode == AccessMode.SAF) {
            val doc = resolveSaf(access.treeUri!!, path)
                ?: return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "path not in granted tree")
            return BridgeOutcome.Ok(
                buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("exists", JsonPrimitive(doc.exists()))
                    put("isDir", JsonPrimitive(doc.isDirectory))
                    put("size", JsonPrimitive(if (doc.isFile) doc.length() else 0))
                },
            )
        }
        val file = runCatching { resolveFile(path, app.id) }.getOrElse {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, it.message ?: "path")
        }
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("path", JsonPrimitive(file.path))
                put("exists", JsonPrimitive(file.exists()))
                put("isDir", JsonPrimitive(file.isDirectory))
                put("size", JsonPrimitive(if (file.isFile) file.length() else 0))
            },
        )
    }

    private fun read(access: FileAccess, app: app.deft.data.InstalledWebApp, request: BridgeRequest): BridgeOutcome {
        val path = jsonArg(request.args, "path") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "path required")
        if (access.mode == AccessMode.SAF) {
            val doc = resolveSaf(access.treeUri!!, path)
                ?: return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "path not in granted tree")
            if (!doc.isFile) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "not a file")
            if (doc.length() > MAX_READ) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "file larger than 5MB")
            val bytes = context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
                ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "could not read file")
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return BridgeOutcome.Ok(
                buildJsonObject {
                    put("base64", JsonPrimitive(b64))
                    put("mime", JsonPrimitive(mimeOf(doc.name ?: "file")))
                },
            )
        }
        val file = runCatching { resolveFile(path, app.id) }.getOrElse {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, it.message ?: "path")
        }
        if (!file.isFile) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "not a file")
        if (file.length() > MAX_READ) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "file larger than 5MB")
        val bytes = file.readBytes()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("base64", JsonPrimitive(b64))
                put("mime", JsonPrimitive(mimeOf(file.name)))
            },
        )
    }

    private fun write(access: FileAccess, app: app.deft.data.InstalledWebApp, request: BridgeRequest): BridgeOutcome {
        val path = jsonArg(request.args, "path") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "path required")
        val b64 = jsonArg(request.args, "base64") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "base64 required")
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        if (bytes.size > MAX_READ) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "file larger than 5MB")
        }
        if (access.mode == AccessMode.SAF) {
            val parentInfo = resolveParentSaf(access.treeUri!!, path)
                ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "invalid path")
            val (parent, name) = parentInfo
            val mime = mimeOf(name)
            val existing = parent.findFile(name)
            val target = existing ?: parent.createFile(mime, name)
                ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "could not create file")
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
                ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "could not write file")
            return BridgeOutcome.Ok()
        }
        val file = runCatching { resolveFile(path, app.id) }.getOrElse {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, it.message ?: "path")
        }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return BridgeOutcome.Ok()
    }

    private fun copy(access: FileAccess, app: app.deft.data.InstalledWebApp, request: BridgeRequest): BridgeOutcome {
        val from = jsonArg(request.args, "from") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "from required")
        val to = jsonArg(request.args, "to") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "to required")
        if (access.mode == AccessMode.SAF) {
            val src = resolveSaf(access.treeUri!!, from)
                ?: return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "from not in granted tree")
            val dstInfo = resolveParentSaf(access.treeUri, to)
                ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "invalid to path")
            val (parent, name) = dstInfo
            val mime = mimeOf(name)
            val out = parent.createFile(mime, name)
                ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "could not create destination")
            context.contentResolver.openInputStream(src.uri)?.use { input ->
                context.contentResolver.openOutputStream(out.uri)?.use { output ->
                    input.copyTo(output)
                }
            } ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "copy failed")
            return BridgeOutcome.Ok()
        }
        val src = runCatching { resolveFile(from, app.id) }.getOrElse {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, it.message ?: "path")
        }
        val dst = runCatching { resolveFile(to, app.id) }.getOrElse {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, it.message ?: "path")
        }
        src.copyTo(dst, overwrite = true)
        return BridgeOutcome.Ok()
    }

    private fun move(access: FileAccess, app: app.deft.data.InstalledWebApp, request: BridgeRequest): BridgeOutcome {
        val from = jsonArg(request.args, "from") ?: jsonArg(request.args, "path")
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "from required")
        val to = jsonArg(request.args, "to") ?: jsonArg(request.args, "newName")?.let { name ->
            val parent = from.substringBeforeLast('/', "")
            if (parent.isEmpty()) name else "$parent/$name"
        } ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "to required")
        if (access.mode == AccessMode.SAF) {
            val src = resolveSaf(access.treeUri!!, from)
                ?: return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "from not in granted tree")
            val dstInfo = resolveParentSaf(access.treeUri, to)
                ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "invalid to path")
            val (parent, name) = dstInfo
            if (src.parentFile?.uri == parent.uri && src.renameTo(name)) {
                return BridgeOutcome.Ok()
            }
            return copy(access, app, request).let { outcome ->
                if (outcome is BridgeOutcome.Ok) delete(access, app, BridgeRequest(app.id, app.origin, "files.delete", buildJsonObject { put("path", JsonPrimitive(from)) }))
                else outcome
            }
        }
        val src = runCatching { resolveFile(from, app.id) }.getOrElse {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, it.message ?: "path")
        }
        val dst = runCatching { resolveFile(to, app.id) }.getOrElse {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, it.message ?: "path")
        }
        src.renameTo(dst)
        return BridgeOutcome.Ok()
    }

    private fun delete(access: FileAccess, app: app.deft.data.InstalledWebApp, request: BridgeRequest): BridgeOutcome {
        val path = jsonArg(request.args, "path") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "path required")
        if (access.mode == AccessMode.SAF) {
            val doc = resolveSaf(access.treeUri!!, path)
                ?: return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "path not in granted tree")
            doc.delete()
            return BridgeOutcome.Ok()
        }
        val file = runCatching { resolveFile(path, app.id) }.getOrElse {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, it.message ?: "path")
        }
        file.deleteRecursively()
        return BridgeOutcome.Ok()
    }

    private fun openWith(access: FileAccess, app: app.deft.data.InstalledWebApp, request: BridgeRequest): BridgeOutcome {
        val path = jsonArg(request.args, "path") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "path required")
        val send = request.method == "files.share"
        if (access.mode == AccessMode.SAF) {
            val doc = resolveSaf(access.treeUri!!, path)
                ?: return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "path not in granted tree")
            val mime = mimeOf(doc.name ?: "file")
            val intent = Intent(if (send) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                if (send) {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, doc.uri)
                } else {
                    setDataAndType(doc.uri, mime)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, doc.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return BridgeOutcome.Ok()
        }
        val file = runCatching { resolveFile(path, app.id) }.getOrElse {
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, it.message ?: "path")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = mimeOf(file.name)
        val intent = Intent(if (send) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
            if (send) {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
            } else {
                setDataAndType(uri, mime)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return BridgeOutcome.Ok()
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
    }

    companion object {
        private const val MAX_SAVE_BYTES = 5 * 1024 * 1024
        const val MAX_READ = 5L * 1024 * 1024
    }
}
