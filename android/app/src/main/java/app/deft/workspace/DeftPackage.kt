package app.deft.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * Reads a signed .deftpkg. Verification finishes before any file is written.
 */
object DeftPackage {
    private val json = Json { ignoreUnknownKeys = true }

    /** The signature document inside a package. */
    const val PACKAGE_FILE = "deft-package.json"

    /** Its name in packages made before the Deft rename (their signed list says `fl: 1`); still accepted. */
    const val LEGACY_PACKAGE_FILE = "fl-package.json"

    private fun isPackageFile(name: String) = name == PACKAGE_FILE || name == LEGACY_PACKAGE_FILE

    data class Verified(
        val id: String,
        val files: Map<String, ByteArray>,
        val publicKey: String,
        val version: String = "0.0.0",
        /** PARITY G3: the caps this package was checked against. */
        val caps: DeftPaths.Caps = DeftPaths.normalCaps(),
    )

    /**
     * [largeCaps] answers, for an app id, the caps to use when the package declares `storage.large`
     * (PARITY G3). Null means the app is not granted it, so the normal caps apply. The caller
     * decides the grant; the answer is only used once the signature and the id check pass.
     */
    fun verify(bytes: ByteArray, largeCaps: (appId: String) -> DeftPaths.Caps? = { null }): Verified {
        val peek = peekLargeCaps(bytes, largeCaps)
        val caps = peek?.second ?: DeftPaths.normalCaps()
        val maxBytes = caps.packageBytes
        if (bytes.size.toLong() > maxBytes) {
            throw IllegalArgumentException("package larger than $maxBytes bytes")
        }
        val entries = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                if (entry.isDirectory) continue
                if (name.startsWith("/") || name.contains("..")) {
                    throw IllegalArgumentException("zip-slip: $name")
                }
                if (name in entries) throw IllegalArgumentException("duplicate zip entry: $name")
                val body = AppArchive.readBounded(zip, maxBytes - totalBytes)
                    ?: throw IllegalArgumentException("package larger than $maxBytes bytes")
                totalBytes += body.size
                entries[name] = body
            }
        }
        val raw = entries[PACKAGE_FILE] ?: entries[LEGACY_PACKAGE_FILE] ?: throw IllegalArgumentException("missing $PACKAGE_FILE")
        val doc = json.parseToJsonElement(raw.decodeToString()).jsonObject
        val manifest = doc["manifest"]?.jsonObject ?: throw IllegalArgumentException("missing manifest")
        val publicKey = doc["publicKey"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("missing publicKey")
        val signature = doc["signature"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("missing signature")
        if (!verifySig(DeftJcs.of(manifest), signature, publicKey)) {
            throw IllegalArgumentException("bad signature")
        }
        val listed = manifest["files"]?.jsonArray ?: throw IllegalArgumentException("missing files")
        val expected = linkedSetOf<String>()
        val files = LinkedHashMap<String, ByteArray>()
        for (item in listed) {
            val obj = item.jsonObject
            val path = obj["path"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("file missing path")
            val sha = obj["sha256"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("file missing sha256")
            if (!expected.add(path)) throw IllegalArgumentException("duplicate manifest path: $path")
            val body = entries[path] ?: throw IllegalArgumentException("missing $path")
            val hash = sha256(body)
            if (hash != sha) throw IllegalArgumentException("hash mismatch $path")
            files[path] = body
        }
        for (name in entries.keys) {
            if (isPackageFile(name)) continue
            if (name !in expected) throw IllegalArgumentException("extra file $name")
        }
        val declared = doc["capabilities"]?.jsonArray
        if (declared != null) {
            val fromManifest = manifest["capabilities"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            val fromDoc = declared.map { it.jsonPrimitive.content }.toSet()
            if (fromManifest != fromDoc) {
                throw IllegalArgumentException("permission mismatch")
            }
        }
        requireRulesAndQuotas(files, caps.binaryBytes)
        // The signed file list has no app id; it lives in the hashed manifest.json.
        val appManifest = files["manifest.json"]?.let { bytes ->
            runCatching { json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()
        }
        val appId = appManifest?.get("id")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val id = appId ?: manifest["id"]?.jsonPrimitive?.content ?: "app"
        // PARITY G3: the large caps were chosen for the signed id; the id this package installs as must be the same one.
        if (peek != null && id != peek.first) {
            throw IllegalArgumentException("manifest.json id does not match the signed id")
        }
        val version = appManifest?.get("version")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: "0.0.0"
        return Verified(id = id, files = files, publicKey = publicKey, version = version, caps = caps)
    }

    /**
     * PARITY G3: the signed id and large caps when the package declares `storage.large` and
     * [largeCaps] grants it, else null. It runs before the signature check, so it is used only to
     * choose how much of the zip to read; [verify] then checks the signature and the id, and only
     * then applies the caps. A forged id gains nothing: the signature must match the pinned key.
     */
    private fun peekLargeCaps(
        bytes: ByteArray,
        largeCaps: (String) -> DeftPaths.Caps?,
    ): Pair<String, DeftPaths.Caps>? {
        val raw = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var found: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (!isPackageFile(entry.name.replace('\\', '/'))) continue
                found = AppArchive.readBounded(zip, DeftPaths.normalCaps().packageBytes) ?: return null
                break
            }
            found
        } ?: return null
        val manifest = runCatching {
            json.parseToJsonElement(raw.decodeToString()).jsonObject["manifest"]?.jsonObject
        }.getOrNull() ?: return null
        val declares = manifest["capabilities"]?.jsonArray?.any {
            runCatching { it.jsonPrimitive.content }.getOrNull() == DeftPaths.STORAGE_LARGE_ID
        } == true
        if (!declares) return null
        val id = manifest["id"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: return null
        val caps = largeCaps(id) ?: return null
        return id to caps
    }

    /**
     * PARITY H5: path rules and quotas run on the verified bytes, before anything touches disk.
     * Dependency blobs (`deps/...`) have their own hash check in [DepStore].
     */
    private fun requireRulesAndQuotas(files: Map<String, ByteArray>, binaryCap: Long) {
        var count = 0
        for ((path, body) in files) {
            if (DepStore.isPackagePath(path)) continue
            val rel = DeftPaths.parse(path)
            if (rel.normalized != path) throw IllegalArgumentException("non-canonical path: $path")
            DeftPaths.requireContentSize(rel, body.size.toLong(), binaryCap)
            count++
        }
        if (count > DeftPaths.active.maxFiles) {
            throw IllegalArgumentException("package has $count files; the limit is ${DeftPaths.active.maxFiles}")
        }
    }

    /**
     * Compares `major.minor.patch[-pre]` versions. A pre-release sorts before its release.
     * Unparseable parts count as 0, so a malformed version can never look newer than a real one.
     */
    fun compareVersions(a: String, b: String): Int {
        fun core(v: String) = v.substringBefore('+').substringBefore('-').split('.')
            .map { it.toIntOrNull() ?: 0 }.let { it + List((3 - it.size).coerceAtLeast(0)) { 0 } }
        val ca = core(a)
        val cb = core(b)
        for (i in 0 until maxOf(ca.size, cb.size)) {
            val d = ca.getOrElse(i) { 0 }.compareTo(cb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        val pa = a.substringBefore('+').contains('-')
        val pb = b.substringBefore('+').contains('-')
        return when {
            pa == pb -> 0
            pa -> -1
            else -> 1
        }
    }

    /** PARITY H5: a same-key update may not go back to an older version. */
    fun requireNotDowngrade(previous: String?, incoming: String) {
        if (previous != null && compareVersions(incoming, previous) < 0) {
            throw IllegalArgumentException("downgrade rejected: installed $previous, package $incoming")
        }
    }

    fun looksSigned(bytes: ByteArray): Boolean = looksSigned(ByteArrayInputStream(bytes))

    fun looksSigned(input: java.io.InputStream): Boolean =
        runCatching {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: return false
                    if (isPackageFile(entry.name.replace('\\', '/'))) return true
                }
            }
            false
        }.getOrDefault(false)

    fun requireSameKey(previous: String?, next: String) {
        if (previous != null && previous != next) {
            throw IllegalArgumentException("wrong key on update")
        }
    }

    fun install(dest: java.io.File, bytes: ByteArray): Verified = verify(bytes).also { extract(it, dest) }

    /** Writes verified app files (not dependency blobs) into an empty [dest]. */
    fun extract(verified: Verified, dest: java.io.File) {
        if (dest.exists() && dest.list()?.isNotEmpty() == true) {
            throw IllegalArgumentException("destination is not empty")
        }
        dest.mkdirs()
        for ((path, body) in verified.files) {
            if (DepStore.isPackagePath(path)) continue
            val parsed = DeftPaths.parse(path)
            val out = java.io.File(dest, parsed.normalized)
            if (!out.canonicalPath.startsWith(dest.canonicalPath)) {
                dest.deleteRecursively()
                throw IllegalArgumentException("zip-slip: $path")
            }
            out.parentFile?.mkdirs()
            out.writeBytes(body)
            if (java.nio.file.Files.isSymbolicLink(out.toPath())) {
                dest.deleteRecursively()
                throw IllegalArgumentException("symlinks are not allowed: $path")
            }
        }
    }

    private fun verifySig(payload: String, signatureB64: String, publicB64: String): Boolean =
        runCatching {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(publicB64)),
            )
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(key)
            sig.update(payload.toByteArray())
            sig.verify(Base64.getDecoder().decode(signatureB64))
        }.getOrDefault(false)

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
