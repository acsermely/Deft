package app.deft.roles

import app.deft.catalog.Capability
import app.deft.catalog.Risk
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleSpecTest {
    @Test
    fun browserUrlAcceptsHttpAndHttps() {
        for (good in listOf(
            "https://example.com",
            "http://example.com/a?b=c#d",
            "HTTPS://Example.com/",
            "https://user@example.com:8443/path",
            "http://[::1]/",
        )) {
            assertTrue(good, RoleSpec.validBrowserUrl(good))
        }
    }

    @Test
    fun browserUrlRejectsEverythingElse() {
        for (bad in listOf(
            null,
            "",
            "ftp://example.com",
            "javascript:alert(1)",
            "intent://x#Intent;end",
            "file:///sdcard/a.html",
            "content://x/y",
            "https:example.com",
            "https://",
            "https:///path",
            "https://exa mple.com",
            "https://example.com/\n",
            "https://example.com/\u0000",
            "https://example.com/\u007f",
            "web+deft:x",
            "example.com",
        )) {
            assertFalse("$bad", RoleSpec.validBrowserUrl(bad))
        }
    }

    @Test
    fun browserUrlIsCappedAtEightKib() {
        val base = "https://example.com/"
        assertTrue(RoleSpec.validBrowserUrl(base + "a".repeat(RoleSpec.MAX_URL - base.length)))
        assertFalse(RoleSpec.validBrowserUrl(base + "a".repeat(RoleSpec.MAX_URL - base.length + 1)))
        assertEquals(8192, RoleSpec.MAX_URL)
    }

    @Test
    fun browserPayloadIsUrlData() {
        val obj = Json.parseToJsonElement(RoleSpec.browserPayload("https://example.com/x")).jsonObject
        assertEquals("url", obj["type"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/x", obj["url"]!!.jsonPrimitive.content)
        assertTrue(obj["items"]!!.jsonArray.isEmpty())
    }

    @Test
    fun componentsFollowHeldGroups() {
        assertTrue(RoleSpec.enabledComponents(emptySet()).isEmpty())
        assertEquals(setOf(RoleComponent.BROWSER_VIEW), RoleSpec.enabledComponents(setOf("role-browser")))
        assertEquals(
            setOf(
                RoleComponent.ASSIST_SERVICE,
                RoleComponent.ASSIST_SESSION,
                RoleComponent.ASSIST_RECOGNIZER,
                RoleComponent.ASSIST_ENTRY,
            ),
            RoleSpec.enabledComponents(setOf("role-assistant")),
        )
        assertEquals(setOf(RoleComponent.WALLPAPER_SERVICE), RoleSpec.enabledComponents(setOf("role-wallpaper")))
        assertEquals(setOf(RoleComponent.IME_SERVICE), RoleSpec.enabledComponents(setOf("role-ime")))
        assertEquals(setOf(RoleComponent.CALL_SCREENING_SERVICE), RoleSpec.enabledComponents(setOf("role-call-screening")))
        assertTrue(RoleSpec.enabledComponents(setOf("a11y", "sms")).isEmpty())
    }

    @Test
    fun everyRoleComponentBelongsToAnExclusiveSpecialCapability() {
        for (component in RoleComponent.entries) {
            assertEquals(Risk.SPECIAL, component.cap.risk)
            assertTrue(component.name, component.cap.exclusiveGroup != null)
            assertEquals(component, RoleComponent.forClassName(component.className))
        }
        assertEquals(null, RoleComponent.forClassName("app.deft.ui.roles.RoleEntryActivity"))
        assertEquals(Capability.ROLE_BROWSER, RoleComponent.BROWSER_VIEW.cap)
    }

    @Test
    fun recognizerNeverPicksTheLauncherItself() {
        val self = "app.deft.debug"
        assertEquals(null, RoleSpec.pickRecognizer(emptyList(), self))
        assertEquals(null, RoleSpec.pickRecognizer(listOf("$self/app.deft.assist.DeftRecognitionService"), self))
        assertEquals(
            "org.vosk/org.vosk.Service",
            RoleSpec.pickRecognizer(listOf("$self/app.deft.assist.DeftRecognitionService", "org.vosk/org.vosk.Service"), self),
        )
    }

    @Test
    fun recognizerPrefersKnownOnesThenName() {
        val self = "app.deft"
        val list = listOf(
            "zz.other/zz.other.R",
            "com.google.android.tts/com.google.android.tts.R",
            "aa.first/aa.first.R",
            "com.google.android.googlequicksearchbox/com.google.android.voicesearch.R",
            "broken-without-slash",
        )
        assertEquals("com.google.android.googlequicksearchbox/com.google.android.voicesearch.R", RoleSpec.pickRecognizer(list, self))
        assertEquals("aa.first/aa.first.R", RoleSpec.pickRecognizer(listOf("zz.other/zz.other.R", "aa.first/aa.first.R"), self))
    }

    @Test
    fun assistEventCarriesOnlyItsSource() {
        assertEquals("assist.invoked", RoleSpec.ASSIST_EVENT)
        for (source in listOf("assist-gesture", "intent")) {
            val payload = Json.parseToJsonElement(RoleSpec.assistPayload(source)).jsonObject
            assertEquals(setOf("source"), payload.keys)
            assertEquals(source, payload["source"]!!.jsonPrimitive.content)
        }
        assertTrue(runCatching { RoleSpec.assistPayload("screen") }.isFailure)
    }

    @Test
    fun lockIsLimitedToOnceEvery30Seconds() {
        val t = 1_700_000_000_000L
        assertTrue(RoleSpec.lockAllowed(null, t))
        assertFalse(RoleSpec.lockAllowed(t, t))
        assertFalse(RoleSpec.lockAllowed(t, t + 29_999))
        assertTrue(RoleSpec.lockAllowed(t, t + 30_000))
        assertTrue("clock went back", RoleSpec.lockAllowed(t, t - 1))
    }

    @Test
    fun deviceAdminDeclaresOnlyForceLock() {
        val file = File("src/main/res/xml/device_admin.xml")
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val text = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val policies = Regex("<([a-z-]+)\\s*/>").findAll(text.substringAfter("<uses-policies>").substringBefore("</uses-policies>"))
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("force-lock"), policies)
        assertFalse("wipe-data" in text)
    }

    @Test
    fun deviceAdminIsExclusiveSpecial() {
        assertEquals(Risk.SPECIAL, Capability.DEVICE_ADMIN.risk)
        assertEquals("device-admin", Capability.DEVICE_ADMIN.exclusiveGroup)
        assertTrue(RoleComponent.entries.none { it.cap == Capability.DEVICE_ADMIN })
    }

    @Test
    fun keyboardServiceIsDisabledUntilAHolderExists() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = manifest.substringAfter("android:name=\".ime.DeftInputMethodService\"").substringBefore("</service>")
        assertTrue("android:enabled=\"false\"" in service)
        assertTrue("android.permission.BIND_INPUT_METHOD" in service)
        assertTrue("@xml/method" in service)
        assertTrue(File("src/main/res/xml/method.xml").exists())
        assertEquals(Capability.IME, RoleComponent.IME_SERVICE.cap)
        assertEquals("app.deft.ime.DeftInputMethodService", RoleComponent.IME_SERVICE.className)
    }

    @Test
    fun wallpaperServiceIsDisabledUntilAHolderExists() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = manifest.substringAfter("android:name=\".wallpaper.DeftWallpaperService\"").substringBefore("</service>")
        assertTrue("android:enabled=\"false\"" in service)
        assertTrue("android.permission.BIND_WALLPAPER" in service)
        assertEquals(Capability.WALLPAPER, RoleComponent.WALLPAPER_SERVICE.cap)
        assertEquals("app.deft.wallpaper.DeftWallpaperService", RoleComponent.WALLPAPER_SERVICE.className)
    }
}
