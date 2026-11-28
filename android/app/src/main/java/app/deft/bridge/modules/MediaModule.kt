package app.deft.bridge.modules

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import androidx.core.content.ContextCompat
import app.deft.bridge.OsPermissions
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.HostSurfaces
import app.deft.bridge.MediaSpec
import app.deft.bridge.jsonArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

// DEVICE-UNCHECKED (media library queries): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a gallery sample lists all photos with thumbnails.
// Remove this note once checked.
/** `media.*`: photos, videos and audio on the phone, only while the app is on screen. The session methods go to [MediaSessionModule]. */
class MediaModule(private val context: Context) : BridgeModule {
    /** The media session shares the `media` prefix and works off screen too. */
    private val session = MediaSessionModule(context)

    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        if (request.method in MediaSessionModule.METHODS) return session.invoke(request)
        HostSurfaces.forRequest(request)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN)
        return withContext(Dispatchers.IO) {
            when (request.method) {
                "media.query" -> query(request, request.args)
                "media.thumbnail" -> thumbnail(request, request.args)
                "media.read" -> read(request, request.args)
                else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
            }
        }
    }

    private suspend fun query(request: BridgeRequest, args: JsonObject): BridgeOutcome {
        val q = MediaSpec.parseQuery(
            jsonArg(args, "kind"),
            jsonArg(args, "since"),
            jsonArg(args, "limit"),
            jsonArg(args, "offset"),
        ) ?: return invalid(KIND_MESSAGE)
        accessOrAsk(q.kind, request)?.let { return it }
        // Newest first; the optional `since` keeps items added at or after that time.
        val bundle = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaSpec.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, q.limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, q.offset)
            q.sinceMs?.let {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaSpec.DATE_ADDED} >= ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf((it / 1000).toString()))
            }
        }
        val items = buildJsonArray {
            context.contentResolver.query(collection(q.kind), MediaSpec.columns(q.kind).toTypedArray(), bundle, null)?.use { cursor ->
                while (cursor.moveToNext()) add(row(cursor))
            }
        }
        return BridgeOutcome.Ok(items)
    }

    private suspend fun thumbnail(request: BridgeRequest, args: JsonObject): BridgeOutcome {
        val kind = kindArg(args) ?: return invalid(KIND_MESSAGE)
        val id = MediaSpec.parseId(jsonArg(args, "id")) ?: return invalid("id from media.query required")
        val size = MediaSpec.parseThumbSize(jsonArg(args, "size")) ?: return invalid("size must be 64 to 512")
        accessOrAsk(kind, request)?.let { return it }
        val uri = ContentUris.withAppendedId(collection(kind), id)
        val bitmap = try {
            context.contentResolver.loadThumbnail(uri, Size(size, size), null)
        } catch (_: IOException) {
            return invalid("No item with that id")
        }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        bitmap.recycle()
        return BridgeOutcome.Ok(buildJsonObject { put("base64", JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP))) })
    }

    private suspend fun read(request: BridgeRequest, args: JsonObject): BridgeOutcome {
        val kind = kindArg(args) ?: return invalid(KIND_MESSAGE)
        val id = MediaSpec.parseId(jsonArg(args, "id")) ?: return invalid("id from media.query required")
        accessOrAsk(kind, request)?.let { return it }
        val uri = ContentUris.withAppendedId(collection(kind), id)
        val resolver = context.contentResolver
        val (size, mime) = resolver.query(uri, arrayOf(MediaSpec.SIZE, MediaSpec.MIME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) Pair(cursor.number(MediaSpec.SIZE) ?: 0L, cursor.text(MediaSpec.MIME)) else null
        } ?: return invalid("No item with that id")
        if (size > MediaSpec.MAX_READ_BYTES) return invalid("too large; use files.* for big files")
        val stream: InputStream = runCatching { resolver.openInputStream(uri) }.getOrNull()
            ?: return invalid("could not read the item")
        val bytes = stream.use { readCapped(it, MediaSpec.MAX_READ_BYTES) }
            ?: return invalid("too large; use files.* for big files")
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("base64", JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)))
                put("mime", JsonPrimitive(mime ?: "application/octet-stream"))
            },
        )
    }

    private fun row(cursor: Cursor): JsonObject = buildJsonObject {
        put("id", JsonPrimitive((cursor.number(MediaSpec.ID) ?: 0L).toString()))
        put("name", JsonPrimitive(cursor.text(MediaSpec.NAME).orEmpty()))
        put("mime", JsonPrimitive(cursor.text(MediaSpec.MIME) ?: "application/octet-stream"))
        put("size", JsonPrimitive(cursor.number(MediaSpec.SIZE) ?: 0L))
        cursor.number(MediaSpec.WIDTH)?.let { put("width", JsonPrimitive(it)) }
        cursor.number(MediaSpec.HEIGHT)?.let { put("height", JsonPrimitive(it)) }
        cursor.number(MediaSpec.DURATION)?.let { put("durationMs", JsonPrimitive(it)) }
        cursor.number(MediaSpec.DATE_TAKEN)?.takeIf { it > 0 }?.let { put("dateTaken", JsonPrimitive(it)) }
        (cursor.text(MediaSpec.BUCKET) ?: cursor.text(MediaSpec.ALBUM))?.let { put("album", JsonPrimitive(it)) }
    }

    private fun Cursor.text(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.number(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }

    private fun kindArg(args: JsonObject): String? = jsonArg(args, "kind")?.takeIf { it in MediaSpec.KINDS }

    private fun collection(kind: String): Uri = when (kind) {
        "images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    /**
     * Android 13 and newer have one permission per media type. Android 14 can also grant only the
     * photos and videos the user picked; the system then limits what the queries return.
     */
    private fun hasAccess(kind: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        val own = when (kind) {
            "images" -> Manifest.permission.READ_MEDIA_IMAGES
            "video" -> Manifest.permission.READ_MEDIA_VIDEO
            else -> Manifest.permission.READ_MEDIA_AUDIO
        }
        if (granted(own)) return true
        return kind != "audio" && Build.VERSION.SDK_INT >= 34 &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    /** Null when the kind may be read; otherwise asks for its media permission on screen, or returns the error. */
    private suspend fun accessOrAsk(kind: String, request: BridgeRequest): BridgeOutcome? {
        if (hasAccess(kind)) return null
        return OsPermissions.ensure(context, request.webAppId, request.background, listOf(mediaPermission(kind)), "Allow photos, videos and music")
    }

    private fun mediaPermission(kind: String): String = when {
        Build.VERSION.SDK_INT < 33 -> Manifest.permission.READ_EXTERNAL_STORAGE
        kind == "images" -> Manifest.permission.READ_MEDIA_IMAGES
        kind == "video" -> Manifest.permission.READ_MEDIA_VIDEO
        else -> Manifest.permission.READ_MEDIA_AUDIO
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun invalid(message: String) = BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, message)

    /** Reads the stream, or gives null as soon as it holds more than [limit] bytes. */
    private fun readCapped(stream: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val KIND_MESSAGE = "kind must be images, video or audio; limit 1 to 500; since and offset whole numbers"
    }
}
