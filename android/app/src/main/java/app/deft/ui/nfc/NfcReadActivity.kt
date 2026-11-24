package app.deft.ui.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.deft.DeftApp
import app.deft.bridge.BridgeAccessoryPicks
import app.deft.ui.theme.applyDeftChrome
import app.deft.bridge.NfcReadResult

class NfcReadActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var requestId: String = ""
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDeftChrome(DeftApp.instance.configStore.config.value)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null || !nfcAdapter!!.isEnabled) {
            finishWith(null)
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        adapter.enableReaderMode(
            this,
            { tag -> onTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V,
            null,
        )
    }

    override fun onDestroy() {
        if (!completed && requestId.isNotBlank()) {
            BridgeAccessoryPicks.completeNfc(requestId, null)
        }
        nfcAdapter?.disableReaderMode(this)
        super.onDestroy()
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    private fun onTag(tag: Tag) {
        if (completed) return
        val idHex = tag.id.joinToString("") { b -> "%02x".format(b) }
        val ndefText = readNdefText(tag)
        finishWith(NfcReadResult(idHex, ndefText))
    }

    private fun readNdefText(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        return runCatching {
            ndef.connect()
            val message = ndef.ndefMessage ?: return@runCatching null
            for (record in message.records) {
                if (record.tnf == android.nfc.NdefRecord.TNF_WELL_KNOWN &&
                    record.type.contentEquals(android.nfc.NdefRecord.RTD_TEXT)
                ) {
                    val payload = record.payload
                    if (payload.isEmpty()) continue
                    val langLen = payload[0].toInt() and 0x3F
                    return@runCatching String(payload, 1 + langLen, payload.size - 1 - langLen, Charsets.UTF_8)
                }
            }
            null
        }.getOrNull().also { runCatching { ndef.close() } }
    }

    private fun finishWith(result: NfcReadResult?) {
        if (completed) return
        completed = true
        if (requestId.isNotBlank()) {
            BridgeAccessoryPicks.completeNfc(requestId, result)
        }
        setResult(if (result != null) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "requestId"
    }
}
