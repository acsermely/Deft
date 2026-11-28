package app.deft.ui.host

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag

// DEVICE-UNCHECKED (NFC write, read-only and tech transceive): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: write a URL tag; read a transit card's ISO-DEP response.
// Remove this note once checked.
/** NFC reader mode for the activity in front. It only works while [activity] is resumed. */
internal object NfcReader {
    /** False when the device has no NFC or it is off. */
    fun available(activity: Activity): Boolean = NfcAdapter.getDefaultAdapter(activity)?.isEnabled == true

    fun enable(activity: Activity, onTag: (Tag) -> Unit) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        runCatching {
            adapter.enableReaderMode(
                activity,
                { tag -> onTag(tag) },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NFC_BARCODE,
                null,
            )
        }
    }

    fun disable(activity: Activity) {
        NfcAdapter.getDefaultAdapter(activity)?.let { runCatching { it.disableReaderMode(activity) } }
    }
}
