package app.deft.bridge.modules

/**
 * NFC Forum payload formats for well-known Text and URI records (PARITY B2). Pure byte logic so it
 * can be tested off-device; building [android.nfc.NdefRecord]s happens in [NfcModule].
 */
object NdefPayloads {
    /** NFC Forum URI Record Type Definition, identifier codes 0x00..0x23. */
    val URI_PREFIXES = listOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://", "ftp://",
        "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:",
        "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
        "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    fun encodeText(text: String, lang: String = "en"): ByteArray {
        val langBytes = lang.take(63).toByteArray(Charsets.US_ASCII)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        return byteArrayOf(langBytes.size.toByte()) + langBytes + textBytes
    }

    /** Returns (lang, text) or null when the payload is not a Text record. */
    fun decodeText(payload: ByteArray): Pair<String, String>? {
        if (payload.isEmpty()) return null
        val status = payload[0].toInt() and 0xFF
        val langLen = status and 0x3F
        if (1 + langLen > payload.size) return null
        val utf16 = status and 0x80 != 0
        val lang = String(payload, 1, langLen, Charsets.US_ASCII)
        val text = String(payload, 1 + langLen, payload.size - 1 - langLen, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
        return lang to text
    }

    fun encodeUri(uri: String): ByteArray {
        // Longest matching prefix wins (index 0 is the empty prefix).
        val code = URI_PREFIXES.indices.drop(1).filter { uri.startsWith(URI_PREFIXES[it]) }
            .maxByOrNull { URI_PREFIXES[it].length } ?: 0
        return byteArrayOf(code.toByte()) + uri.removePrefix(URI_PREFIXES[code]).toByteArray(Charsets.UTF_8)
    }

    fun decodeUri(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val prefix = URI_PREFIXES.getOrNull(payload[0].toInt() and 0xFF) ?: return null
        return prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
    }
}
