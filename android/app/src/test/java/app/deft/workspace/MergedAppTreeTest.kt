package app.deft.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MergedAppTreeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(root: File, rel: String, text: String) =
        File(root, rel).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun overlayWinsOverBase() {
        val base = tmp.newFolder("base")
        val overlay = tmp.newFolder("overlay")
        write(base, "index.html", "base")
        write(base, "main.js", "base js")
        write(overlay, "index.html", "mine")
        val tree = MergedAppTree(base, overlay)
        assertEquals("mine", tree.resolve("index.html", emptySet())!!.readText())
        assertEquals("base js", tree.resolve("main.js", emptySet())!!.readText())
        assertEquals("mine", tree.resolve("", emptySet())!!.readText())
    }

    @Test
    fun deletedPathIsAbsentEvenIfBaseHasIt() {
        val base = tmp.newFolder("base")
        write(base, "old.js", "x")
        val tree = MergedAppTree(base, tmp.newFolder("overlay"))
        assertNull(tree.resolve("old.js", setOf("old.js")))
    }

    @Test
    fun escapesAreRejected() {
        val base = tmp.newFolder("base")
        write(tmp.root, "secret.txt", "s")
        val tree = MergedAppTree(base, null)
        assertNull(tree.resolve("../secret.txt", emptySet()))
    }

    @Test
    fun overlayCreatedLaterIsSeen() {
        val base = tmp.newFolder("base")
        write(base, "a.js", "base")
        val overlay = File(tmp.root, "overlay-late")
        val tree = MergedAppTree(base, overlay)
        assertEquals("base", tree.resolve("a.js", emptySet())!!.readText())
        write(overlay, "a.js", "edited")
        assertEquals("edited", tree.resolve("a.js", emptySet())!!.readText())
    }

    @Test
    fun manifestAndFileListAreMerged() {
        val base = tmp.newFolder("base")
        val overlay = tmp.newFolder("overlay")
        write(base, "manifest.json", "{\"base\":1}")
        write(base, "lib/util.js", "u")
        write(overlay, "manifest.json", "{\"overlay\":1}")
        write(overlay, "new.js", "n")
        val tree = MergedAppTree(base, overlay)
        assertEquals("{\"overlay\":1}", tree.manifestFile().readText())
        val files = tree.files()
        assertEquals(setOf("manifest.json", "lib/util.js", "new.js"), files.keys)
        assertEquals("{\"overlay\":1}", files.getValue("manifest.json").readText())
    }
}
