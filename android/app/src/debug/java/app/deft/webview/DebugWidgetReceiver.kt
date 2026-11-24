package app.deft.webview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.deft.DeftApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only: registers my-apps pushed into files/deft/local and consents every capability they declare,
 * and replaces the home canvas index.html from a file under files/.
 * adb shell am broadcast -n app.deft.debug/app.deft.webview.DebugWidgetReceiver --es slugs a,b --es canvas w8/index.html
 */
class DebugWidgetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slugs = intent.getStringExtra("slugs")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val canvas = intent.getStringExtra("canvas")
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val app = DeftApp.instance
                for (slug in slugs) {
                    val caps = app.myApps.readManifest(slug).capabilities
                    val row = app.repository.applyWorkspaceConsent(slug, caps, caps)
                    Log.i("DebugWidget", "registered $slug as ${row.id} origin ${row.origin}")
                }
                if (canvas != null) {
                    app.homeCanvas.write("index.html", File(context.filesDir, canvas).readText())
                    Log.i("DebugWidget", "canvas written from $canvas")
                }
                if (slugs.isEmpty() && canvas == null) app.homeCanvas.refresh()
            } catch (e: Throwable) {
                Log.e("DebugWidget", "failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
