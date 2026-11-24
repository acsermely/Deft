package app.deft.sms

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.deft.DeftApp
import app.deft.bridge.BridgeService
import app.deft.ui.host.WebViewHostActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SendToActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data: Uri? = intent.data
        val to = data?.schemeSpecificPart?.substringBefore('?').orEmpty()
            .removePrefix("//")
        val body = data?.getQueryParameter("body")
            ?: intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        BridgeService.compose(this, to, body)
        lifecycleScope.launch {
            val holder = withContext(Dispatchers.IO) {
                DeftApp.instance.repository.smsHolderId()
            }
            if (holder != null) {
                startActivity(WebViewHostActivity.intent(this@SendToActivity, holder))
            }
            finish()
        }
    }
}
