package app.deft.workspace

import app.deft.registry.DeftDependency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DepStoreTest {
    private val threeUrl = "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js"
    private val threeSri = "sha256-PmkKx9GAsKrfCJG+o57sZD4p4tPnXJmxholRhmX2m6Y="

    @Test
    fun storeNameIsAlgoAndHex() {
        val name = DepStore.storeName(threeSri)
        assertTrue(name.startsWith("sha256-3e690a"))
        assertEquals("sha256-".length + 64, name.length)
        assertEquals("deps/$name", DepStore.packagePath(threeSri))
        assertTrue(DepStore.isPackagePath("deps/$name"))
        assertFalse(DepStore.isPackagePath("deps/../$name"))
    }

    @Test
    fun sriMatchesKnownVector() {
        assertEquals(
            "sha384-OLBgp1GsljhM2TJ+sbHjaiH9txEUvgdDTAzHv2P24donTt6/529l+9Ua0vFImLlb",
            DepStore.sri("sha384", ByteArray(0)),
        )
    }

    @Test
    fun importMapMapsHostsAndAliasesForStoredFiles() {
        val store = Files.createTempDirectory("deps").toFile()
        try {
            File(store, DepStore.storeName(threeSri)).writeText("export {};")
            val deps = listOf(
                DeftDependency(threeUrl, threeSri, alias = "three"),
                DeftDependency("https://unpkg.com/missing@1.0.0/x.js", "sha256-" + "A".repeat(43) + "=", null),
            )
            val map = Json.parseToJsonElement(DepStore.importMap(store, deps)!!).jsonObject["imports"]!!.jsonObject
            assertEquals("/_deft/deps/cdn.jsdelivr.net/", map["https://cdn.jsdelivr.net/"]!!.jsonPrimitive.content)
            assertEquals(
                "/_deft/deps/cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js",
                map["three"]!!.jsonPrimitive.content,
            )
            assertFalse(map.containsKey("https://unpkg.com/"))
        } finally {
            store.deleteRecursively()
        }
    }

    @Test
    fun lookupServesOnlyDeclaredUrls() {
        val store = Files.createTempDirectory("deps").toFile()
        try {
            File(store, DepStore.storeName(threeSri)).writeText("export {};")
            val deps = listOf(DeftDependency(threeUrl, threeSri))
            val served = "cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js"
            assertTrue(DepStore.lookup(store, deps, served)!!.isFile)
            assertNull(DepStore.lookup(store, deps, "cdn.jsdelivr.net/npm/three@0.160.0/build/other.js"))
            assertNull(DepStore.lookup(store, emptyList(), served))
            assertNull(DepStore.lookup(store, listOf(DeftDependency(threeUrl, null)), served))
        } finally {
            store.deleteRecursively()
        }
    }

    @Test
    fun pinFillsOnlyMissingHashes() {
        val file = File.createTempFile("manifest", ".json")
        try {
            file.writeText(
                """
                {
                  "deft": 1,
                  "id": "game",
                  "start_url": "./index.html",
                  "dependencies": {
                    "$threeUrl": null,
                    "https://unpkg.com/a@1.0.0/a.js": { "integrity": null, "as": "a" },
                    "https://unpkg.com/b@1.0.0/b.js": "$threeSri"
                  }
                }
                """.trimIndent(),
            )
            val other = "sha384-" + "B".repeat(64)
            DepStore.pin(
                file,
                listOf(
                    DepStore.Fetched(threeUrl, threeSri),
                    DepStore.Fetched("https://unpkg.com/a@1.0.0/a.js", other),
                    DepStore.Fetched("https://unpkg.com/b@1.0.0/b.js", other),
                ),
            )
            val deps = DepStore.dependenciesIn(file).associateBy { it.url }
            assertEquals(threeSri, deps[threeUrl]?.integrity)
            assertEquals(other, deps["https://unpkg.com/a@1.0.0/a.js"]?.integrity)
            assertEquals("a", deps["https://unpkg.com/a@1.0.0/a.js"]?.alias)
            assertEquals(threeSri, deps["https://unpkg.com/b@1.0.0/b.js"]?.integrity)
            assertTrue(file.readText().contains("\"./index.html\""))
        } finally {
            file.delete()
        }
    }

    /** A hand-made module exporting add(a, b), the same bytes as samples/wasm-add/add.wasm. */
    private val addWasm = byteArrayOf(
        0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x07, 0x01, 0x60, 0x02, 0x7f, 0x7f, 0x01,
        0x7f, 0x03, 0x02, 0x01, 0x00, 0x07, 0x07, 0x01, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00, 0x0a, 0x09,
        0x01, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6a, 0x0b,
    )
    private val wasmUrl = "https://app.acsermely.hu/deps/add@1.0.0.wasm"

    @Test
    fun wasmDependencyIsVerifiedStoredAndServedOnlyWithTheGrant() {
        val store = Files.createTempDirectory("deps").toFile()
        try {
            val sri = DepStore.sri("sha384", addWasm)
            val wrong = DepStore.sri("sha384", addWasm + 0x00)
            // Bytes that do not match the pin are never stored.
            assertTrue(runCatching { DepStore.storeIn(store, wrong, addWasm) }.isFailure)
            assertFalse(DepStore.has(store, wrong))
            DepStore.storeIn(store, sri, addWasm)
            assertTrue(DepStore.has(store, sri))

            val deps = listOf(DeftDependency(wasmUrl, sri, null))
            val rest = wasmUrl.removePrefix("https://")
            val served = DepStore.serve(store, deps, rest, wasmAllowed = true)!!
            assertEquals("application/wasm", served.mime)
            assertTrue(served.file.readBytes().contentEquals(addWasm))
            // Without the wasm grant, and for a URL the app does not declare, nothing is served.
            assertNull(DepStore.serve(store, deps, rest, wasmAllowed = false))
            assertNull(DepStore.serve(store, deps, "app.acsermely.hu/deps/other@1.0.0.wasm", wasmAllowed = true))
            assertNull(DepStore.serve(store, deps, "app.acsermely.hu/deps/../add@1.0.0.wasm", wasmAllowed = true))
            // A pinned URL whose bytes are not stored yet is not served either.
            val unstored = listOf(DeftDependency(wasmUrl, wrong, null))
            assertNull(DepStore.serve(store, unstored, rest, wasmAllowed = true))
        } finally {
            store.deleteRecursively()
        }
    }

    @Test
    fun wasmDependencyMustBePinned() {
        assertTrue(DepStore.isWasm(DeftDependency(wasmUrl, null, null)))
        assertFalse(DepStore.isWasm(DeftDependency(threeUrl, null, null)))
        val thrown = runCatching { DepStore.requirePinnedWasm(listOf(DeftDependency(wasmUrl, null, null))) }.exceptionOrNull()
        assertTrue(thrown?.message.orEmpty(), thrown?.message.orEmpty().contains("must be hash-pinned"))
        // A pinned .wasm and an unpinned .js (pinned on first download for local apps) pass.
        DepStore.requirePinnedWasm(listOf(DeftDependency(wasmUrl, DepStore.sri("sha384", addWasm), null), DeftDependency(threeUrl, null, null)))
    }
}
