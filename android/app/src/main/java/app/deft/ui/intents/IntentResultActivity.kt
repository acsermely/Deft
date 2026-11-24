package app.deft.ui.intents

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgePicks
import app.deft.bridge.IntentReply
import app.deft.roles.RoleComponents
import app.deft.share.InboxDelivery
import app.deft.share.ShareInbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Runs one outbound intent for `intents.startForResult` (PARITY E2) and hands back what it
 * returned. Content the other app grants us is copied into the app's [ShareInbox] before this
 * activity finishes, because the grant does not outlive it. Only inbox items go back to the app.
 */
class IntentResultActivity : ComponentActivity() {
    private val launch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val webAppId = intent.getStringExtra(EXTRA_WEB_APP_ID).orEmpty()
        val returned = result.data
        lifecycleScope.launch {
            val reply = withContext(Dispatchers.IO) {
                if (result.resultCode == RESULT_OK) collect(returned, webAppId) else IntentReply(false, null, emptyList())
            }
            BridgePicks.completeIntent(requestId, reply)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val target = IntentCompat.getParcelableExtra(intent, EXTRA_TARGET, Intent::class.java)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (target == null) {
            fail(requestId, BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "no intent"))
            return
        }
        // Always a chooser: the app never picks the receiving app.
        // PARITY G11: never the launcher's own browser-role entries.
        try {
            launch.launch(RoleComponents.excludeSelf(this, Intent.createChooser(target, null)))
        } catch (e: SecurityException) {
            // Fallback only: IntentsModule's pre-flight checks are the real guard. This catches a launch
            // Android refuses anyway (for example a revoked permission), reported as an error, not a cancel.
            Log.w(TAG, "intent launch refused", e)
            fail(requestId, BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Android would not open this for Deft"))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for intent", e)
            fail(requestId, BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "No app on this phone can do this"))
        }
    }

    private fun fail(requestId: String, error: BridgeOutcome.Err) {
        BridgePicks.completeIntent(requestId, IntentReply(false, null, emptyList(), error))
        finish()
    }

    /** Any failure while keeping what came back is an error reply to the app, never a crash of the launcher. */
    private fun collect(returned: Intent?, webAppId: String): IntentReply {
        if (returned == null) return IntentReply(true, null, emptyList())
        return try {
            collectItems(returned, webAppId)
        } catch (e: Exception) {
            Log.w(TAG, "could not keep the returned items", e)
            IntentReply(false, null, emptyList(), BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Could not keep what the app returned"))
        }
    }

    private fun collectItems(returned: Intent, webAppId: String): IntentReply {
        val inbox = ShareInbox.forFilesDir(filesDir)
        val now = System.currentTimeMillis()
        val items = mutableListOf<ShareInbox.Item>()
        val uris = buildList {
            returned.data?.let { add(it) }
            val clip = returned.clipData
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { add(it) }
        }.distinct().take(ShareInbox.MAX_ITEMS)
        for (uri in uris) {
            // Only streams the other app granted us. A file:// URI or our own provider would name a
            // private file; contacts or media URIs would be read with our permissions ([UriIntake]).
            if (!InboxDelivery.accepts(this, uri)) continue
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    inbox.store(webAppId, displayName(uri), contentResolver.getType(uri) ?: "application/octet-stream", input, now)
                }
            }.getOrNull()?.let { items += it }
        }
        // A camera given no output file returns a small thumbnail in the "data" extra.
        if (items.isEmpty()) {
            val thumb = returned.extras?.get("data") as? Bitmap
            if (thumb != null) {
                val jpeg = ByteArrayOutputStream().apply { thumb.compress(Bitmap.CompressFormat.JPEG, 90, this) }.toByteArray()
                inbox.store(webAppId, "photo.jpg", "image/jpeg", ByteArrayInputStream(jpeg), now)?.let { items += it }
            }
        }
        // Text the other app returned is passed on; URIs of files are not.
        val text = returned.data?.takeUnless { it.scheme == "content" || it.scheme == "file" }?.toString()
        return IntentReply(true, text, items)
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    companion object {
        private const val TAG = "IntentResult"
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_WEB_APP_ID = "webAppId"
        const val EXTRA_TARGET = "target"
    }
}
