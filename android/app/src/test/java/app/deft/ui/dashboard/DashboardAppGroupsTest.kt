package app.deft.ui.dashboard

import app.deft.data.WebAppEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardAppGroupsTest {
    private fun app(id: String, name: String, tier: String = "local", slug: String? = null) = WebAppEntity(
        id = id, name = name, startUrl = "https://x/", origin = "https://x", manifestUrl = "https://x/m.json",
        requiredCapabilitiesJson = "[]", workspaceSlug = slug, tier = tier,
    )

    @Test
    fun everyAppLandsInExactlyOneGroupAndLocalAppsAreListed() {
        val tape = app("myapp:measuring-tape", "Measuring Tape", slug = "measuring-tape")
        val steps = app("myapp:steps", "steps", slug = "steps")
        val web = app("https://example.org", "Example")
        val clock = app("bundled:clock", "Clock", tier = "bundled")
        val oldBundled = app("bundled:files", "Files")
        val groups = DashboardAppGroups.of(listOf(clock, tape, web, oldBundled, steps))
        assertEquals(listOf(tape, steps), groups.mine)
        assertEquals(listOf(web), groups.installed)
        assertEquals(listOf(clock, oldBundled), groups.bundled)
    }
}
