package app.deft.bridge

/**
 * Watches one page's bridge traffic (agent `apps.preview` records what the app did during a run). Called from
 * the bridge's threads; implementations must be thread-safe and cheap. Never changes the traffic.
 */
interface BridgeTap {
    fun call(method: String)
    fun result(method: String, ok: Boolean, code: String?)
    fun subscribe(event: String)
    fun event(event: String, payloadJson: String)
}
