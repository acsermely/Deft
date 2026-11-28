package app.deft.data

import app.deft.DeftApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// DEVICE-UNCHECKED (per-app network log and "Block network" switch): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: an app's fetches, images and net.* sockets show on its dashboard card; with the switch on, nothing leaves.
// Remove this note once checked.
/**
 * Writes network log rows off the caller's thread (WebView's `shouldInterceptRequest`
 * runs on a network thread and must not wait for the database), one at a time and in order.
 */
object NetworkLogWriter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun record(row: NetworkLogEntity?) {
        row ?: return
        scope.launch { runCatching { DeftApp.instance.repository.networkLog.record(row) } }
    }
}
