package app.deft.webview

import org.junit.Assert.assertEquals
import org.junit.Test

/** Apps made before the Deft rename get the runtime with its old names (LocalOriginPathHandler.legacyNames). */
class LegacyNamesTest {
    private fun legacy(s: String) = LocalOriginPathHandler.legacyNames(s)

    @Test
    fun mapsDeftNamesToTheirFlForms() {
        assertEquals("fl-btn", legacy("deft-btn"))
        assertEquals("var(--fl-accent)", legacy("var(--deft-accent)"))
        assertEquals("import '/_fl/v1/x.css';", legacy("import '/_deft/v1/x.css';"))
        assertEquals("@layer fl.components {", legacy("@layer deft.components {"))
        assertEquals("box.dataset.flEnter", legacy("box.dataset.deftEnter"))
    }

    @Test
    fun leavesIdentifiersAlone() {
        assertEquals("self.deftBridge.invoke()", legacy("self.deftBridge.invoke()"))
        assertEquals("deftBridge", legacy("deftBridge"))
        assertEquals("self.__deftOnBack", legacy("self.__deftOnBack"))
    }
}
