package app.deft.nfc

import android.content.ComponentName
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import app.deft.DeftApp

// DEVICE-UNCHECKED (NFC host card emulation): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: the phone emulates a test tag to a second NFC phone or reader.
// Remove this note once checked.
/**
 * NFC card emulation for the one web app holding `nfc.hce`. Each command APDU is sent
 * to that app as `nfc.hceCommand`; its `nfc.hceRespond` becomes the response. If the app is not
 * open, or does not answer in [HceRouting.RESPONSE_TIMEOUT_MS], the reader gets 6F00.
 */
class DeftHostApduService : RoutedApduService() {
    override val router: ApduRouter get() = ROUTER

    companion object {
        private val ROUTER = ApduRouter("nfc.hceCommand", "nfc.hceDeactivated") {
            DeftApp.instance.repository.hceHolderId()
        }

        /** `nfc.hceRespond`: only the app that received the command may answer it. */
        fun respond(webAppId: String, requestId: String, response: ByteArray): Boolean =
            ROUTER.respond(webAppId, requestId, response)

        /** Review: drops the `nfc.hce` holder's AIDs (no holder any more, or a new one). */
        fun clearAids(context: Context) {
            runCatching {
                val adapter = NfcAdapter.getDefaultAdapter(context) ?: return
                CardEmulation.getInstance(adapter)
                    .removeAidsForService(ComponentName(context, DeftHostApduService::class.java), CardEmulation.CATEGORY_OTHER)
            }
        }
    }
}
