package app.deft.push

import android.content.Context
import android.util.Log
import app.deft.bridge.PushSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.MessagingReceiver

// DEVICE-UNCHECKED (UnifiedPush): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: an ntfy-backed sample receives a push with the app closed.
// Remove this note once checked.
/**
 * Broadcasts from the UnifiedPush distributor. The connector library matches each
 * broadcast's token to an instance (= webAppId) and ignores unknown tokens, so another app
 * cannot fake a message without the distributor's token.
 */
class PushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        val previous = PushRegistry.onEndpoint(context, instance, endpoint)
        if (previous != null && previous != endpoint) {
            async { PushDelivery.notifyApp(instance, PushSpec.EVENT_ENDPOINT_CHANGED, PushSpec.endpointPayload(endpoint)) }
        }
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        PushRegistry.onFailed(context, instance)
    }

    override fun onUnregistered(context: Context, instance: String) {
        if (PushRegistry.onUnregistered(context, instance)) {
            async { PushDelivery.notifyApp(instance, PushSpec.EVENT_UNREGISTERED, "{}") }
        }
    }

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        val app = context.applicationContext
        async { PushDelivery.deliver(app, instance, message) }
    }

    private fun async(block: suspend () -> Unit) {
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "push handling failed: ${e.javaClass.simpleName}")
            } finally {
                done.finish()
            }
        }
    }

    private companion object {
        const val TAG = "PushReceiver"
    }
}
