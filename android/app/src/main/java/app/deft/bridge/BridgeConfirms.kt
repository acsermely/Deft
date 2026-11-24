package app.deft.bridge

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

enum class ConfirmChoice {
    ALLOW,
    DENY,
}

object BridgeConfirms {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ConfirmChoice>>()
    private val owners = ConcurrentHashMap<String, String>()

    /** [owner] is the web app id the dialog belongs to, when there is one (see [pendingFor]). */
    fun register(id: String, owner: String? = null): CompletableDeferred<ConfirmChoice> {
        val d = CompletableDeferred<ConfirmChoice>()
        if (owner != null) owners[id] = owner
        pending[id] = d
        return d
    }

    /** W5: a confirm dialog of app [owner] waits for the user now (that app's tool call timeout does not run meanwhile). */
    fun pendingFor(owner: String): Boolean = pending.keys.any { owners[it] == owner }

    fun anyPending(): Boolean = pending.isNotEmpty()

    fun complete(id: String, choice: ConfirmChoice) {
        owners.remove(id)
        pending.remove(id)?.complete(choice)
    }

    fun complete(id: String, allowed: Boolean) {
        complete(id, if (allowed) ConfirmChoice.ALLOW else ConfirmChoice.DENY)
    }
}
