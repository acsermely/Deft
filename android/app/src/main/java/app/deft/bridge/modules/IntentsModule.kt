package app.deft.bridge.modules

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgePicks
import app.deft.bridge.BridgeRequest
import app.deft.bridge.IntentReply
import app.deft.bridge.IntentSpec
import app.deft.bridge.OsPermissions
import app.deft.bridge.jsonArg
import app.deft.share.ShareInbox
import app.deft.ui.intents.IntentResultActivity
import android.util.Base64
import android.content.ClipData
import androidx.core.content.FileProvider
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

// DEVICE-UNCHECKED (outbound intents and results): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a sample opens the system camera via IMAGE_CAPTURE and gets the photo back.
// Remove this note once checked.
class IntentsModule(
    private val context: Context,
) : BridgeModule {
    /** Read once: whether the launcher's manifest asks for CAMERA. Without that request Android lets camera intents through. */
    private val cameraDeclared: Boolean by lazy {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.CAMERA) == true
    }

    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        if (request.method == "intents.readShare") return readShare(request)
        if (request.method == "intents.share") return share(request)
        if (request.method == "intents.start") return start(request, forResult = false)
        if (request.method == "intents.startForResult") return start(request, forResult = true)
        if (request.method != "intents.open") {
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
        val url = jsonArg(request.args, "url")
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "url required")
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "invalid url")
        if (scheme !in ALLOWED_SCHEMES) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "scheme not allowed")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // The launcher's own browser-role entry is left out, so a link never loops back.
        context.startActivity(
            app.deft.roles.RoleComponents.excludeSelf(
                context,
                Intent.createChooser(intent, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ),
        )
        return BridgeOutcome.Ok()
    }

    /**
     * `navigator.share` / `intents.share {title?, text?, url?, files?: [{name, mime, base64}]}`.
     * Always a system chooser; the app never learns which app received it.
     */
    private fun share(request: BridgeRequest): BridgeOutcome {
        val title = jsonArg(request.args, "title")
        val text = listOfNotNull(jsonArg(request.args, "text"), jsonArg(request.args, "url"))
            .filter { it.isNotBlank() }.joinToString("\n")
        val files = (request.args["files"] as? JsonArray).orEmpty()
        if (text.isBlank() && files.isEmpty()) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "nothing to share")
        val outDir = File(context.cacheDir, "shareout/${UUID.randomUUID()}").apply { mkdirs() }
        // Old shares are only needed until the receiving app has read them.
        File(context.cacheDir, "shareout").listFiles()?.filter {
            it != outDir && System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000
        }?.forEach { it.deleteRecursively() }
        val uris = ArrayList<Uri>()
        val mimes = mutableSetOf<String>()
        for ((index, el) in files.withIndex()) {
            val obj = el as? JsonObject ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "files must be objects")
            val b64 = (obj["base64"] as? JsonPrimitive)?.contentOrNull
                ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "file $index has no base64")
            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "file $index is not valid base64")
            if (bytes.size > MAX_SHARE_FILE) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "file $index is too large")
            val name = ((obj["name"] as? JsonPrimitive)?.contentOrNull ?: "file-$index")
                .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "file-$index" }
            val mime = (obj["mime"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.matches(Regex("^[a-z0-9.+-]+/[a-z0-9.+*-]+$")) } ?: "application/octet-stream"
            val out = File(outDir, "$index-$name").apply { writeBytes(bytes) }
            uris += FileProvider.getUriForFile(context, "${context.packageName}.files", out)
            mimes += mime
        }
        val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = when {
                uris.isEmpty() -> "text/plain"
                mimes.size == 1 -> mimes.first()
                mimes.map { it.substringBefore('/') }.toSet().size == 1 -> mimes.first().substringBefore('/') + "/*"
                else -> "*/*"
            }
            if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
            title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            when (uris.size) {
                0 -> Unit
                1 -> putExtra(Intent.EXTRA_STREAM, uris[0])
                else -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            if (uris.isNotEmpty()) {
                clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(
            Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        return BridgeOutcome.Ok()
    }

    /**
     * `intents.start` / `intents.startForResult {action, type?, data?, extras?}`. The args are
     * checked by [IntentSpec] first. The system chooser always shows, so the app never learns which app ran.
     */
    private suspend fun start(request: BridgeRequest, forResult: Boolean): BridgeOutcome {
        val spec = IntentSpec.parse(request.args).getOrElse {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, it.message ?: "invalid intent")
        }
        val target = toIntent(spec)
        if (target.action == MediaStore.ACTION_IMAGE_CAPTURE || target.action == MediaStore.ACTION_VIDEO_CAPTURE) {
            // Without the CAMERA request in the manifest Android lets the intent through, so nothing to ask.
            if (cameraDeclared) {
                OsPermissions.ensure(context, request.webAppId, request.background, listOf(Manifest.permission.CAMERA), CAMERA_MISSING)?.let { return it }
            }
            cameraAppRefusal { context.packageManager.resolveActivity(target, PackageManager.MATCH_DEFAULT_ONLY) != null }
                ?.let { return it }
        }
        if (!forResult) {
            // Never the launcher's own browser-role entries.
            context.startActivity(
                app.deft.roles.RoleComponents.excludeSelf(
                    context,
                    Intent.createChooser(target, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ),
            )
            return BridgeOutcome.Ok()
        }
        val id = UUID.randomUUID().toString()
        val deferred = BridgePicks.registerIntent(id)
        context.startActivity(
            Intent(context, IntentResultActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(IntentResultActivity.EXTRA_REQUEST_ID, id)
                putExtra(IntentResultActivity.EXTRA_WEB_APP_ID, request.webAppId)
                putExtra(IntentResultActivity.EXTRA_TARGET, target)
            },
        )
        val reply = try {
            withTimeoutOrNull(BridgePicks.RESULT_TIMEOUT_MS) { deferred.await() }
        } finally {
            BridgePicks.forget(id)
        } ?: return BridgeOutcome.Err(BridgeErrors.CANCELLED, "No answer after 10 minutes")
        return intentOutcome(reply)
    }

    private fun toIntent(spec: IntentSpec.Spec): Intent {
        val uri = spec.data?.let { Uri.parse(it) }
        return Intent(spec.androidAction).apply {
            if (uri != null && spec.type != null) {
                setDataAndType(uri, spec.type)
            } else if (uri != null) {
                data = uri
            } else if (spec.type != null) {
                type = spec.type
            }
            spec.extras.forEach { (key, value) -> putExtra(key, value) }
        }
    }

    /** One shared item from an `intents.shareReceived` event, read once. */
    private fun readShare(request: BridgeRequest): BridgeOutcome {
        val id = jsonArg(request.args, "id")
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "id required")
        val (bytes, mime) = ShareInbox.forFilesDir(context.filesDir)
            .take(request.webAppId, id, System.currentTimeMillis())
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "unknown, expired or already read")
        return BridgeOutcome.Ok(
            buildJsonObject {
                put("mime", JsonPrimitive(mime))
                put("base64", JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)))
            },
        )
    }

    companion object {
        /** Maps an answered picker or result to the call's outcome: a launch failure is an error, a dismissal is "cancelled". */
        internal fun intentOutcome(reply: IntentReply): BridgeOutcome {
            reply.error?.let { return it }
            return BridgeOutcome.Ok(
                buildJsonObject {
                    put("resultCode", JsonPrimitive(if (reply.ok) "ok" else "cancelled"))
                    reply.data?.let { put("data", JsonPrimitive(it)) }
                    put(
                        "items",
                        buildJsonArray {
                            reply.items.forEach { item ->
                                add(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(item.id))
                                        put("name", JsonPrimitive(item.name))
                                        put("mime", JsonPrimitive(item.mime))
                                        put("size", JsonPrimitive(item.size))
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }

        /** The camera permission is checked first (see [OsPermissions.ensure]); this is the app check after it. */
        internal fun cameraAppRefusal(hasCameraApp: () -> Boolean): BridgeOutcome.Err? =
            if (hasCameraApp()) null else BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "No camera app is installed on this phone")

        internal const val CAMERA_MISSING = "Allow the camera for Deft"

        private const val MAX_SHARE_FILE = 5 * 1024 * 1024
        private val ALLOWED_SCHEMES = setOf("http", "https", "mailto", "geo", "tel", "sms")
    }
}
