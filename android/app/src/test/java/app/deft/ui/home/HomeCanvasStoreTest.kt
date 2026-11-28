package app.deft.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HomeCanvasStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun seedsBundledFiles() {
        val store = HomeCanvasStore(tmp.newFolder("canvas"))
        store.ensureSeeded()
        val listed = store.list(".")
        assertTrue(listed.contains("index.html"))
        assertTrue(listed.contains("styles.css"))
        assertTrue(listed.contains("main.js"))
        assertTrue(store.read("index.html").contains("viewport-fit=cover"))
        assertTrue(store.read("styles.css").contains("safe-area-inset"))
        assertTrue(store.read("main.js").contains("Home clock"))
        assertEquals(0, store.revision.value)
    }

    @Test
    fun writeBumpsRevisionAndEditIsUnique() {
        val store = HomeCanvasStore(tmp.newFolder("canvas"))
        store.write("notes.txt", "hello canvas")
        assertEquals(1, store.revision.value)
        assertEquals("hello canvas", store.read("notes.txt"))
        store.edit("notes.txt", "hello canvas", "painted")
        assertEquals(2, store.revision.value)
        assertEquals("painted", store.read("notes.txt"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathEscape() {
        val store = HomeCanvasStore(tmp.newFolder("canvas"))
        store.resolve("../secret")
    }

    @Test
    fun rejectsRuntimeDirAndUnknownType() {
        val store = HomeCanvasStore(tmp.newFolder("canvas"))
        val fl = runCatching { store.write("_deft/x.js", "no") }.exceptionOrNull()
        assertTrue(fl is IllegalArgumentException)
        assertTrue(fl!!.message!!.contains("runtime"))
        val exe = runCatching { store.write("payload.exe", "x") }.exceptionOrNull()
        assertTrue(exe is IllegalArgumentException)
    }

    @Test
    fun resetRestoresBundledWallpaper() {
        val dir = tmp.newFolder("canvas")
        val store = HomeCanvasStore(dir)
        store.write("notes.txt", "scratch")
        store.write("main.js", "// wiped\n")
        store.reset()
        assertTrue(store.read("main.js").contains("Home clock"))
        assertFalse(File(dir, "notes.txt").exists())
        assertTrue(store.revision.value >= 1)
    }

    @Test
    fun upgradesUntouchedStylesFromTheFixedClearance() {
        val dir = tmp.newFolder("canvas")
        val store = HomeCanvasStore(dir)
        store.ensureSeeded()
        val current = store.read("styles.css")
        val previous = current
            .replace("/* The launcher sets --chrome-clearance to the height of its dock, harness bar and bottom system bar. */\n", "")
            .replace(
                "28px var(--chrome-clearance);",
                "28px calc(var(--chrome-clearance) + env(safe-area-inset-bottom, 0px));",
            )
        File(dir, "styles.css").writeText(previous)
        HomeCanvasStore(dir).ensureSeeded()
        assertEquals(current, File(dir, "styles.css").readText())
        // An edited copy is left alone.
        File(dir, "styles.css").writeText(previous + "/* mine */\n")
        HomeCanvasStore(dir).ensureSeeded()
        assertTrue(File(dir, "styles.css").readText().endsWith("/* mine */\n"))
    }

    @Test
    fun globAndGrep() {
        val store = HomeCanvasStore(tmp.newFolder("canvas"))
        store.ensureSeeded()
        assertTrue(store.glob("**/*.css").contains("styles.css"))
        val hits = store.grep("chrome-clearance", "**/*.css")
        assertTrue(hits.contains("styles.css"))
    }
}
