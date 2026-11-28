package app.deft.workspace

import app.deft.config.parseLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeftPathsTest {
    @Test
    fun acceptsNormalFiles() {
        assertEquals("main.js", DeftPaths.parse("main.js").normalized)
        assertEquals("lib/store.js", DeftPaths.parse("lib/store.js").normalized)
        assertEquals("license", DeftPaths.parse("license").fileName)
    }

    @Test
    fun rejectsDotDot() {
        val err = runCatching { DeftPaths.parse("../secret") }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertTrue(err!!.message!!.contains("invalid path"))
    }

    @Test
    fun rejectsRuntimeDir() {
        val err = runCatching { DeftPaths.parse("_deft/v1/core.js") }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertTrue(err!!.message!!.contains("runtime"))
    }

    @Test
    fun oldRuntimeDirIsAnOrdinaryFolder() {
        assertTrue(runCatching { DeftPaths.parse("_fl/v1/core.js") }.isSuccess)
    }

    @Test
    fun rejectsUppercaseAndUnknownExt() {
        assertTrue(runCatching { DeftPaths.parse("Main.js") }.exceptionOrNull() is IllegalArgumentException)
        val exe = runCatching { DeftPaths.parse("payload.exe") }.exceptionOrNull()
        assertTrue(exe is IllegalArgumentException)
        assertTrue(exe!!.message!!.contains("disallowed"))
    }

    @Test
    fun wasmIsABinaryWithTheBinaryCap() {
        // A .wasm file is a binary asset and counts against the binary size cap.
        DeftPaths.active = DeftPaths.defaultLimits()
        val wasm = DeftPaths.parse("lib/add.wasm")
        assertTrue(wasm.isBinary)
        assertEquals(DeftPaths.MAX_BINARY_BYTES, wasm.maxBytes)
        DeftPaths.requireContentSize(wasm, DeftPaths.MAX_BINARY_BYTES)
        assertTrue(
            runCatching { DeftPaths.requireContentSize(wasm, DeftPaths.MAX_BINARY_BYTES + 1) }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }

    @Test
    fun rejectsTooDeep() {
        val err = runCatching { DeftPaths.parse("a/b/c/d/e/f/g.js") }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertTrue(err!!.message!!.contains("deeper"))
    }

    @Test
    fun sizeCaps() {
        DeftPaths.active = DeftPaths.defaultLimits()
        val js = DeftPaths.parse("main.js")
        DeftPaths.requireContentSize(js, DeftPaths.MAX_TEXT_BYTES)
        val over = runCatching { DeftPaths.requireContentSize(js, DeftPaths.MAX_TEXT_BYTES + 1) }.exceptionOrNull()
        assertTrue(over is IllegalArgumentException)
        val png = DeftPaths.parse("icon.png")
        DeftPaths.requireContentSize(png, DeftPaths.MAX_BINARY_BYTES)
        assertTrue(
            runCatching { DeftPaths.requireContentSize(png, DeftPaths.MAX_BINARY_BYTES + 1) }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }

    @Test
    fun clampsLimitsIntoTheShippedRange() {
        val low = DeftPaths.clampLimits(1, 1, 1, 1)
        assertEquals(DeftPaths.MAX_TEXT_BYTES, low.maxTextBytes)
        assertEquals(DeftPaths.MAX_BINARY_BYTES, low.maxBinaryBytes)
        assertEquals(DeftPaths.MAX_FILES, low.maxFiles)
        assertEquals(DeftPaths.MAX_PACKAGE_BYTES, low.maxPackageBytes)
        val high = DeftPaths.clampLimits(99_000_000, 99_000_000, 9_000, 99_000_000)
        assertEquals(DeftPaths.TEXT_CEILING, high.maxTextBytes)
        assertEquals(DeftPaths.BINARY_CEILING, high.maxBinaryBytes)
        assertEquals(DeftPaths.FILES_CEILING, high.maxFiles)
        assertEquals(DeftPaths.PACKAGE_CEILING, high.maxPackageBytes)
    }

    @Test
    fun largeCapsAreFreeSpaceBoundedAndNeverBelowNormal() {
        // min(free - 1 GiB, 2 GiB) for the package, 200 MiB per binary.
        val gib = 1024L * 1024 * 1024
        DeftPaths.active = DeftPaths.defaultLimits()
        assertEquals(2 * gib, DeftPaths.largeCaps(freeBytes = 10 * gib).packageBytes)
        assertEquals(gib, DeftPaths.largeCaps(freeBytes = 2 * gib).packageBytes)
        assertEquals(DeftPaths.HARD_PACKAGE_BYTES, DeftPaths.largeCaps(freeBytes = 0).packageBytes)
        assertEquals(DeftPaths.LARGE_BINARY_BYTES, DeftPaths.largeCaps(freeBytes = 10 * gib).binaryBytes)
        assertEquals(200L * 1024 * 1024, DeftPaths.LARGE_BINARY_BYTES)
    }

    @Test
    fun missingLimitKeysKeepDefaults() {
        val parsed = parseLimits(mapOf("max_text_bytes" to "1048576", "max_files" to "300"))
        assertEquals(1024L * 1024, parsed.maxTextBytes)
        assertEquals(300, parsed.maxFiles)
        assertEquals(DeftPaths.MAX_BINARY_BYTES, parsed.maxBinaryBytes)
        assertEquals(DeftPaths.HARD_PACKAGE_BYTES, parsed.maxPackageBytes)
    }

    @Test
    fun activeLimitIsWhatWritersEnforce() {
        val previous = DeftPaths.active
        DeftPaths.active = DeftPaths.clampLimits(600 * 1024, DeftPaths.MAX_BINARY_BYTES, 400, DeftPaths.HARD_PACKAGE_BYTES)
        try {
            DeftPaths.requireContentSize(DeftPaths.parse("main.js"), 600L * 1024)
            val over = runCatching {
                DeftPaths.requireContentSize(DeftPaths.parse("main.js"), 600L * 1024 + 1)
            }.exceptionOrNull()
            assertTrue(over is IllegalArgumentException)
        } finally {
            DeftPaths.active = previous
        }
    }
}
