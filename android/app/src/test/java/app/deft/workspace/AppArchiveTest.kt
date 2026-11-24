package app.deft.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun roundTrip() {
        val src = tmp.newFolder("app")
        File(src, "manifest.json").writeText("""{"name":"n"}""")
        File(src, "index.html").writeText("<!doctype html>")
        File(src, "main.js").writeText("console.log(1)")
        val zip = ByteArrayOutputStream()
        AppArchive.export(src, zip)
        val dest = tmp.newFolder("imported")
        val report = AppArchive.import(dest, ByteArrayInputStream(zip.toByteArray()))
        assertEquals(3, report.files)
        assertEquals("<!doctype html>", File(dest, "index.html").readText())
        assertEquals("console.log(1)", File(dest, "main.js").readText())
    }

    @Test
    fun rejectsZipSlip() {
        val bytes = zipOf("../secret.txt" to "pwned")
        val dest = tmp.newFolder("safe")
        val err = runCatching { AppArchive.import(dest, ByteArrayInputStream(bytes)) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertTrue(err!!.message!!.contains("zip-slip") || err.message!!.contains("invalid path"))
        assertFalse(File(dest, "secret.txt").exists())
        assertFalse(File(dest.parentFile, "secret.txt").exists())
    }

    @Test
    fun rejectsUnknownExt() {
        val exe = zipOf("payload.exe" to "x")
        val dest2 = tmp.newFolder("exe")
        val report = AppArchive.import(dest2, ByteArrayInputStream(exe))
        assertEquals(0, report.files)
        assertTrue(report.skipped.any { it.contains("payload.exe") })
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
