package app.deft.webview

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeftLintEngineTest {
    @Test
    fun flagsInnerHtml() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = DeftLintEngine.run(ctx, mapOf("main.js" to "el.innerHTML = x;\n"), "app")
        val text = DeftLintFormat.lines(DeftLintFormat.parse(raw))
        assertTrue(text, text.contains("FL100"))
        DeftLintEngine.close()
    }
}
