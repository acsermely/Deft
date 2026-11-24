package app.deft.webview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.deft.workspace.BundledApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only (src/debug): runs AppVerifier on a bundled app id, or on a folder under the app's files dir.
 * adb shell am broadcast -n app.deft/.webview.DebugVerifyReceiver --es id wasm-add
 * adb shell am broadcast -n ... --es dir tmp-broken   (files/tmp-broken)
 * Timings go to logcat tag AppVerifier; the result goes to tag DebugVerify.
 */
class DebugVerifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id")
        val dirName = intent.getStringExtra("dir")
        val dir = if (dirName != null) File(context.filesDir, dirName) else BundledApps.dir(context, id ?: return)
        val slug = id ?: dirName ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val out = AppVerifier.verify(context, slug, dir, "./index.html", origin = BundledApps.origin(slug))
                Log.i("DebugVerify", "RESULT $slug\n$out")
            } catch (e: Throwable) {
                Log.e("DebugVerify", "failed $slug", e)
            } finally {
                pending.finish()
            }
        }
    }
}
