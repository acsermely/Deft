package app.deft.workspace

import android.content.Context
import app.deft.registry.DeftDependency
import app.deft.registry.DeftManifestParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/**
 * Content-addressed store of CDN files, one file per SRI hash, shared by
 * every app that declares it. Bytes are verified before they are stored.
 */
object DepStore {
    const val MAX_FILE_BYTES = 16L * 1024 * 1024
    const val MAX_STORE_BYTES = 512L * 1024 * 1024
    const val PIN_ALGORITHM = "sha384"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()
    private val SRI = Regex("^(sha256|sha384|sha512)-([A-Za-z0-9+/]+={0,2})$")
    private val STORE_NAME = Regex("^sha(256|384|512)-[0-9a-f]+$")

    /** Result of a confirmed download: the URL and the hash its bytes have. */
    data class Fetched(val url: String, val integrity: String)

    /** Path of a dependency inside a .deftpkg: `deps/<algo>-<hex>`. */
    fun packagePath(integrity: String): String = "deps/" + storeName(integrity)

    fun isPackagePath(path: String): Boolean =
        path.startsWith("deps/") && STORE_NAME.matches(path.removePrefix("deps/"))

    fun root(context: Context): File = File(context.filesDir, "deft/deps").canonicalFile

    /** Store file name for an SRI hash: `<algo>-<lowercase hex>`. */
    fun storeName(integrity: String): String {
        val m = SRI.matchEntire(integrity) ?: throw IllegalArgumentException("not an SRI hash: $integrity")
        val raw = Base64.getDecoder().decode(m.groupValues[2])
        val expected = when (m.groupValues[1]) {
            "sha256" -> 32
            "sha384" -> 48
            else -> 64
        }
        require(raw.size == expected) { "${m.groupValues[1]} digest must be $expected bytes" }
        return m.groupValues[1] + "-" + raw.joinToString("") { "%02x".format(it) }
    }

    fun fileIn(storeRoot: File, integrity: String): File = File(storeRoot, storeName(integrity))

    fun has(storeRoot: File, integrity: String): Boolean =
        runCatching { fileIn(storeRoot, integrity).isFile }.getOrDefault(false)

    /** Dependencies that cannot be served yet: not pinned, or not in the store. */
    fun missing(context: Context, deps: List<DeftDependency>): List<DeftDependency> {
        val store = root(context)
        return deps.filter { it.integrity == null || !has(store, it.integrity) }
    }

    fun dependenciesIn(manifest: File): List<DeftDependency> {
        if (!manifest.isFile) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(manifest.readText()).jsonObject
            DeftManifestParser.parseDependencies(root["dependencies"])
        }.getOrDefault(emptyList())
    }

    /** Served path of a CDN URL on the app origin. Mirrors the CDN path so relative imports keep working. */
    fun servedPath(dep: DeftDependency): String = "/_deft/deps/${dep.host}/${dep.path}"

    /**
     * Downloads one file and checks it. With a pinned hash the bytes must match; without one
     * the host computes [PIN_ALGORITHM]. Call only after the user confirmed the host sheet.
     */
    fun download(context: Context, dep: DeftDependency): Fetched {
        dep.integrity?.let { if (has(root(context), it)) return Fetched(dep.url, it) }
        val bytes = httpGet(dep.url)
        val integrity = dep.integrity ?: sri(PIN_ALGORITHM, bytes)
        store(context, integrity, bytes)
        return Fetched(dep.url, integrity)
    }

    /** Verifies [bytes] against [integrity] and stores them under their hash. */
    fun store(context: Context, integrity: String, bytes: ByteArray) = storeIn(root(context), integrity, bytes)

    /** [store] into an explicit store folder (unit tests use a temp folder). */
    fun storeIn(root: File, integrity: String, bytes: ByteArray) {
        if (bytes.size > MAX_FILE_BYTES) throw IllegalArgumentException("dependency larger than $MAX_FILE_BYTES bytes")
        val algo = SRI.matchEntire(integrity)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("not an SRI hash: $integrity")
        if (sri(algo, bytes) != integrity) throw IllegalArgumentException("bytes do not match the declared hash $integrity")
        synchronized(lock) {
            root.mkdirs()
            val dest = fileIn(root, integrity)
            if (dest.isFile) return
            if (storeBytes(root) + bytes.size > MAX_STORE_BYTES) {
                throw IllegalArgumentException("dependency store is full ($MAX_STORE_BYTES bytes)")
            }
            val tmp = File(root, ".tmp-${dest.name}")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IllegalStateException("could not move dependency into the store")
            }
        }
    }

    fun sri(algo: String, bytes: ByteArray): String {
        val name = when (algo) {
            "sha256" -> "SHA-256"
            "sha384" -> "SHA-384"
            "sha512" -> "SHA-512"
            else -> throw IllegalArgumentException("unsupported hash $algo")
        }
        return algo + "-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance(name).digest(bytes))
    }

    /** Hashes referenced by any manifest the host still needs (clean up). */
    fun referenced(context: Context): Set<String> {
        val manifests = mutableListOf<File>()
        fun appsUnder(dir: File) {
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) manifests += File(child, "manifest.json")
            }
        }
        appsUnder(StorageMigration.localRoot(context))
        appsUnder(StorageMigration.bundledRoot(context))
        appsUnder(File(context.filesDir, "deft/overlay"))
        appsUnder(File(context.filesDir, "deft/rollback"))
        return manifests.flatMap { dependenciesIn(it) }.mapNotNull { it.integrity }.toSet()
    }

    /** Mark and sweep: deletes every store file no manifest references. Returns how many were removed. */
    fun sweep(context: Context): Int = synchronized(lock) {
        val keep = referenced(context).mapNotNull { runCatching { storeName(it) }.getOrNull() }.toSet()
        var removed = 0
        root(context).listFiles()?.forEach { child ->
            if (child.name in keep && STORE_NAME.matches(child.name)) return@forEach
            child.deleteRecursively()
            removed++
        }
        removed
    }

    /** Writes computed hashes into a local manifest.json, like a lockfile. Existing hashes are never replaced. */
    fun pin(manifest: File, fetched: List<Fetched>) {
        val root = json.parseToJsonElement(manifest.readText()).jsonObject
        val deps = root["dependencies"]?.jsonObject ?: return
        val byUrl = fetched.associateBy { it.url }
        val nextDeps = buildJsonObject {
            for ((url, value) in deps) {
                val found = byUrl[url]
                when {
                    found == null -> put(url, value)
                    value is JsonNull -> put(url, found.integrity)
                    value is JsonObject && (value["integrity"] == null || value["integrity"] is JsonNull) ->
                        put(
                            url,
                            buildJsonObject {
                                for ((k, v) in value) if (k != "integrity") put(k, v)
                                put("integrity", found.integrity)
                            },
                        )
                    else -> put(url, value)
                }
            }
        }
        val next = JsonObject(root.toMutableMap<String, JsonElement>().apply { put("dependencies", nextDeps) })
        manifest.writeText(json.encodeToString(JsonObject.serializer(), next) + "\n")
    }

    /**
     * The import map for an app: each declared host prefix -> its served prefix,
     * plus `as` aliases. Only stored files count. Returns null when nothing is servable.
     */
    fun importMap(storeRoot: File, deps: List<DeftDependency>): String? {
        val ready = deps.filter { it.integrity != null && has(storeRoot, it.integrity) }
        if (ready.isEmpty()) return null
        val imports = linkedMapOf<String, String>()
        for (dep in ready.sortedBy { it.url }) {
            imports["https://${dep.host}/"] = "/_deft/deps/${dep.host}/"
            dep.alias?.let { imports[it] = servedPath(dep) }
        }
        val map = buildJsonObject {
            put("imports", buildJsonObject { for ((k, v) in imports) put(k, JsonPrimitive(v)) })
        }
        return Json.encodeToString(JsonObject.serializer(), map)
    }

    /** A WebAssembly dependency (by URL; the parser allows `.wasm` next to `.js`, `.mjs` and `.json`). */
    fun isWasm(dep: DeftDependency): Boolean = dep.url.substringAfterLast('/').lowercase().endsWith(".wasm")

    /**
     * A `.wasm` dependency is fetched only with its hash in the manifest. The host never
     * pins one on first download, so the bytes the author tested are the bytes that run.
     */
    fun requirePinnedWasm(deps: List<DeftDependency>) {
        deps.firstOrNull { isWasm(it) && it.integrity == null }?.let {
            throw IllegalArgumentException("WebAssembly dependency ${it.url} must be hash-pinned in manifest.json")
        }
    }

    /** What `/_deft/deps/` serves for one request: a verified store file and its MIME type. */
    data class Served(val file: File, val mime: String)

    /**
     * The response for `/_deft/deps/<host>/<path>`: only a URL this app declares,
     * only once its pinned bytes are in the store. a `.wasm` file is served as
     * `application/wasm`, and only when [wasmAllowed] (the app declares and holds `wasm`).
     */
    fun serve(storeRoot: File, deps: List<DeftDependency>, servedRest: String, wasmAllowed: Boolean): Served? {
        if (servedRest.split('/').any { it.isEmpty() || it.startsWith(".") }) return null
        val file = lookup(storeRoot, deps, servedRest) ?: return null
        val name = servedRest.substringAfterLast('/').lowercase()
        val mime = when {
            name.endsWith(".json") -> "application/json"
            name.endsWith(".wasm") -> if (wasmAllowed) "application/wasm" else return null
            else -> "text/javascript"
        }
        return Served(file, mime)
    }

    /** Store file for a request under `/_deft/deps/<host>/<path>`, only when this app declares that URL. */
    fun lookup(storeRoot: File, deps: List<DeftDependency>, servedRest: String): File? {
        val url = "https://$servedRest"
        val dep = deps.firstOrNull { it.url == url } ?: return null
        val integrity = dep.integrity ?: return null
        return fileIn(storeRoot, integrity).takeIf { it.isFile }
    }

    private fun storeBytes(root: File): Long =
        root.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sumOf { it.length() } ?: 0L

    private fun httpGet(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            val code = conn.responseCode
            if (code in 300..399) {
                val target = conn.getHeaderField("Location").orEmpty()
                throw IllegalArgumentException("$url redirects to $target. Declare the final URL instead.")
            }
            if (code != 200) throw IllegalArgumentException("$url returned HTTP $code")
            return conn.inputStream.use { AppArchive.readBounded(it, MAX_FILE_BYTES) }
                ?: throw IllegalArgumentException("$url is larger than $MAX_FILE_BYTES bytes")
        } finally {
            conn.disconnect()
        }
    }
}
