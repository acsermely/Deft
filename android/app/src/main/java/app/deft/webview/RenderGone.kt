package app.deft.webview

import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView

/**
 * One policy for `WebViewClient.onRenderProcessGone`. The renderer is shared by every WebView in
 * the process; a client that returns false makes Android kill the whole app when it dies. Each
 * client returns true and, on the main thread, calls [dispose] on the dead view; it must not
 * call any other method on that view; removing its bridge listener and document-start script just before [dispose] is the only exception (wrap it in runCatching).
 */
object RenderGone {
    /** Logs why the renderer went away. */
    fun log(tag: String, what: String, detail: RenderProcessGoneDetail) {
        Log.w(tag, "renderer gone for $what: crashed=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}")
    }

    /** Removes the dead [view] from its parent and destroys it; safe to call once per view. */
    fun dispose(view: WebView) {
        runCatching {
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
    }
}
