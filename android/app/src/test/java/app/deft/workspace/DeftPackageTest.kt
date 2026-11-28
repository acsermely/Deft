package app.deft.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
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
    fun rejectsPackagesMadeBeforeTheRename() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val old = pack(keys, mapOf("manifest.json" to """{"fl":1,"id":"notes","name":"Notes"}"""), legacy = true)
        assertFalse(DeftPackage.looksSigned(old))
        assertTrue(runCatching { DeftPackage.verify(old) }.isFailure)
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
        // A 2 MiB + 1 byte binary is over the normal binary cap; only a declared and granted app may ship it.
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
                zip.putNextEntry(ZipEntry(if (legacy) "fl-package.json" else DeftPackage.PACKAGE_FILE))
                zip.write(doc.toString().toByteArray())
                zip.closeEntry()
            }
            out.toByteArray()
        }
    }

    // A plain zip takes the same path as a signed package, minus the signature.

    private fun plainZip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                entries.forEach { (path, body) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(body)
                    zip.closeEntry()
                }
            }
            out.toByteArray()
        }

    private fun snapshot(dir: java.io.File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to sha(it.readBytes()) }

    private val plainManifest = """{"deft":1,"id":"notes","name":"Notes","version":"1.2.0"}""".toByteArray()

    @Test
    fun unsignedZipIsReadWithThePathRules() {
        val ok = DeftPackage.readUnsigned(
            plainZip(
                "manifest.json" to plainManifest,
                "index.html" to "<p>hi</p>".toByteArray(),
                "Evil.EXE" to "x".toByteArray(),
            ),
        )
        assertFalse(ok.signed)
        assertEquals("notes", ok.id)
        assertEquals("1.2.0", ok.version)
        assertEquals(setOf("manifest.json", "index.html"), ok.files.keys)
        assertEquals(1, ok.skipped.size)
        assertTrue(ok.skipped.single().startsWith("Evil.EXE"))
    }

    @Test
    fun unsignedBadZipLeavesTheAppUnchanged() {
        val root = java.nio.file.Files.createTempDirectory("deft-h5").toFile()
        try {
            val dir = java.io.File(root, "notes").apply { mkdirs() }
            java.io.File(dir, "manifest.json").writeBytes(plainManifest)
            java.io.File(dir, "index.html").writeText("<p>old</p>")
            val before = snapshot(dir)
            val staging = java.io.File(root, "staging")
            val tooBig = ByteArray((DeftPaths.active.maxTextBytes + 1).toInt()) { 'a'.code.toByte() }
            val bad = listOf(
                // zip-slip after a good entry: the old direct import had already written index.html by then.
                plainZip("index.html" to "<p>new</p>".toByteArray(), "../escape.txt" to "x".toByteArray(), "manifest.json" to plainManifest),
                plainZip(*(listOf("manifest.json" to plainManifest) + (0..DeftPaths.active.maxFiles).map { "f$it.js" to "x".toByteArray() }).toTypedArray()),
                plainZip("index.html" to "<p>no manifest</p>".toByteArray()),
                plainZip("manifest.json" to plainManifest, "main.js" to tooBig),
                "not a zip at all".toByteArray(),
                plainZip(),
            )
            for ((i, zip) in bad.withIndex()) {
                assertThrows("bad zip $i", IllegalArgumentException::class.java) {
                    DeftPackage.swapInto(DeftPackage.readUnsigned(zip), dir, staging)
                }
                assertEquals("bad zip $i", before, snapshot(dir))
            }
            // A failure after staging, before the swap, also leaves the old app and no staging copy.
            val good = DeftPackage.readUnsigned(plainZip("manifest.json" to plainManifest, "index.html" to "<p>new</p>".toByteArray()))
            assertThrows(IllegalStateException::class.java) {
                DeftPackage.swapInto(good, dir, staging) { throw IllegalStateException("lint said no") }
            }
            assertEquals(before, snapshot(dir))
            assertTrue(staging.listFiles().orEmpty().isEmpty())
            // The good one swaps in completely.
            DeftPackage.swapInto(good, dir, staging)
            assertEquals("<p>new</p>", java.io.File(dir, "index.html").readText())
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    /** A pack hash lives in manifest.json, which the signed file list covers. */
    @Test
    fun assetPackHashesAreCoveredBySignature() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val withPack = """{"deft":1,"id":"notes","name":"Notes","version":"1.0.0","assetPacks":[""" +
            """{"name":"maps","url":"https://cdn.example.com/m@1.0.0/m.zip","sha256":"${"a".repeat(64)}","size":10}]}"""
        val good = pack(keys, mapOf("manifest.json" to withPack))
        val verified = DeftPackage.verify(good)
        assertTrue(verified.files.getValue("manifest.json").decodeToString().contains("a".repeat(64)))
        // Swapping the pack hash in manifest.json after signing breaks the package.
        val swapped = rezip(good, "manifest.json", withPack.replace("a".repeat(64), "b".repeat(64)).toByteArray())
        val err = assertThrows(IllegalArgumentException::class.java) { DeftPackage.verify(swapped) }
        assertTrue(err.message.orEmpty(), err.message.orEmpty().contains("hash mismatch manifest.json"))
    }

    private fun rezip(bytes: ByteArray, path: String, body: ByteArray): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name] = z.readBytes()
            }
        }
        entries[path] = body
        return ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { z ->
                entries.forEach { (name, data) ->
                    z.putNextEntry(ZipEntry(name))
                    z.write(data)
                    z.closeEntry()
                }
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
