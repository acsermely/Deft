package app.deft.ui.files

import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.deft.bridge.BridgePicks

class MediaPickActivity : ComponentActivity() {
    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (uri == null) {
            BridgePicks.completeMedia(requestId, null, null)
        } else {
            val mime = contentResolver.getType(uri) ?: guessMime(uri)
            BridgePicks.completeMedia(requestId, uri.toString(), mime)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mime = intent.getStringExtra(EXTRA_MIME) ?: "image/*"
        pick.launch(mime)
    }

    private fun guessMime(uri: Uri): String {
        val ext = uri.lastPathSegment?.substringAfterLast('.', "").orEmpty()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: "application/octet-stream"
    }

    companion object {
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_MIME = "mime"
    }
}
