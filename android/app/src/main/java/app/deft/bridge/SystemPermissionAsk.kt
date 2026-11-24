package app.deft.bridge

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Android's runtime permission dialog for one screen ([HostSurface.requestPermissions]): turns the result launcher's
 * callback into a suspend call. One request at a time across screens ([HostSurfaces.dialogSlot]). Main thread only.
 */
class SystemPermissionAsk(private val timeoutMs: Long = OsPermissions.SYSTEM_TIMEOUT_MS) {
    private var pending: List<String> = emptyList()
    private var answer: ((RuntimePermissions.Answer) -> Unit)? = null

    /**
     * Shows the dialog through [launch]. Null when another request is open; DENIED when it cannot be shown
     * or times out.
     */
    suspend fun request(permissions: List<String>, launch: (Array<String>) -> Unit): RuntimePermissions.Answer? =
        HostSurfaces.dialogSlot.tryRun {
            withTimeoutOrNull(timeoutMs) { ask(permissions, launch) } ?: RuntimePermissions.Answer.DENIED
        }

    private suspend fun ask(permissions: List<String>, launch: (Array<String>) -> Unit): RuntimePermissions.Answer =
        suspendCancellableCoroutine { cont ->
            val done: (RuntimePermissions.Answer) -> Unit = { result ->
                answer = null
                pending = emptyList()
                if (cont.isActive) cont.resume(result)
            }
            answer = done
            pending = permissions
            if (!runCatching { launch(permissions.toTypedArray()) }.isSuccess) {
                done(RuntimePermissions.Answer.DENIED)
            }
            cont.invokeOnCancellation { answer = null; pending = emptyList() }
        }

    /** The launcher's result. [showsRationale] is read after the answer: see [RuntimePermissions.answerFor]. */
    fun onResult(isGranted: (String) -> Boolean, showsRationale: (String) -> Boolean) {
        val names = pending
        pending = emptyList()
        answer?.invoke(RuntimePermissions.answerFor(names.all(isGranted), names.any(showsRationale)))
    }

    fun onResult(activity: Activity) = onResult(
        isGranted = { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED },
        showsRationale = { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) },
    )

    /** The screen is gone: a waiting request is refused now, not left to its timeout. */
    fun cancel() {
        answer?.invoke(RuntimePermissions.Answer.DENIED)
    }
}
