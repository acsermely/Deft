package app.deft.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every launcher approval screen hides overlays and drops obscured touches (tapjacking). */
class OverlayGuardTest {
    @Test
    fun approvalScreensCallProtect() {
        val root = "src/main/java/app/deft/ui"
        for (path in listOf(
            "consent/ConsentActivity.kt", "confirm/ConfirmActivity.kt", "special/SpecialGrantActivity.kt",
            "keystore/KeystoreAuthActivity.kt", "handlers/HandlerPickerActivity.kt", "dashboard/DashboardActivity.kt",
        )) {
            val source = File("$root/$path").readText()
            assertTrue(path, Regex("""super\.onCreate\(savedInstanceState\)\s+OverlayGuard\.protect\(this\)""").containsMatchIn(source))
        }
    }
}
