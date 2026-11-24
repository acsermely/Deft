package app.deft.workspace

/**
 * Spec 3.1 path rules. Used by stores, agent file tools, zip import and the installer.
 */
object DeftPaths {
    const val MAX_DEPTH = 6
    const val MAX_FILES = 256
    const val MAX_TEXT_BYTES = 512L * 1024
    const val MAX_BINARY_BYTES = 2L * 1024 * 1024
    const val MAX_PACKAGE_BYTES = 10L * 1024 * 1024
    const val HARD_PACKAGE_BYTES = 25L * 1024 * 1024
    const val TEXT_CEILING = 4L * 1024 * 1024
    const val BINARY_CEILING = 8L * 1024 * 1024
    const val FILES_CEILING = 1024
    const val PACKAGE_CEILING = 64L * 1024 * 1024

    /**
     * Live quotas from `config/limits.toml`. Defaults match the previous compiled caps.
     * Each field is clamped: the floor is the shipped default (package floor is 10 MB),
     * and the ceiling is the constant above.
     */
    data class Limits(
        val maxTextBytes: Long,
        val maxBinaryBytes: Long,
        val maxFiles: Int,
        val maxPackageBytes: Long,
    )

    @Volatile
    var active: Limits = defaultLimits()

    fun defaultLimits() = Limits(
        maxTextBytes = MAX_TEXT_BYTES,
        maxBinaryBytes = MAX_BINARY_BYTES,
        maxFiles = MAX_FILES,
        maxPackageBytes = HARD_PACKAGE_BYTES,
    )

    fun clampLimits(text: Long, binary: Long, files: Int, pkg: Long) = Limits(
        maxTextBytes = text.coerceIn(MAX_TEXT_BYTES, TEXT_CEILING),
        maxBinaryBytes = binary.coerceIn(MAX_BINARY_BYTES, BINARY_CEILING),
        maxFiles = files.coerceIn(MAX_FILES, FILES_CEILING),
        maxPackageBytes = pkg.coerceIn(MAX_PACKAGE_BYTES, PACKAGE_CEILING),
    )

    /** PARITY G3: package and binary caps that apply to one package install. */
    data class Caps(val packageBytes: Long, val binaryBytes: Long)

    /** The normal caps: the live `limits.toml` values. */
    fun normalCaps() = Caps(active.maxPackageBytes, active.maxBinaryBytes)

    /**
     * PARITY G3: caps for an app whose manifest declares `storage.large` and whose grant is on.
     * Package: min(free space − 1 GiB, 2 GiB). Binary files: 200 MiB. Never lower than the normal caps.
     */
    fun largeCaps(freeBytes: Long): Caps {
        val normal = normalCaps()
        return Caps(
            packageBytes = minOf(freeBytes - LARGE_FREE_RESERVE_BYTES, LARGE_PACKAGE_MAX_BYTES)
                .coerceAtLeast(normal.packageBytes),
            binaryBytes = LARGE_BINARY_BYTES.coerceAtLeast(normal.binaryBytes),
        )
    }

    /** PARITY G3: the capability id that opts an app into [largeCaps]. */
    const val STORAGE_LARGE_ID = "storage.large"
    const val LARGE_PACKAGE_MAX_BYTES = 2L * 1024 * 1024 * 1024
    const val LARGE_BINARY_BYTES = 200L * 1024 * 1024
    const val LARGE_FREE_RESERVE_BYTES = 1L * 1024 * 1024 * 1024
    const val RUNTIME_DIR = "_deft"

    /** The pre-rename runtime dir; LocalOriginPathHandler still serves /_fl/v1/, so apps cannot create it either. */
    const val LEGACY_RUNTIME_DIR = "_fl"
    const val RUNTIME_MESSAGE =
        "Writes under _deft/ are reserved for the Deft runtime (/_deft/v1/). Apps cannot create this path."

    private val TEXT_EXT = setOf("html", "js", "css", "json", "svg", "txt", "md")
    private val BINARY_EXT = setOf("png", "webp", "jpg", "jpeg", "gif", "avif", "woff2", "wasm")
    private val BARE_NAMES = setOf("license", "copying")
    private val SEGMENT = Regex("^[a-z0-9._-]+$")

    data class Rel(
        val normalized: String,
        val segments: List<String>,
        val fileName: String,
    ) {
        val ext: String get() = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val isBare: Boolean get() = '.' !in fileName
        val isBinary: Boolean get() = ext in BINARY_EXT
        val maxBytes: Long get() = if (isBinary) active.maxBinaryBytes else active.maxTextBytes
    }

    fun parse(relative: String): Rel {
        val trimmed = relative.trim().replace('\\', '/')
        if (trimmed.isEmpty() || trimmed == ".") {
            return Rel(normalized = "", segments = emptyList(), fileName = "")
        }
        if (trimmed.startsWith("/")) throw IllegalArgumentException("path must be relative")
        val parts = trimmed.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw IllegalArgumentException("invalid path")
        if (parts.size > MAX_DEPTH) {
            throw IllegalArgumentException("path deeper than $MAX_DEPTH segments: $relative")
        }
        for (part in parts) {
            if (part == "." || part == "..") throw IllegalArgumentException("invalid path")
            if (part.startsWith('.')) throw IllegalArgumentException("dotfiles are not allowed: $part")
            if (part.equals(RUNTIME_DIR, ignoreCase = true) || part.equals(LEGACY_RUNTIME_DIR, ignoreCase = true)) {
                throw IllegalArgumentException(RUNTIME_MESSAGE)
            }
            if (!SEGMENT.matches(part.lowercase()) || part != part.lowercase()) {
                throw IllegalArgumentException(
                    "path segments must be lowercase [a-z0-9._-]: $part",
                )
            }
        }
        val fileName = parts.last()
        if (!isAllowedFileName(fileName)) {
            throw IllegalArgumentException(
                "disallowed file type: $fileName (allowed: ${allowedList()})",
            )
        }
        return Rel(normalized = parts.joinToString("/"), segments = parts, fileName = fileName)
    }

    fun parseExisting(relative: String): Rel {
        val trimmed = relative.trim().replace('\\', '/').trimStart('/')
        if (trimmed.isEmpty() || trimmed == ".") {
            return Rel(normalized = "", segments = emptyList(), fileName = "")
        }
        if (trimmed.contains("..")) throw IllegalArgumentException("invalid path")
        return parse(trimmed.lowercase())
    }

    fun isAllowedFileName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in BARE_NAMES && '.' !in name) return true
        val ext = lower.substringAfterLast('.', missingDelimiterValue = "")
        return ext in TEXT_EXT || ext in BINARY_EXT
    }

    /** PARITY G2: `.wasm` files reach deft-lint by path only; their bytes are never text. */
    fun isWasmFileName(name: String): Boolean = name.lowercase().endsWith(".wasm")

    fun isTextFileName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in BARE_NAMES) return true
        return lower.substringAfterLast('.', "") in TEXT_EXT
    }

    fun requireContentSize(rel: Rel, bytes: Long) = requireContentSize(rel, bytes, active.maxBinaryBytes)

    /** [binaryCap] replaces the live binary cap; text files always use the live text cap. */
    fun requireContentSize(rel: Rel, bytes: Long, binaryCap: Long) {
        val cap = if (rel.isBinary) binaryCap else rel.maxBytes
        if (bytes > cap) {
            val kind = if (rel.isBinary) "binary asset" else "text file"
            throw IllegalArgumentException("$kind exceeds $cap bytes: ${rel.normalized}")
        }
    }

    fun requireFileBudget(currentFiles: Int, addingNew: Boolean) {
        if (addingNew && currentFiles >= active.maxFiles) {
            throw IllegalArgumentException("app has ${active.maxFiles} files already")
        }
    }

    fun countFiles(root: java.io.File): Int {
        if (!root.isDirectory) return 0
        var n = 0
        root.walkTopDown().forEach { if (it.isFile) n++ }
        return n
    }

    fun allowedList(): String =
        (TEXT_EXT + BINARY_EXT).sorted().joinToString(", ") { ".$it" } + ", LICENSE, COPYING"
}
