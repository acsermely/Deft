package app.deft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdSpecTest {
    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun matchesHmacSha256Vector() {
        // Computed independently with HMAC-SHA256 (Python hmac), first 32 hex characters.
        assertEquals("88f975c1948a1b0db387d3e3a81e5071", DeviceIdSpec.derive(secret, "https://example.com"))
        assertEquals("b613679a0814d9ec772f95d778c35fc5", DeviceIdSpec.derive(ByteArray(32), ""))
    }

    @Test
    fun stableForTheSameSecretAndOrigin() {
        val first = DeviceIdSpec.derive(secret, "https://example.com")
        assertEquals(first, DeviceIdSpec.derive(secret.copyOf(), "https://example.com"))
        assertEquals(DeviceIdSpec.LENGTH, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun differsPerOrigin() {
        assertNotEquals(
            DeviceIdSpec.derive(secret, "https://example.com"),
            DeviceIdSpec.derive(secret, "https://other.example"),
        )
        assertNotEquals(
            DeviceIdSpec.derive(secret, "https://example.com"),
            DeviceIdSpec.derive(secret, "https://example.com:8443"),
        )
    }

    @Test
    fun differsPerLauncherSecret() {
        val other = ByteArray(32) { (it + 1).toByte() }
        assertNotEquals(
            DeviceIdSpec.derive(secret, "https://example.com"),
            DeviceIdSpec.derive(other, "https://example.com"),
        )
    }
}
