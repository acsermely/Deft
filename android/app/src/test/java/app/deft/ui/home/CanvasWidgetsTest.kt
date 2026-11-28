package app.deft.ui.home

import app.deft.registry.DeftWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasWidgetsTest {
    @Test
    fun readsEveryFlWidgetElementWithItsSize() {
        val html = """
            <deft-widget app="myapp:notes" size="2x1"></deft-widget>
            <DEFT-WIDGET size='4x2' app='bundled:clock'></DEFT-WIDGET>
            <deft-widget app="myapp:calendar"></deft-widget>
            <deft-widgetish app="no"></deft-widgetish>
        """.trimIndent()
        assertEquals(
            listOf(
                WidgetPlacement("myapp:notes", "2x1"),
                WidgetPlacement("bundled:clock", "4x2"),
                WidgetPlacement("myapp:calendar", "1x1"),
            ),
            CanvasWidgets.placements("<div class=\"deft-widgets\">$html</div>"),
        )
    }

    @Test
    fun ignoresTheOldFlWidgetTag() {
        val html = """<div class="fl-widgets"><fl-widget app="myapp:steps" size="1x1"></fl-widget></div>"""
        assertEquals(emptyList<WidgetPlacement>(), CanvasWidgets.placements(html))
    }

    @Test
    fun parsesAnyAttributeOrderQuotingAndIgnoresLookalikesAndComments() {
        val html = """
            <!-- <deft-widget app="myapp:old"></deft-widget> -->
            <deft-widget data-app="myapp:wrong" app=myapp:bare size=2x2></deft-widget>
            <deft-widget size="" title="a > b" app="myapp:quoted-gt"></deft-widget>
            <deft-widget app="myapp:first" app="myapp:second"/>
            <deft-widget-quiet app="no"></deft-widget-quiet>
            <!-- unterminated <deft-widget app="myapp:hidden">
        """.trimIndent()
        assertEquals(
            listOf(
                WidgetPlacement("myapp:bare", "2x2"),
                WidgetPlacement("myapp:quoted-gt", "1x1"),
                WidgetPlacement("myapp:first", "1x1"),
            ),
            CanvasWidgets.placements("<div class=\"deft-widgets\">$html</div>"),
        )
    }

    @Test
    fun widgetOutsideTheGridWarnsToWrapIt() {
        val html = """<main><deft-widget app="myapp:a"></deft-widget></main><div class="x deft-widgets"><section><deft-widget app="myapp:b"></deft-widget></section></div>"""
        val placed = CanvasWidgets.placements(html)
        assertEquals(listOf(false, true), placed.map { it.inGrid })
        val declared = mapOf("myapp:a" to DeftWidget("/widget.html", listOf("1x1")))
        val lines = CanvasWidgets.findings(placed.take(1), setOf("myapp:a"), declared).map { it.lineText() }
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("deft-widgets"))
    }

    @Test
    fun reportsUnknownAppsNoWidgetsUndeclaredSizesAndTheCap() {
        val placed = listOf(
            WidgetPlacement("myapp:ghost", "2x1"),
            WidgetPlacement("myapp:plain", "1x1"),
            WidgetPlacement("myapp:notes", "4x4"),
            WidgetPlacement("myapp:notes", "2x1"),
        )
        val declared = mapOf("myapp:notes" to DeftWidget("/widget.html", listOf("2x1")))
        val installed = setOf("myapp:notes", "myapp:plain")
        val lines = CanvasWidgets.findings(placed, installed, declared).map { it.lineText() }
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("myapp:ghost") && lines[0].contains("not an installed app"))
        assertTrue(lines[1].contains("myapp:plain") && lines[1].contains("declares no widget"))
        assertTrue(lines[2].contains("size=\"4x4\"") && lines[2].contains("sizes: 2x1"))
        assertTrue(lines.all { it.startsWith("WARN") })
    }

    @Test
    fun warnsWhenMoreThanTheCapIsPlaced() {
        val placed = List(CanvasWidgets.CAP + 1) { WidgetPlacement("myapp:notes", "2x1") }
        val declared = mapOf("myapp:notes" to DeftWidget("/widget.html", listOf("2x1")))
        val lines = CanvasWidgets.findings(placed, setOf("myapp:notes"), declared).map { it.lineText() }
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("7 deft-widget elements"))
    }

    @Test
    fun cleanCanvasHasNoFindings() {
        val placed = listOf(WidgetPlacement("myapp:notes", "2x2"))
        val declared = mapOf("myapp:notes" to DeftWidget("/widget.html", listOf("2x1", "2x2")))
        assertTrue(CanvasWidgets.findings(placed, setOf("myapp:notes"), declared).isEmpty())
    }

    @Test
    fun needsSetupWarnsWithTheCapabilitiesAndStateIsSetupOrReady() {
        val placed = listOf(WidgetPlacement("myapp:steps", "2x1"))
        val declared = mapOf("myapp:steps" to DeftWidget("/widget.html", listOf("2x1")))
        val lines = CanvasWidgets.findings(placed, setOf("myapp:steps"), declared, mapOf("myapp:steps" to listOf("sensors"))).map { it.lineText() }
        assertEquals(1, lines.size)
        assertEquals(
            "WARN widget deft-widget app=\"myapp:steps\" needs setup: ask the user to tap the widget once to allow sensors",
            lines[0],
        )
        assertEquals("setup", CanvasWidgets.stateOf(listOf("sensors")))
        assertEquals("ready", CanvasWidgets.stateOf(emptyList()))
        assertEquals("ready", CanvasWidgets.stateOf(null))
    }

    @Test
    fun registersOnlyPlacedMyAppsWithAFolderAndNoRow() {
        val placed = listOf(
            WidgetPlacement("myapp:steps", "2x1"),
            WidgetPlacement("myapp:steps", "1x1"),
            WidgetPlacement("myapp:done", "1x1"),
            WidgetPlacement("myapp:nofolder", "1x1"),
            WidgetPlacement("bundled:clock", "1x1"),
            WidgetPlacement("myapp:", "1x1"),
        )
        val got = CanvasWidgets.slugsToRegister(placed, setOf("myapp:done")) { it == "steps" || it == "done" }
        assertEquals(listOf("steps"), got)
    }

    @Test
    fun specialAccessWarnsThatWidgetsCannotHaveIt() {
        val placed = listOf(WidgetPlacement("myapp:steps", "1x1"))
        val declared = mapOf("myapp:steps" to DeftWidget("/widget.html", listOf("1x1")))
        val lines = CanvasWidgets.findings(placed, setOf("myapp:steps"), declared, special = mapOf("myapp:steps" to listOf("accessibility"))).map { it.lineText() }
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("special access is not for widgets"))
    }

    @Test
    fun reloadJsListsOriginsAndFramesAnswerThemeAndReload() {
        val js = CanvasWidgets.reloadFramesJs(listOf("https://a.app.deft.internal"))
        assertTrue(js.contains("[\"https://a.app.deft.internal\"]"))
        assertTrue(js.contains("deft:reload") && !js.contains("fl:reload"))
        assertTrue(CanvasWidgets.THEME_FRAMES_JS.contains("deft:theme") && !CanvasWidgets.THEME_FRAMES_JS.contains("fl:theme"))
        val frame = WidgetRouting.frameScript("/*c*/", "https://canvas.app.deft.internal", "n")
        assertTrue(frame.contains("m==='deft:theme'") && frame.contains("location.reload()") && frame.contains("e.source!==parent"))
    }

    @Test
    fun stampChangesWithFilesAndDiffNamesChangedApps() {
        val dir = java.nio.file.Files.createTempDirectory("stamp").toFile()
        val f = java.io.File(dir, "a.html").apply { writeText("one"); setLastModified(1_000_000L) }
        val before = CanvasWidgets.stamp(listOf(f))
        assertEquals(before, CanvasWidgets.stamp(listOf(f)))
        f.writeText("longer"); f.setLastModified(2_000_000L)
        val after = CanvasWidgets.stamp(listOf(f))
        assertTrue(before != after)
        assertEquals(emptySet<String>(), CanvasWidgets.changedStamps(null, mapOf("o1" to 1L)))
        assertEquals(setOf("o2"), CanvasWidgets.changedStamps(mapOf("o1" to 1L, "o2" to 2L), mapOf("o1" to 1L, "o2" to 3L, "o3" to 9L)))
        dir.deleteRecursively()
    }
}
