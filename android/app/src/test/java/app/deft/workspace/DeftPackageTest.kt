package app.deft.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DeftPackageTest {
    @Test
    fun acceptsPackagesMadeBeforeTheRename() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val old = pack(keys, mapOf("manifest.json" to """{"fl":1,"id":"notes","name":"Notes"}"""), legacy = true)
        assertTrue(DeftPackage.looksSigned(old))
        assertEquals("notes", DeftPackage.verify(old).id)
    }

    @Test
    fun verifiesAndRejectsHostilePackages() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val good = pack(keys, mapOf("manifest.json" to """{"deft":1,"id":"notes","name":"Notes"}"""), extra = emptyMap())
        val verified = DeftPackage.verify(good)
        assertEquals("notes", verified.id)
        assertTrue(verified.files.containsKey("manifest.json"))

        assertThrows(IllegalArgumentException::class.java) {
            DeftPackage.verify(pack(keys, mapOf("manifest.json" to "x"), tamper = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeftPackage.verify(pack(keys, mapOf("manifest.json" to "x"), extra = mapOf("evil.txt" to "no")))
        }
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signedByOther = pack(other, mapOf("manifest.json" to "x"))
        assertThrows(IllegalArgumentException::class.java) {
            DeftPackage.requireSameKey(verified.publicKey, DeftPackage.verify(signedByOther).publicKey)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeftPackage.verify(slip())
        }
    }

    @Test
    fun pathRulesAndQuotasRunBeforeInstall() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val manifest = """{"deft":1,"id":"notes","name":"Notes","version":"1.0.0"}"""
        for (bad in listOf("Main.JS", ".hidden.js", "_deft/runtime.js", "evil.exe")) {
            assertThrows(bad, IllegalArgumentException::class.java) {
                DeftPackage.verify(pack(keys, mapOf("manifest.json" to manifest, bad to "x")))
            }
        }
        val tooBig = "a".repeat((DeftPaths.active.maxTextBytes + 1).toInt())
        assertThrows(IllegalArgumentException::class.java) {
            DeftPackage.verify(pack(keys, mapOf("manifest.json" to manifest, "main.js" to tooBig)))
        }
        val ok = DeftPackage.verify(pack(keys, mapOf("manifest.json" to manifest, "lib/util.js" to "u")))
        assertEquals("1.0.0", ok.version)
    }

    @Test
    fun versionsCompareAndDowngradesAreRejected() {
        assertTrue(DeftPackage.compareVersions("1.2.0", "1.10.0") < 0)
        assertTrue(DeftPackage.compareVersions("2.0.0", "1.99.99") > 0)
        assertTrue(DeftPackage.compareVersions("1.0.0-beta", "1.0.0") < 0)
        assertEquals(0, DeftPackage.compareVersions("1.0", "1.0.0"))
        assertTrue(DeftPackage.compareVersions("garbage", "0.0.1") < 0)
        DeftPackage.requireNotDowngrade(null, "0.1.0")
        DeftPackage.requireNotDowngrade("1.0.0", "1.0.0")
        DeftPackage.requireNotDowngrade("1.0.0", "1.0.1")
        assertThrows(IllegalArgumentException::class.java) { DeftPackage.requireNotDowngrade("1.2.0", "1.1.9") }
    }

    @Test
    fun largeStorageNeedsTheDeclarationAndTheGrant() {
        // PARITY G3: a 2 MiB + 1 byte binary is over the normal binary cap; only a declared and granted app may ship it.
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val manifest = """{"deft":1,"id":"notes","name":"Notes","version":"1.0.0"}"""
        val big = "a".repeat((DeftPaths.MAX_BINARY_BYTES + 1).toInt())
        val files = mapOf("manifest.json" to manifest, "art/map.png" to big)
        val declared = pack(keys, files, capabilities = listOf("storage.large"))
        val undeclared = pack(keys, files)
        val large = DeftPaths.largeCaps(freeBytes = 4L * 1024 * 1024 * 1024)
        val previous = DeftPaths.active
        DeftPaths.active = DeftPaths.defaultLimits()
        try {
            assertThrows(IllegalArgumentException::class.java) { DeftPackage.verify(declared) }
            assertThrows(IllegalArgumentException::class.java) { DeftPackage.verify(declared) { null } }
            assertThrows(IllegalArgumentException::class.java) {
                DeftPackage.verify(undeclared) { large }
            }
            val ok = DeftPackage.verify(declared) { appId -> if (appId == "notes") large else null }
            assertEquals(large, ok.caps)
            // The signed id is "notes"; a manifest.json that claims another id gets no larger caps.
            val spoof = pack(
                keys,
                mapOf("manifest.json" to """{"deft":1,"id":"other","version":"1.0.0"}""", "art/map.png" to big),
                capabilities = listOf("storage.large"),
            )
            assertThrows(IllegalArgumentException::class.java) { DeftPackage.verify(spoof) { large } }
        } finally {
            DeftPaths.active = previous
        }
    }

    private fun pack(
        keys: KeyPair,
        files: Map<String, String>,
        extra: Map<String, String> = emptyMap(),
        tamper: Boolean = false,
        capabilities: List<String> = emptyList(),
        legacy: Boolean = false,
    ): ByteArray {
        val listed = files.map { (path, text) ->
            val bytes = text.toByteArray()
            val body = if (tamper) bytes + "z".toByteArray() else bytes
            path to body
        }
        val manifest = buildJsonObject {
            put(if (legacy) "fl" else "deft", 1)
            put("id", "notes")
            if (capabilities.isNotEmpty()) {
                put("capabilities", buildJsonArray { capabilities.forEach { add(JsonPrimitive(it)) } })
            }
            put("files", buildJsonArray {
                listed.forEach { (path, body) ->
                    add(buildJsonObject {
                        put("path", path)
                        put("sha256", sha(if (tamper) files.getValue(path).toByteArray() else body))
                    })
                }
            })
        }
        val payload = DeftJcs.of(Json.parseToJsonElement(manifest.toString()))
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keys.private)
        signer.update(payload.toByteArray())
        val doc = buildJsonObject {
            put("manifest", Json.parseToJsonElement(manifest.toString()))
            put("publicKey", Base64.getEncoder().encodeToString(keys.public.encoded))
            put("signature", Base64.getEncoder().encodeToString(signer.sign()))
        }
        return ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                listed.forEach { (path, body) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(body)
                    zip.closeEntry()
                }
                extra.forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(if (legacy) DeftPackage.LEGACY_PACKAGE_FILE else DeftPackage.PACKAGE_FILE))
                zip.write(doc.toString().toByteArray())
                zip.closeEntry()
            }
            out.toByteArray()
        }
    }

    private fun slip(): ByteArray {
        return ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("../secret.txt"))
                zip.write("pwned".toByteArray())
                zip.closeEntry()
            }
            out.toByteArray()
        }
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
