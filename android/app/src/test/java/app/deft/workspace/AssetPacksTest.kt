package app.deft.workspace

import app.deft.registry.DeftAssetPack
import app.deft.registry.DeftManifestParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Asset packs are parsed, verified, installed and served same-origin. */
class AssetPacksTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { z ->
                entries.forEach { (path, body) ->
                    z.putNextEntry(ZipEntry(path))
                    z.write(body)
                    z.closeEntry()
                }
            }
            out.toByteArray()
        }

    private fun hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun packFor(name: String, bytes: ByteArray) =
        DeftAssetPack(name, "https://cdn.example.com/maps@1.0.0/$name.zip", hex(bytes), bytes.size.toLong())

    private fun parse(json: String) = DeftManifestParser.parseAssetPacks(Json.parseToJsonElement(json))

    @Test
    fun manifestRulesAreTheLintRules() {
        val ok = parse("""[{"name":"europe","url":"https://cdn.example.com/maps@1.0.0/europe.zip","sha256":"${"a".repeat(64)}","size":1000}]""")
        assertEquals(listOf(DeftAssetPack("europe", "https://cdn.example.com/maps@1.0.0/europe.zip", "a".repeat(64), 1000)), ok)
        assertEquals(emptyList<DeftAssetPack>(), parse("null"))
        val bad = listOf(
            """{}""",
            """[{"name":"Europe","url":"https://cdn.example.com/e.zip","sha256":"${"a".repeat(64)}","size":1}]""",
            """[{"name":"e","url":"http://cdn.example.com/e.zip","sha256":"${"a".repeat(64)}","size":1}]""",
            """[{"name":"e","url":"https://cdn.example.com/e.zip?x=1","sha256":"${"a".repeat(64)}","size":1}]""",
            """[{"name":"e","url":"https://cdn.example.com/e.tar","sha256":"${"a".repeat(64)}","size":1}]""",
            """[{"name":"e","url":"https://cdn.example.com/e.zip","sha256":"${"A".repeat(64)}","size":1}]""",
            """[{"name":"e","url":"https://cdn.example.com/e.zip","sha256":"${"a".repeat(64)}","size":0}]""",
            """[{"name":"e","url":"https://cdn.example.com/e.zip","sha256":"${"a".repeat(64)}","size":"1"}]""",
            """[{"name":"e","url":"https://cdn.example.com/e.zip","sha256":"${"a".repeat(64)}","size":1},""" +
                """{"name":"e","url":"https://cdn.example.com/f.zip","sha256":"${"a".repeat(64)}","size":1}]""",
            "[" + (0..DeftManifestParser.MAX_ASSET_PACKS).joinToString(",") {
                """{"name":"p$it","url":"https://cdn.example.com/p$it.zip","sha256":"${"a".repeat(64)}","size":1}"""
            } + "]",
        )
        for (json in bad) assertThrows(json, IllegalArgumentException::class.java) { parse(json) }
    }

    @Test
    fun receiveChecksSizeAndHash() {
        val bytes = zip("tiles/0.png" to byteArrayOf(1, 2, 3))
        val pack = packFor("europe", bytes)
        val dest = File.createTempFile("pack", ".zip")
        try {
            AssetPacks.receive(ByteArrayInputStream(bytes), dest, pack)
            assertTrue(dest.readBytes().contentEquals(bytes))
            assertThrows(IllegalArgumentException::class.java) {
                AssetPacks.receive(ByteArrayInputStream(bytes), dest, pack.copy(sha256 = "0".repeat(64)))
            }
            assertThrows(IllegalArgumentException::class.java) {
                AssetPacks.receive(ByteArrayInputStream(bytes + 0), dest, pack)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AssetPacks.receive(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)), dest, pack)
            }
        } finally {
            dest.delete()
        }
    }

    @Test
    fun installedPacksAreServedOnlyWhenDeclaredAndCurrent() {
        val root = Files.createTempDirectory("packs").toFile()
        try {
            val v1 = zip("tiles/0/0.png" to byteArrayOf(9), "index.json" to "{}".toByteArray())
            val pack = packFor("europe", v1)
            val file = File(root, "v1.zip").apply { writeBytes(v1) }
            AssetPacks.install(root, pack, file, maxExtractedBytes = 1_000_000, maxFileBytes = 1_000_000)
            file.delete()
            assertTrue(AssetPacks.installed(root, pack))
            assertEquals(emptyList<DeftAssetPack>(), AssetPacks.missing(root, listOf(pack)))
            val served = AssetPacks.serve(root, listOf(pack), "europe/tiles/0/0.png")!!
            assertTrue(served.readBytes().contentEquals(byteArrayOf(9)))
            // Not declared, another version, the marker itself, traversal, or a missing file: nothing.
            assertNull(AssetPacks.serve(root, emptyList(), "europe/tiles/0/0.png"))
            assertNull(AssetPacks.serve(root, listOf(pack.copy(sha256 = "b".repeat(64))), "europe/tiles/0/0.png"))
            assertNull(AssetPacks.serve(root, listOf(pack), "europe/${AssetPacks.MARKER}"))
            assertNull(AssetPacks.serve(root, listOf(pack), "europe/../europe/index.json"))
            assertNull(AssetPacks.serve(root, listOf(pack), "europe/nope.png"))
            // Sweep drops packs the manifest no longer lists.
            AssetPacks.sweep(root, emptyList())
            assertFalse(AssetPacks.installed(root, pack))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun badPackLeavesTheInstalledVersion() {
        val root = Files.createTempDirectory("packs").toFile()
        try {
            val good = zip("a.json" to "1".toByteArray())
            val pack = packFor("data", good)
            AssetPacks.install(root, pack, File(root, "g.zip").apply { writeBytes(good) }, 1_000_000, 1_000_000)
            val bad = listOf(
                zip("main.js" to "alert(1)".toByteArray()),
                zip("page.html" to "<p>".toByteArray()),
                zip("../escape.json" to "1".toByteArray()),
                zip("Upper.json" to "1".toByteArray()),
                zip("big.bin" to ByteArray(2_000)),
                zip(),
                "not a zip".toByteArray(),
            )
            for ((i, bytes) in bad.withIndex()) {
                val next = packFor("data", bytes)
                assertThrows("bad pack $i", IllegalArgumentException::class.java) {
                    AssetPacks.install(root, next, File(root, "b$i.zip").apply { writeBytes(bytes) }, 1_000_000, 1_000)
                }
                assertTrue("bad pack $i", AssetPacks.installed(root, pack))
                assertEquals("1", File(root, "data/a.json").readText())
            }
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".staging") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun packsCountAgainstThePackageCap() {
        val caps = DeftPaths.Caps(packageBytes = 10_000, binaryBytes = 1_000)
        val pack = DeftAssetPack("p", "https://cdn.example.com/p@1.0.0/p.zip", "a".repeat(64), 6_000)
        AssetPacks.requireQuota(listOf(pack), appBytes = 4_000, caps = caps)
        assertThrows(IllegalArgumentException::class.java) {
            AssetPacks.requireQuota(listOf(pack), appBytes = 4_001, caps = caps)
        }
    }
}
