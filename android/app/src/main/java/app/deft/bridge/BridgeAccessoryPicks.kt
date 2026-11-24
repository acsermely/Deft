package app.deft.bridge

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

data class NfcReadResult(val idHex: String, val ndefText: String?)

object BridgeAccessoryPicks {
    private val nfcPending = ConcurrentHashMap<String, CompletableDeferred<NfcReadResult?>>()
    private val usbPending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val bleEnablePending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun registerNfc(id: String): CompletableDeferred<NfcReadResult?> {
        val d = CompletableDeferred<NfcReadResult?>()
        nfcPending[id] = d
        return d
    }

    fun completeNfc(id: String, result: NfcReadResult?) {
        nfcPending.remove(id)?.complete(result)
    }

    fun registerUsbPermission(id: String): CompletableDeferred<Boolean> {
        val d = CompletableDeferred<Boolean>()
        usbPending[id] = d
        return d
    }

    fun completeUsbPermission(id: String, granted: Boolean) {
        usbPending.remove(id)?.complete(granted)
    }

    fun registerBleEnable(id: String): CompletableDeferred<Boolean> {
        val d = CompletableDeferred<Boolean>()
        bleEnablePending[id] = d
        return d
    }

    fun completeBleEnable(id: String, granted: Boolean) {
        bleEnablePending.remove(id)?.complete(granted)
    }
}
