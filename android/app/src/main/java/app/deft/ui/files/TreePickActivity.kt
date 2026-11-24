package app.deft.ui.files

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.deft.DeftApp
import app.deft.bridge.BridgePicks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TreePickActivity : ComponentActivity() {
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val webAppId = intent.getStringExtra(EXTRA_WEB_APP_ID).orEmpty()
        if (uri != null && webAppId.isNotBlank()) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    DeftApp.instance.repository.setFilesTreeUri(webAppId, uri.toString())
                }
                BridgePicks.completeTree(requestId, uri.toString())
                finish()
            }
        } else {
            BridgePicks.completeTree(requestId, null)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickTree.launch(initialTreeUri())
    }

    @Suppress("DEPRECATION")
    private fun initialTreeUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_INITIAL_URI, Uri::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_INITIAL_URI)
        }

    companion object {
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_WEB_APP_ID = "webAppId"
        const val EXTRA_INITIAL_URI = "initialUri"
    }
}
