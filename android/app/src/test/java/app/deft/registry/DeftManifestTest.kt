package app.deft.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeftManifestTest {
    @Test
    fun parsesV1() {
        val parsed = DeftManifestParser.parse(
            """
            {
              "deft": 1,
              "id": "notes",
              "name": "Notes",
              "version": "1.0.0",
              "profile": "myapp",
              "start_url": "./index.html",
              "capabilities": [
                { "id": "files.read", "reason": "Open notes the user picked" }
              ],
              "network": []
            }
            """.trimIndent(),
        )
        assertEquals("notes", parsed.id)
        assertEquals(1, parsed.capabilities.size)
        assertEquals("files.read", parsed.capabilities[0].first.id)
    }

    @Test
    fun rejectsRequiredCapabilities() {
        val err = runCatching {
            DeftManifestParser.parse(
                """{"deft":1,"id":"x","name":"X","requiredCapabilities":["storage"]}""",
            )
        }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertTrue(err!!.message!!.contains("requiredCapabilities"))
    }

    @Test
    fun rejectsUnknownCapability() {
        val err = runCatching {
            DeftManifestParser.parse(
                """
                {
                  "deft": 1,
                  "id": "notes",
                  "name": "Notes",
                  "capabilities": [{ "id": "storage", "reason": "Old id that must fail now" }]
                }
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertTrue(err!!.message!!.contains("unknown capability"))
    }

    @Test
    fun parsesDependencies() {
        val parsed = DeftManifestParser.parse(
            """
            {
              "deft": 1, "id": "game", "name": "Game", "version": "1.0.0", "start_url": "./index.html",
              "dependencies": {
                "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js": "sha256-PmkKx9GAsKrfCJG+o57sZD4p4tPnXJmxholRhmX2m6Y=",
                "https://unpkg.com/a@1.0.0/a.js": { "integrity": null, "as": "a" },
                "https://esm.sh/b@2.0.0": null
              }
            }
            """.trimIndent(),
        )
        val deps = parsed.dependencies.associateBy { it.url }
        assertEquals(3, deps.size)
        assertEquals("cdn.jsdelivr.net", deps.values.first().host)
        assertEquals("a", deps["https://unpkg.com/a@1.0.0/a.js"]?.alias)
        assertEquals(null, deps["https://esm.sh/b@2.0.0"]?.integrity)
    }

    @Test
    fun rejectsBadDependencies() {
        fun error(deps: String): String = runCatching {
            DeftManifestParser.parseDependencies(kotlinx.serialization.json.Json.parseToJsonElement(deps))
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue(error("""{"http://cdn.example.com/x@1.0.0/x.js": null}""").contains("https file URL"))
        assertTrue(error("""{"https://cdn.example.com/x@1.0.0/x.js?module": null}""").contains("https file URL"))
        assertTrue(error("""{"https://cdn.example.com/x@1.0.0/x.css": null}""").contains("JavaScript module"))
        assertTrue(error("""{"https://cdn.example.com/x@1.0.0/x.js": "md5-abc"}""").contains("hash must be"))
        val canvas = runCatching {
            DeftManifestParser.parse(
                """{"deft":1,"id":"home","name":"Home","profile":"canvas","dependencies":{"https://cdn.example.com/x@1.0.0/x.js":null}}""",
            )
        }
        assertTrue(canvas.exceptionOrNull()?.message.orEmpty().contains("canvas forbids dependencies"))
    }

    @Test
    fun networkOriginsMatchLintFl704() {
        val bad = listOf(
            "https://127.0.0.1", "https://10.0.0.5:8443", "https://localhost", "https://api.localhost",
            "https://x.app.deft.internal", "https://router", "https://a..b.com", "https://.example.com",
            "http://example.com", "https://example.com/path", "https://*.example.com",
        )
        for (entry in bad) assertFalse(entry, DeftManifestParser.validNetworkOrigin(entry))
        assertTrue(DeftManifestParser.validNetworkOrigin("https://api.example.com"))
        assertTrue(DeftManifestParser.validNetworkOrigin("https://feeds.example.org:8443"))
    }

    @Test
    fun shortcutsMatchLintFl708() {
        assertTrue(DeftManifestParser.validShortcutUrl("./#new"))
        assertTrue(DeftManifestParser.validShortcutUrl("/compose?to=me"))
        for (bad in listOf("https://evil.example/", "//evil.example", "./../x", "javascript:alert(1)", "/_deft/v1/bridge.js", "./a b")) {
            assertFalse(bad, DeftManifestParser.validShortcutUrl(bad))
        }
        val parsed = DeftManifestParser.parseShortcuts(
            kotlinx.serialization.json.Json.parseToJsonElement("""[{"id":"new","name":"New note","url":"./#new"}]"""),
        )
        assertEquals("New note", parsed.single().name)
    }

    private fun withEmbeds(embeds: String, profile: String = "myapp") =
        """{"deft":1,"id":"pay","name":"Pay","profile":"$profile","embeds":$embeds}"""

    @Test
    fun parsesEmbeds() {
        val parsed = DeftManifestParser.parse(
            withEmbeds("""["https://js.stripe.com","https://*.paypal.com","https://js.stripe.com","https://a.example.com:8443"]"""),
        )
        assertEquals(listOf("https://js.stripe.com", "https://*.paypal.com", "https://a.example.com:8443"), parsed.embeds)
        assertEquals(emptyList<String>(), DeftManifestParser.parse("""{"deft":1,"id":"pay","name":"Pay"}""").embeds)
    }

    @Test
    fun embedOriginRule() {
        val ok = listOf("https://js.stripe.com", "https://*.js.stripe.com", "https://*.paypal.com", "https://pay.example.com:8443")
        val bad = listOf(
            "http://js.stripe.com",
            "https://js.stripe.com/",
            "https://js.stripe.com/v3",
            "https://*.com",
            "https://*",
            "*",
            "https://a.*.example.com",
            "https://*.*.example.com",
            "https://*example.com",
            "https://192.168.1.1",
            "https://*.1.1.1.1",
            "https://localhost",
            "https://*.localhost",
            "https://a.app.deft.internal",
            "https://*.deft.internal",
            "https://JS.STRIPE.COM",
            "https://js..stripe.com",
            "wss://js.stripe.com",
            "",
        )
        for (o in ok) assertTrue(o, DeftManifestParser.validEmbedOrigin(o))
        for (o in bad) assertFalse(o, DeftManifestParser.validEmbedOrigin(o))
    }

    @Test
    fun wildcardEmbedOverAPublicSuffixIsRefused() {
        val bad = listOf(
            "https://*.github.io", "https://*.co.uk", "https://*.com.au", "https://*.co.jp", "https://*.gov.uk",
            "https://*.herokuapp.com", "https://*.s3.amazonaws.com", "https://*.amazonaws.com", "https://*.web.app",
            "https://*.pages.dev:8443",
        )
        for (o in bad) assertFalse(o, DeftManifestParser.validEmbedOrigin(o))
        val ok = listOf(
            "https://*.myname.github.io", "https://*.shop.co.uk", "https://*.example.com", "https://*.ad.nl",
            "https://github.io", "https://co.uk", "https://*.co.com", "https://*.paypal.com",
        )
        for (o in ok) assertTrue(o, DeftManifestParser.validEmbedOrigin(o))
        assertTrue(EmbedSuffixes.publicSuffix("org.uk"))
        assertFalse(EmbedSuffixes.publicSuffix("example.uk"))
    }

    @Test
    fun rejectsBadEmbeds() {
        fun error(raw: String) = runCatching { DeftManifestParser.parse(raw) }.exceptionOrNull()?.message.orEmpty()
        assertTrue(error(withEmbeds("""["http://js.stripe.com"]""")).contains("invalid embed origin"))
        assertTrue(error(withEmbeds("""["https://10.0.0.1"]""")).contains("invalid embed origin"))
        assertTrue(error(withEmbeds("\"https://js.stripe.com\"")).contains("embeds must be an array"))
        assertTrue(error(withEmbeds("[1]")).contains("embeds must be an array"))
        val seventeen = (1..17).joinToString(",", "[", "]") { "\"https://s$it.example.com\"" }
        assertTrue(error(withEmbeds(seventeen)).contains("at most 16"))
        val sixteen = (1..16).joinToString(",", "[", "]") { "\"https://s$it.example.com\"" }
        assertEquals(16, DeftManifestParser.parse(withEmbeds(sixteen)).embeds.size)
        assertTrue(error(withEmbeds("""["https://js.stripe.com"]""", profile = "canvas")).contains("canvas forbids embeds"))
    }

    private fun withBackground(background: String, profile: String = "myapp") =
        """{"deft":1,"id":"bg","name":"Bg","profile":"$profile","background":$background}"""

    @Test
    fun parsesBackground() {
        val parsed = DeftManifestParser.parse(
            withBackground("""{"module":"./bg.js","triggers":["sms","periodic:30","sms","notify.action"]}"""),
        )
        assertEquals("/bg.js", parsed.background?.module)
        assertEquals(listOf("sms", "periodic:30", "notify.action"), parsed.background?.triggers)
        assertEquals(null, DeftManifestParser.parse(withBackground("null")).background)
        assertEquals("/jobs/run.mjs", DeftManifestParser.parse(withBackground("""{"module":"/jobs/run.mjs","triggers":[]}""")).background?.module)
    }

    @Test
    fun backgroundModuleRule() {
        for (good in listOf("./bg.js", "/bg.js", "./work/bg.mjs", "/a-b_c.d~e.js")) {
            assertTrue(good, DeftManifestParser.validBackgroundModule(good))
        }
        val bad = listOf(
            "bg.js", "../bg.js", "./../bg.js", "//cdn.example.com/bg.js", "https://x.example.com/bg.js",
            "/_deft/v1/bg.js", "./_DEFT/x.js", "/bg.ts", "/bg.js?x=1", "/bg.js#a", "/b g.js", "/bg\\x.js",
            "./a//b.js", "/\"x.js", "/<x>.js", "/bg.json", "/", "",
        )
        for (b in bad) assertFalse(b, DeftManifestParser.validBackgroundModule(b))
    }

    @Test
    fun oldFlNamesAreNotSpecial() {
        // Only /_deft/ is reserved; the old /_fl/ runtime is gone, so _fl is an ordinary folder name.
        assertTrue(DeftManifestParser.validBackgroundModule("./_fl/bg.js"))
        assertTrue(DeftManifestParser.validWallpaperPage("/_fl/w.html"))
        // The old "fl" version key no longer counts as "deft".
        val old = runCatching { DeftManifestParser.parse("""{"fl":1,"id":"notes","name":"Notes","version":"1.0.0","start_url":"./index.html","capabilities":[],"network":[]}""") }
        assertTrue(old.exceptionOrNull()?.message.orEmpty().contains("missing deft"))
    }

    @Test
    fun backgroundTriggerRule() {
        for (good in listOf("alarms", "sms", "calls", "notify.action", "boot", "network", "charging", "push", "geofence", "sync", "notification", "screening", "periodic:15", "periodic:10080")) {
            assertTrue(good, DeftManifestParser.validBackgroundTrigger(good))
        }
        for (bad in listOf("periodic:14", "periodic:0", "periodic:", "periodic", "periodic:10081", "periodic:-5", "periodic:1.5", "manual", "SMS", "")) {
            assertFalse(bad, DeftManifestParser.validBackgroundTrigger(bad))
        }
    }

    @Test
    fun rejectsBadBackground() {
        fun error(raw: String) = runCatching { DeftManifestParser.parse(raw) }.exceptionOrNull()?.message.orEmpty()
        assertTrue(error(withBackground("\"./bg.js\"")).contains("background must be"))
        assertTrue(error(withBackground("""{"triggers":["sms"]}""")).contains("background.module"))
        assertTrue(error(withBackground("""{"module":"../bg.js","triggers":["sms"]}""")).contains("background.module"))
        assertTrue(error(withBackground("""{"module":"./bg.js"}""")).contains("background.triggers"))
        assertTrue(error(withBackground("""{"module":"./bg.js","triggers":["periodic:5"]}""")).contains("periodic"))
        assertTrue(error(withBackground("""{"module":"./bg.js","triggers":[7]}""")).contains("background.triggers"))
        assertTrue(error(withBackground("""{"module":"./bg.js","triggers":["periodic:15","periodic:30"]}""")).contains("one periodic"))
        assertTrue(error(withBackground("""{"module":"./bg.js","triggers":["sms"]}""", profile = "canvas")).contains("canvas"))
    }

    // Manifest wallpaper {page}.
    private fun withWallpaper(wallpaper: String, profile: String = "myapp") =
        """{"deft":1,"id":"wp","name":"Wp","profile":"$profile","wallpaper":$wallpaper}"""

    @Test
    fun parsesWallpaper() {
        assertEquals("/wallpaper.html", DeftManifestParser.parse(withWallpaper("""{"page":"./wallpaper.html"}""")).wallpaper?.page)
        assertEquals("/wp/clock.htm", DeftManifestParser.parse(withWallpaper("""{"page":"/wp/clock.htm"}""")).wallpaper?.page)
        assertEquals(null, DeftManifestParser.parse(withWallpaper("null")).wallpaper)
        assertEquals(null, DeftManifestParser.parse("""{"deft":1,"id":"wp","name":"Wp"}""").wallpaper)
    }

    @Test
    fun wallpaperPageRule() {
        for (good in listOf("./wallpaper.html", "/wallpaper.html", "./a/b-c_d~e.htm", "/W.html")) {
            assertTrue(good, DeftManifestParser.validWallpaperPage(good))
        }
        val bad = listOf(
            "wallpaper.html", "../w.html", "./../w.html", "//cdn.example.com/w.html", "https://x.example.com/w.html",
            "/_deft/v1/w.html", "./_DEFT/w.html", "/w.js", "/w.html?x=1", "/w.html#a", "/w x.html", "./a//w.html",
            "/<x>.html", "", "/" + "a".repeat(260) + ".html",
        )
        for (b in bad) assertFalse(b, DeftManifestParser.validWallpaperPage(b))
    }

    // Manifest keyboard {page}.
    @Test
    fun parsesKeyboard() {
        fun withKeyboard(keyboard: String, profile: String = "myapp") =
            """{"deft":1,"id":"kb","name":"Kb","profile":"$profile","keyboard":$keyboard}"""
        assertEquals("/keyboard/index.html", DeftManifestParser.parse(withKeyboard("""{"page":"./keyboard/index.html"}""")).keyboard?.page)
        assertEquals(null, DeftManifestParser.parse(withKeyboard("null")).keyboard)
        assertEquals(null, DeftManifestParser.parse("""{"deft":1,"id":"kb","name":"Kb"}""").keyboard)
        fun error(raw: String) = runCatching { DeftManifestParser.parse(raw) }.exceptionOrNull()?.message.orEmpty()
        assertTrue(error(withKeyboard("\"./k.html\"")).contains("keyboard must be"))
        assertTrue(error(withKeyboard("""{}""")).contains("keyboard.page"))
        assertTrue(error(withKeyboard("""{"page":"../k.html"}""")).contains("keyboard.page"))
        assertTrue(error(withKeyboard("""{"page":"/_deft/k.html"}""")).contains("keyboard.page"))
        assertTrue(error(withKeyboard("""{"page":"./k.html"}""", profile = "canvas")).contains("canvas"))
    }

    @Test
    fun rejectsBadWallpaper() {
        fun error(raw: String) = runCatching { DeftManifestParser.parse(raw) }.exceptionOrNull()?.message.orEmpty()
        assertTrue(error(withWallpaper("\"./wallpaper.html\"")).contains("wallpaper must be"))
        assertTrue(error(withWallpaper("""{}""")).contains("wallpaper.page"))
        assertTrue(error(withWallpaper("""{"page":7}""")).contains("wallpaper.page"))
        assertTrue(error(withWallpaper("""{"page":"../w.html"}""")).contains("wallpaper.page"))
        assertTrue(error(withWallpaper("""{"page":"./w.html"}""", profile = "canvas")).contains("canvas"))
    }

    // W1: manifest widget {page, sizes}.
    private fun withWidget(widget: String, profile: String = "myapp") =
        """{"deft":1,"id":"wg","name":"Wg","profile":"$profile","widget":$widget}"""

    @Test
    fun parsesWidget() {
        val parsed = DeftManifestParser.parse(withWidget("""{"page":"./widget.html","sizes":["2x1","2x2","4x2"]}"""))
        assertEquals("/widget.html", parsed.widget?.page)
        assertEquals(listOf("2x1", "2x2", "4x2"), parsed.widget?.sizes)
        assertEquals(listOf("2x1"), DeftManifestParser.parse(withWidget("""{"page":"/w.htm","sizes":["2x1","2x1"]}""")).widget?.sizes)
        assertEquals(null, DeftManifestParser.parse(withWidget("null")).widget)
        assertEquals(null, DeftManifestParser.parse("""{"deft":1,"id":"wg","name":"Wg"}""").widget)
    }

    @Test
    fun rejectsBadWidget() {
        fun error(raw: String) = runCatching { DeftManifestParser.parse(raw) }.exceptionOrNull()?.message.orEmpty()
        assertTrue(error(withWidget("\"./widget.html\"")).contains("widget must be"))
        assertTrue(error(withWidget("""{"sizes":["2x1"]}""")).contains("widget.page"))
        assertTrue(error(withWidget("""{"page":"../w.html","sizes":["2x1"]}""")).contains("widget.page"))
        assertTrue(error(withWidget("""{"page":"./w.html"}""")).contains("widget.sizes"))
        assertTrue(error(withWidget("""{"page":"./w.html","sizes":"2x1"}""")).contains("widget.sizes"))
        assertTrue(error(withWidget("""{"page":"./w.html","sizes":[]}""")).contains("must not be empty"))
        assertTrue(error(withWidget("""{"page":"./w.html","sizes":["3x3"]}""")).contains("unknown widget size"))
        assertTrue(error(withWidget("""{"page":"./w.html","sizes":[2]}""")).contains("array of strings"))
        assertTrue(error(withWidget("""{"page":"./w.html","sizes":["2x1"]}""", profile = "canvas")).contains("canvas"))
    }
}
