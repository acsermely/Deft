package app.deft.bridge

/** Pure rules for `wifi.*` (PARITY G6): kept free of Android types so they can be unit tested. */
object WifiSpec {
    /** What Android reports for a hidden network or when the SSID is not readable. */
    const val UNKNOWN_SSID = "<unknown ssid>"

    /** What Android reports for the BSSID when location is off or the phone is not connected. */
    private const val HIDDEN_BSSID = "02:00:00:00:00:00"

    /** Android wraps an SSID in double quotes; hidden or unreadable names become null. */
    fun ssid(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == UNKNOWN_SSID) return null
        val unquoted = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
        return unquoted.ifEmpty { null }
    }

    fun bssid(raw: String?): String? = raw?.takeIf { it.isNotBlank() && it != HIDDEN_BSSID }

    /** The security type from a scan result's capability string, such as `[WPA2-PSK-CCMP][ESS]`. */
    fun security(capabilities: String?): String {
        val caps = capabilities.orEmpty().uppercase()
        return when {
            "EAP" in caps -> "enterprise"
            "SAE" in caps && "PSK" in caps -> "wpa2-wpa3"
            "SAE" in caps -> "wpa3"
            "OWE" in caps -> "owe"
            "RSN-PSK" in caps || "WPA2" in caps -> "wpa2"
            "WPA-PSK" in caps -> "wpa"
            "WEP" in caps -> "wep"
            else -> "open"
        }
    }

    /** The Wi-Fi band for a centre frequency in MHz, or null when it is not a Wi-Fi band. */
    fun band(frequencyMhz: Int): String? = when (frequencyMhz) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5900 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> null
    }

    /** An SSID is 1 to 32 bytes of UTF-8. */
    fun ssidValid(ssid: String): Boolean = ssid.isNotEmpty() && ssid.toByteArray(Charsets.UTF_8).size <= 32

    /** A WPA2 or WPA3 passphrase is 8 to 63 printable ASCII characters. */
    fun passphraseValid(passphrase: String): Boolean =
        passphrase.length in 8..63 && passphrase.all { it.code in 0x20..0x7E }
}
