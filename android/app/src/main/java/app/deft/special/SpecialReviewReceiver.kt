package app.deft.special

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.deft.catalog.Capability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Daily review alarm and the reminder's Keep / Remove buttons. Not exported. */
class SpecialReviewReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_CHECK -> SpecialReviewRunner.runCheck(context)
                    ACTION_KEEP, ACTION_REMOVE -> {
                        val webAppId = intent.getStringExtra(EXTRA_WEB_APP_ID) ?: return@launch
                        val cap = intent.getStringExtra(EXTRA_CAPABILITY)?.let { Capability.fromId(it) } ?: return@launch
                        if (action == ACTION_KEEP) {
                            SpecialReviewRunner.keep(context, webAppId, cap)
                        } else {
                            SpecialReviewRunner.remove(context, webAppId, cap)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CHECK = "app.deft.special.CHECK"
        const val ACTION_KEEP = "app.deft.special.KEEP"
        const val ACTION_REMOVE = "app.deft.special.REMOVE"
        const val EXTRA_WEB_APP_ID = "webAppId"
        const val EXTRA_CAPABILITY = "capability"
    }
}
