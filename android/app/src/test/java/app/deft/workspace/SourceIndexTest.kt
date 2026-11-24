package app.deft.workspace

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64

class SourceIndexTest {
    @Test
    fun rejectsRollbackAndExpiry() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val fresh = index(keys, serial = 2, expires = "2099-01-01T00:00:00Z")
        val checked = SourceIndex.verify(fresh, previousSerial = 1, now = Instant.parse("2026-01-01T00:00:00Z"))
        assertEquals(2L, checked.serial)
        assertEquals(listOf("notes.deftpkg"), checked.packageNames)
        assertThrows(IllegalArgumentException::class.java) {
            SourceIndex.verify(fresh, previousSerial = 3, now = Instant.parse("2026-01-01T00:00:00Z"))
        }
        val old = index(keys, serial = 4, expires = "2020-01-01T00:00:00Z")
        assertThrows(IllegalArgumentException::class.java) {
            SourceIndex.verify(old, now = Instant.parse("2026-01-01T00:00:00Z"))
        }
    }

    private fun index(keys: java.security.KeyPair, serial: Long, expires: String): String {
        val body = buildJsonObject {
            put("format", 1)
            put("serial", serial)
            put("expires", expires)
            put("packages", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("skip-me"))
                add(buildJsonObject { put("name", "notes.deftpkg") })
            })
        }
        val payload = DeftJcs.of(body)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keys.private)
        signer.update(payload.toByteArray())
        val root = buildJsonObject {
            put("body", body)
            put("publicKey", Base64.getEncoder().encodeToString(keys.public.encoded))
            put("signature", Base64.getEncoder().encodeToString(signer.sign()))
        }
        return root.toString()
    }
}
