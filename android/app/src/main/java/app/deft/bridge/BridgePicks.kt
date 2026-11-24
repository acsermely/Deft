package app.deft.bridge

import app.deft.share.ShareInbox
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

data class MediaPickResult(val uri: String?, val mime: String?)

/** What an outbound intent brought back (PARITY E2): only the inbox items, never the sender's URIs. */
data class IntentReply(
    val ok: Boolean,
    val data: String?,
    val items: List<ShareInbox.Item>,
    /** Set when the chooser could not be opened at all; the call fails with this instead of "cancelled". */
    val error: BridgeOutcome.Err? = null,
)

object BridgePicks {
    /** Review: a picker or result activity that never answers ends the call as cancelled after this long. */
    const val RESULT_TIMEOUT_MS = 10 * 60_000L

    private val treePending = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val mediaPending = ConcurrentHashMap<String, CompletableDeferred<MediaPickResult>>()
    private val savePending = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val intentPending = ConcurrentHashMap<String, CompletableDeferred<IntentReply>>()
    private val authPending = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    fun registerIntent(id: String): CompletableDeferred<IntentReply> {
        val d = CompletableDeferred<IntentReply>()
        intentPending[id] = d
        return d
    }

    fun completeIntent(id: String, reply: IntentReply) {
        intentPending.remove(id)?.complete(reply)
    }

    /** PARITY E3: keyed by the sign-in nonce. Completes with the full redirect URL, or null when cancelled. */
    fun registerAuth(nonce: String): CompletableDeferred<String?> {
        val d = CompletableDeferred<String?>()
        authPending[nonce] = d
        return d
    }

    fun completeAuth(nonce: String, url: String?) {
        authPending.remove(nonce)?.complete(url)
    }

    fun registerSave(id: String): CompletableDeferred<String?> {
        val d = CompletableDeferred<String?>()
        savePending[id] = d
        return d
    }

    fun completeSave(id: String, uri: String?) {
        savePending.remove(id)?.complete(uri)
    }

    fun registerTree(id: String): CompletableDeferred<String?> {
        val d = CompletableDeferred<String?>()
        treePending[id] = d
        return d
    }

    fun completeTree(id: String, uri: String?) {
        treePending.remove(id)?.complete(uri)
    }

    fun registerMedia(id: String): CompletableDeferred<MediaPickResult> {
        val d = CompletableDeferred<MediaPickResult>()
        mediaPending[id] = d
        return d
    }

    fun completeMedia(id: String, uri: String?, mime: String?) {
        mediaPending.remove(id)?.complete(MediaPickResult(uri, mime))
    }

    /** Review: drops a waiting request (timed out or abandoned); a late answer is then ignored. */
    fun forget(id: String) {
        treePending.remove(id)
        mediaPending.remove(id)
        savePending.remove(id)
        intentPending.remove(id)
    }
}
