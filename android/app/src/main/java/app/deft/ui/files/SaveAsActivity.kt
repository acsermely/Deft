package app.deft.ui.files

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.deft.bridge.BridgePicks
import java.io.File

/**
 * System "Save as" for `files.saveAs` and `<a download>` (PARITY A2). The bytes wait in a private
 * cache file; the user picks where they go, and only that one document is written.
 */
class SaveAsActivity : ComponentActivity() {
    private val create = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val source = intent.getStringExtra(EXTRA_PATH)?.let { File(it) }
        val saved = if (uri != null && source != null && source.isFile && source.canonicalPath.startsWith(stagingRoot())) {
            runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } }
                true
            }.getOrDefault(false)
        } else {
            false
        }
        source?.delete()
        BridgePicks.completeSave(requestId, if (saved) uri.toString() else null)
        finish()
    }

    private fun stagingRoot(): String = File(cacheDir, "saveas").canonicalPath

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) create.launch(intent.getStringExtra(EXTRA_NAME) ?: "download")
    }

    companion object {
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
    }
}
