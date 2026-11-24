package app.deft.ui.host

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag

/** PARITY B2: NFC reader mode for the activity in front. It only works while [activity] is resumed. */
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
