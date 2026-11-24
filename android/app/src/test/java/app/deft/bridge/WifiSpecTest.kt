package app.deft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiSpecTest {
    @Test
    fun ssidLosesTheQuotesAndUnknownIsNull() {
        assertEquals("Home", WifiSpec.ssid("\"Home\""))
        assertEquals("My \"Net\"", WifiSpec.ssid("\"My \"Net\"\""))
        assertEquals("Plain", WifiSpec.ssid("Plain"))
        assertNull(WifiSpec.ssid("<unknown ssid>"))
        assertNull(WifiSpec.ssid(""))
        assertNull(WifiSpec.ssid(null))
        assertNull(WifiSpec.ssid("\"\""))
    }

    @Test
    fun bssidHidesTheAndroidPlaceholder() {
        assertEquals("aa:bb:cc:dd:ee:ff", WifiSpec.bssid("aa:bb:cc:dd:ee:ff"))
        assertNull(WifiSpec.bssid("02:00:00:00:00:00"))
        assertNull(WifiSpec.bssid(" "))
        assertNull(WifiSpec.bssid(null))
    }

    @Test
    fun securityFromCapabilities() {
        assertEquals("open", WifiSpec.security("[ESS]"))
        assertEquals("open", WifiSpec.security(""))
        assertEquals("open", WifiSpec.security(null))
        assertEquals("wep", WifiSpec.security("[WEP][ESS]"))
        assertEquals("wpa", WifiSpec.security("[WPA-PSK-TKIP][ESS]"))
        assertEquals("wpa2", WifiSpec.security("[WPA2-PSK-CCMP][ESS]"))
        assertEquals("wpa2", WifiSpec.security("[WPA-PSK-TKIP][WPA2-PSK-CCMP][ESS]"))
        assertEquals("wpa3", WifiSpec.security("[SAE][ESS]"))
        assertEquals("wpa2-wpa3", WifiSpec.security("[RSN-PSK+SAE-CCMP][ESS]"))
        assertEquals("owe", WifiSpec.security("[RSN-OWE-CCMP][ESS]"))
        assertEquals("enterprise", WifiSpec.security("[WPA2-EAP-CCMP][ESS]"))
    }

    @Test
    fun frequencyMapsToBand() {
        assertEquals("2.4 GHz", WifiSpec.band(2412))
        assertEquals("2.4 GHz", WifiSpec.band(2484))
        assertEquals("5 GHz", WifiSpec.band(5180))
        assertEquals("6 GHz", WifiSpec.band(5955))
        assertNull(WifiSpec.band(0))
        assertNull(WifiSpec.band(3000))
        assertNull(WifiSpec.band(7500))
    }

    @Test
    fun ssidIsOneToThirtyTwoBytes() {
        assertTrue(WifiSpec.ssidValid("a"))
        assertTrue(WifiSpec.ssidValid("a".repeat(32)))
        assertFalse(WifiSpec.ssidValid(""))
        assertFalse(WifiSpec.ssidValid("a".repeat(33)))
        // Four bytes per character: 9 characters is 36 bytes.
        assertFalse(WifiSpec.ssidValid("😀".repeat(9)))
        assertTrue(WifiSpec.ssidValid("😀".repeat(8)))
    }

    @Test
    fun passphraseIsEightToSixtyThreePrintableAscii() {
        assertTrue(WifiSpec.passphraseValid("12345678"))
        assertTrue(WifiSpec.passphraseValid("a".repeat(63)))
        assertTrue(WifiSpec.passphraseValid("pass word!~"))
        assertFalse(WifiSpec.passphraseValid("1234567"))
        assertFalse(WifiSpec.passphraseValid("a".repeat(64)))
        assertFalse(WifiSpec.passphraseValid("tab\tinside"))
        assertFalse(WifiSpec.passphraseValid("pássword"))
    }
}
