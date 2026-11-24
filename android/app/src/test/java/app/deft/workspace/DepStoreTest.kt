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
}
