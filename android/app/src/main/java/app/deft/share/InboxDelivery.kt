package app.deft.share

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import app.deft.DeftApp
import app.deft.bridge.BridgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Copies incoming `content://` streams into an app's [ShareInbox] and delivers the event.
 * Used by share-in ([ShareToActivity]) and "Open with" (HandlerPickerActivity),
 * so both keep the same rules: content URIs that pass [accepts] only, [ShareInbox.MAX_ITEMS] items,
 * [ShareInbox.MAX_ITEM_BYTES] each.
 */
object InboxDelivery {
    suspend fun copy(context: Context, webAppId: String, uris: List<Uri>, fallbackMime: String): List<ShareInbox.Item> {
        val inbox = ShareInbox.forFilesDir(context.filesDir)
        val now = System.currentTimeMillis()
        val resolver = context.contentResolver
        return withContext(Dispatchers.IO) {
            inbox.sweep(now)
            uris.take(ShareInbox.MAX_ITEMS).mapNotNull { uri ->
                // Only streams the sender granted to us. A file:// URI or the launcher's own provider
                // could name its private files, and contacts, SMS or media URIs would be read with
                // the launcher's permissions ([UriIntake]).
                if (!accepts(context, uri)) return@mapNotNull null
                runCatching {
                    resolver.openInputStream(uri)?.use { input ->
                        inbox.store(webAppId, displayName(context, uri), resolver.getType(uri) ?: fallbackMime, input, now)
                    }
                }.getOrNull()
            }
        }
    }

    /** [UriIntake.allowed] with the facts Android gives about [uri]'s provider and our grants. */
    fun accepts(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val provider = uri.authority?.let { authority ->
            runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.resolveContentProvider(authority, PackageManager.GET_URI_PERMISSION_PATTERNS)
            }.getOrNull()
        }?.let { info ->
            UriIntake.Provider(
                packageName = info.packageName,
                exported = info.exported,
                readProtected = info.readPermission != null || info.pathPermissions?.any { it.readPermission != null } == true,
                system = info.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 } ?: true,
            )
        }
        val granted = runCatching {
            context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return UriIntake.allowed(uri.scheme, provider, context.packageName, granted)
    }

    /** The plain error when [copy] stored nothing because [webAppId]'s inbox is full, else null. */
    fun fullMessage(context: Context, webAppId: String, appName: String): String? =
        if (ShareInbox.forFilesDir(context.filesDir).full(webAppId, System.currentTimeMillis())) {
            "$appName has too many files it has not opened yet. Open it first, then try again."
        } else {
            null
        }

    fun itemsJson(items: List<ShareInbox.Item>): JsonArray = buildJsonArray {
        items.forEach { item ->
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(item.id))
                    put("name", JsonPrimitive(item.name))
                    put("mime", JsonPrimitive(item.mime))
                    put("size", JsonPrimitive(item.size))
                },
            )
        }
    }

    /** Live if the app is open, else queued until it subscribes. */
    suspend fun emit(webAppId: String, event: String, payloadJson: String) {
        val service = BridgeService.instanceOrNull()
        if (service != null) {
            service.emit(webAppId, event, payloadJson)
        } else {
            DeftApp.instance.repository.enqueueEvent(webAppId, event, payloadJson)
        }
    }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
}
