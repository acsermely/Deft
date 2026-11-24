package app.deft.bridge.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthBrowserTest {
    private val own = "app.deft"
    private val pick = AuthModule.Companion::pickBrowser

    @Test
    fun launcherIsNeverPickedEvenAsDefault() {
        assertEquals("org.mozilla.firefox", pick(own, listOf(own, "org.mozilla.firefox"), setOf("org.mozilla.firefox"), own))
        assertNull(pick(own, listOf(own), emptySet(), own))
    }

    @Test
    fun defaultBrowserWithCustomTabsWins() {
        assertEquals("com.android.chrome", pick("com.android.chrome", listOf("org.mozilla.firefox", "com.android.chrome"), setOf("com.android.chrome", "org.mozilla.firefox"), own))
    }

    @Test
    fun noDefaultPrefersACustomTabsBrowser() {
        assertEquals("com.android.chrome", pick("android", listOf("org.lite.browser", "com.android.chrome"), setOf("com.android.chrome"), own))
        assertEquals("org.lite.browser", pick(null, listOf("org.lite.browser"), emptySet(), own))
    }

    @Test
    fun noBrowserAtAll() {
        assertNull(pick(null, emptyList(), emptySet(), own))
        assertNull(pick("android", emptyList(), emptySet(), own))
    }
}
