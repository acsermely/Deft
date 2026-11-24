package app.deft.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class WorkspaceFilesTest {
    @Test
    fun globRootAndNestedJs() {
        assertTrue(WorkspaceFiles.globMatches("main.js", "**/*.js"))
        assertTrue(WorkspaceFiles.globMatches("main.js", "*.js"))
        assertTrue(WorkspaceFiles.globMatches("lib/store.js", "**/*.js"))
        assertTrue(WorkspaceFiles.globMatches("lib/store.js", "*.js"))
        assertFalse(WorkspaceFiles.globMatches("index.html", "**/*.js"))
        assertTrue(WorkspaceFiles.globMatches("screens/list.js", "screens/*.js"))
        assertFalse(WorkspaceFiles.globMatches("list.js", "screens/*.js"))
        assertTrue(WorkspaceFiles.globMatches("main.js", "**/*.{js,css,html}"))
        assertTrue(WorkspaceFiles.globMatches("styles.css", "**/*.{js,css,html}"))
    }

    @Test
    fun grepCaseInsensitiveAndLiteral() {
        val (ok, literal) = WorkspaceFiles.compileGrep("Angle|calc")
        assertFalse(literal)
        assertTrue(ok.matcher("// angle calculation").find())
        val (bad, asLiteral) = WorkspaceFiles.compileGrep("(open")
        assertTrue(asLiteral)
        assertTrue(bad.matcher("foo (open bar").find())
        assertEquals(Pattern.CASE_INSENSITIVE, ok.flags() and Pattern.CASE_INSENSITIVE)
    }

    @Test
    fun grepContextBlock() {
        val lines = listOf("a", "// angle calculation", "const dx = 1", "const dy = 2")
        val block = WorkspaceFiles.grepHitBlock("main.js", lines, 1, 2)
        assertTrue(block.contains("main.js:2:// angle calculation"))
        assertTrue(block.contains("main.js:3-const dx = 1"))
    }
}
