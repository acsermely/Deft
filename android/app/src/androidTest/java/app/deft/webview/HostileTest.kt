package app.deft.webview

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.deft.bridge.BridgeHostTestActivity
import app.deft.bridge.DeftJsBridge
import app.deft.bridge.GrantedPrefixes
import app.deft.DeftApp
import app.deft.catalog.Capability
import app.deft.catalog.GrantLevel
import app.deft.catalog.isGranted
import app.deft.data.SpecialGrantResult
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostileTest {
    @Test
    fun inlineScriptDoesNotRunUnderCsp() {
        val probe = loadPage(
            host = "hostile-inline.app.deft.internal",
            html = """<!doctype html><html><head>
                <script>window.__deftHostileInline = true;</script>
                </head><body></body></html>""",
            expr = "!!window.__deftHostileInline",
        )
        assertFalse("inline script must not run: $probe", probe.contains("true"))
    }

    @Test
    fun evalIsBlockedOrFails() {
        val probe = loadPage(
            host = "hostile-eval.app.deft.internal",
            html = """<!doctype html><html><head>
                <script type="module" src="./main.js"></script>
                </head><body></body></html>""",
            extra = mapOf(
                "main.js" to """try{window.__deftHostileEval=eval("1+1")}catch(e){window.__deftHostileEval="blocked:"+e.name}""",
            ),
            expr = "String(window.__deftHostileEval)",
        )
        assertTrue(
            "eval should be blocked or marked blocked: $probe",
            probe.contains("blocked") || probe.contains("null") || probe.contains("undefined"),
        )
    }

    @Test
    fun undeclaredFetchIsBlocked() {
        val probe = loadPage(
            host = "hostile-fetch.app.deft.internal",
            html = """<!doctype html><html><head>
                <script type="module" src="./main.js"></script>
                </head><body></body></html>""",
            extra = mapOf(
                "main.js" to """
                    window.__deftFetch = 'pending';
                    fetch('https://example.com/x').then(function(){
                      window.__deftFetch = 'ok';
                    }).catch(function(e){
                      window.__deftFetch = 'blocked:' + (e && e.name);
                    });
                """.trimIndent(),
            ),
            expr = "String(window.__deftFetch)",
            policy = CspPolicy(),
        )
        assertTrue(
            "undeclared fetch should fail: $probe",
            probe.contains("blocked") || probe.contains("pending") || probe.contains("TypeError"),
        )
    }

    @Test
    fun serviceWorkerRegisterFails() {
        val probe = loadPage(
            host = "hostile-sw.app.deft.internal",
            html = """<!doctype html><html><head>
                <script type="module" src="./main.js"></script>
                </head><body></body></html>""",
            extra = mapOf(
                "main.js" to """
                    window.__deftSw = 'none';
                    try {
                      navigator.serviceWorker.register('./sw.js').then(function(){
                        window.__deftSw = 'registered';
                      }).catch(function(){
                        window.__deftSw = 'rejected';
                      });
                    } catch (e) { window.__deftSw = 'threw'; }
                """.trimIndent(),
            ),
            expr = "String(window.__deftSw)",
        )
        assertTrue(
            "service worker must not register: $probe",
            probe.contains("rejected") || probe.contains("threw") || probe.contains("none"),
        )
    }

    @Test
    fun windowOpenReturnsNull() {
        val probe = loadPage(
            host = "hostile-open.app.deft.internal",
            html = """<!doctype html><html><head>
                <script type="module" src="./main.js"></script>
                </head><body></body></html>""",
            extra = mapOf(
                "main.js" to """window.__deftOpen = String(window.open('https://example.com'));""",
            ),
            expr = "String(window.__deftOpen)",
        )
        assertTrue("window.open should be null: $probe", probe.contains("null"))
    }

    @Test
    fun originsDoNotShareLocalStorage() {
        val a = "https://hostile-a.app.deft.internal"
        val b = "https://hostile-b.app.deft.internal"
        writeThenReadStorage(a, "secret-a")
        val other = writeThenReadStorage(b, null)
        assertFalse("b must not see a's localStorage: $other", other.contains("secret-a"))
    }

    @Test
    fun agentRejectsRuntimeDirWrite() {
        val err = runCatching {
            app.deft.workspace.DeftPaths.parse("_deft/core.js")
        }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertTrue(err!!.message!!.contains("runtime"))
        val parent = runCatching {
            app.deft.workspace.DeftPaths.parse("../secret.txt")
        }.exceptionOrNull()
        assertTrue(parent is IllegalArgumentException)
    }

    @Test
    fun foreignOriginHasNoBridge() {
        assumeTrue(DeftJsBridge.webViewSupported())
        launchBridge(allowed = "contacts.read", listener = "https://app.example").use { scenario ->
            val state = awaitBridge(scenario)
            assertFalse(state.optBoolean("hasBridge"))
            assertFalse(state.optBoolean("hasPort"))
            assertEquals("no-bridge", state.optString("error"))
        }
    }

    @Test
    fun undeclaredCapabilityIsNotGranted() {
        assumeTrue(DeftJsBridge.webViewSupported())
        launchBridge(allowed = "contacts.read").use { scenario ->
            awaitBridge(scenario)
            val code = invokeOnBridge(scenario, "sms.send", """{to:'+15555550100', body:'no'}""")
            assertEquals("not_granted", code)
        }
    }

    @Test
    fun highRiskDenyDoesNotSend() {
        assumeTrue(DeftJsBridge.webViewSupported())
        launchBridge(allowed = "sms.send", bindService = true).use { scenario ->
            awaitBridge(scenario)
            val code = invokeOnBridge(scenario, "sms.send", """{to:'+15555550100', body:'no'}""")
            assertEquals("not_granted", code)
        }
    }

    /** Install and the consent screen write special access as DENIED, whatever they ask for. */
    @Test
    fun specialCannotBeGrantedFromConsent() {
        val store = DeftApp.instance.myApps
        val repo = DeftApp.instance.repository
        val slug = "hostile-special"
        val dir = store.projectDir(slug).also { it.deleteRecursively(); it.mkdirs() }
        File(dir, "index.html").writeText("<!doctype html><html><body></body></html>")
        File(dir, "manifest.json").writeText(
            """{"deft":1,"id":"hostile-special","name":"hostile-special","version":"0.1.0","profile":"myapp",""" +
                """"start_url":"./index.html","capabilities":[{"id":"apps.list","reason":"Lists your apps for a test."},""" +
                """{"id":"haptics","reason":"Buzzes for a test."}],"network":[]}""",
        )
        val ids = mutableListOf<String>()
        try {
            runBlocking {
                // The consent screen with every box ticked and Allow tapped.
                val shown = listOf(Capability.APPS_LIST, Capability.HAPTICS)
                val app = repo.applyWorkspaceConsent(slug, shown, shown).also { ids += it.id }
                assertEquals(GrantLevel.DENIED, repo.grantLevel(app.id, Capability.APPS_LIST))
                assertTrue(repo.grantLevel(app.id, Capability.HAPTICS).isGranted())
                // It is listed once ("Asks later"); the DENIED row then settles it, so no consent loop.
                assertTrue(repo.ungrantedCapabilities(app.id, shown).isEmpty())
                assertTrue(repo.capabilitiesNeedingConsent(app.id, shown).isEmpty())

                // Install with an explicit grant map.
                val urlApp = repo.install(
                    name = "hostile-special-url",
                    startUrl = "https://hostile-special.app.deft.internal/",
                    origin = "https://hostile-special.app.deft.internal",
                    manifestUrl = "https://hostile-special.app.deft.internal/manifest.json",
                    capabilities = listOf(Capability.APPS_LIST),
                    grants = mapOf(Capability.APPS_LIST to GrantLevel.WHILE_USING),
                ).also { ids += it.id }
                assertEquals(GrantLevel.DENIED, repo.grantLevel(urlApp.id, Capability.APPS_LIST))
                // The ordinary dashboard toggle cannot grant it either.
                repo.setGrant(urlApp.id, Capability.APPS_LIST, GrantLevel.WHILE_USING)
                assertEquals(GrantLevel.DENIED, repo.grantLevel(urlApp.id, Capability.APPS_LIST))
                // Only the special sheet's path can.
                assertEquals(SpecialGrantResult.Granted, repo.grantSpecial(urlApp.id, Capability.APPS_LIST))
                assertTrue(repo.grantLevel(urlApp.id, Capability.APPS_LIST).isGranted())
                // An app that does not declare a capability cannot get it.
                assertEquals(SpecialGrantResult.Refused, repo.grantSpecial(app.id, Capability.USAGE_READ))
            }
        } finally {
            runBlocking {
                val db = DeftApp.instance.database
                for (id in ids) {
                    db.grants().deleteForApp(id)
                    db.webApps().delete(id)
                }
            }
            runCatching { store.projectDir(slug).deleteRecursively() }
        }
    }

    /**
     * A special grant the user turned off is remembered, so a bridge call only asks again for
     * "asks later" rows. Only the sheet's screen lock (grantSpecial) grants, and it clears the remembered denial.
     */
    @Test
    fun specialDenialByUserIsRememberedAndAsksLaterIsNot() {
        val repo = DeftApp.instance.repository
        val ids = mutableListOf<String>()
        try {
            runBlocking {
                val asks = repo.install(
                    name = "hostile-denial",
                    startUrl = "https://hostile-denial.app.deft.internal/",
                    origin = "https://hostile-denial.app.deft.internal",
                    manifestUrl = "https://hostile-denial.app.deft.internal/manifest.json",
                    capabilities = listOf(Capability.APPS_LIST),
                    grants = emptyMap(),
                ).also { ids += it.id }
                // The install placeholder is "asks later": DENIED, but not a denial by the user.
                assertEquals(GrantLevel.DENIED, repo.grantLevel(asks.id, Capability.APPS_LIST))
                assertFalse(repo.specialDeniedByUser(asks.id, Capability.APPS_LIST))
                // A bridge-side or dashboard write cannot grant it.
                repo.setGrant(asks.id, Capability.APPS_LIST, GrantLevel.WHILE_USING)
                assertEquals(GrantLevel.DENIED, repo.grantLevel(asks.id, Capability.APPS_LIST))
                assertFalse(repo.specialDeniedByUser(asks.id, Capability.APPS_LIST))
                // The sheet grants; the dashboard's Remove is then the user's denial.
                assertEquals(SpecialGrantResult.Granted, repo.grantSpecial(asks.id, Capability.APPS_LIST))
                repo.setGrant(asks.id, Capability.APPS_LIST, GrantLevel.DENIED)
                assertTrue(repo.specialDeniedByUser(asks.id, Capability.APPS_LIST))
                // The 90-day auto-revoke is not the user's denial: the app may ask again.
                repo.setGrant(asks.id, Capability.APPS_LIST, GrantLevel.DENIED, byUser = false)
                assertFalse(repo.specialDeniedByUser(asks.id, Capability.APPS_LIST))
                // A new sheet grant clears the remembered denial.
                assertEquals(SpecialGrantResult.Granted, repo.grantSpecial(asks.id, Capability.APPS_LIST))
                assertFalse(repo.specialDeniedByUser(asks.id, Capability.APPS_LIST))
            }
        } finally {
            runBlocking {
                val db = DeftApp.instance.database
                for (id in ids) {
                    db.grants().deleteForApp(id)
                    db.webApps().delete(id)
                }
            }
        }
    }

    /**
     * `network.open` widens the CSP only when the app declares it and the user granted
     * it; a denied box, a revoke, or a stray grant row for an undeclared capability serve no `https:`.
     */
    @Test
    fun openNetworkNeedsGrant() {
        val store = DeftApp.instance.myApps
        val repo = DeftApp.instance.repository
        val declares = "hostile-open-net"
        val silent = "hostile-open-silent"
        fun project(slug: String, caps: String) {
            val dir = store.projectDir(slug).also { it.deleteRecursively(); it.mkdirs() }
            File(dir, "index.html").writeText("<!doctype html><html><body></body></html>")
            File(dir, "manifest.json").writeText(
                """{"deft":1,"id":"$slug","name":"$slug","version":"0.1.0","profile":"myapp",""" +
                    """"start_url":"./index.html","capabilities":[$caps],"network":[]}""",
            )
        }
        project(declares, """{"id":"network.open","reason":"Reads any feed for a test."}""")
        project(silent, """{"id":"haptics","reason":"Buzzes for a test."}""")
        val ids = mutableListOf<String>()
        var denied: CspPolicy? = null
        try {
            runBlocking {
                // Consent screen with the network.open box left unticked.
                val shown = listOf(Capability.NETWORK_OPEN)
                val app = repo.applyWorkspaceConsent(declares, shown, emptyList()).also { ids += it.id }
                denied = repo.cspPolicyFor(app)
                assertFalse(CspFlag.OPEN_NETWORK in denied!!.flags)
                val deniedCsp = CspBuilder.build(denied!!)
                assertTrue(deniedCsp, deniedCsp.contains("connect-src 'self';"))
                assertFalse(deniedCsp, deniedCsp.contains("https:"))

                // Allowed from the dashboard: any https / wss origin, images and media too.
                repo.setGrant(app.id, Capability.NETWORK_OPEN, GrantLevel.WHILE_USING)
                val openCsp = CspBuilder.build(repo.cspPolicyFor(app))
                assertTrue(openCsp, openCsp.contains("connect-src 'self' https: wss:;"))
                assertTrue(openCsp, openCsp.contains("img-src 'self' data: blob: https:;"))

                // Revoked again: back to the narrow policy.
                repo.setGrant(app.id, Capability.NETWORK_OPEN, GrantLevel.DENIED)
                assertEquals(denied, repo.cspPolicyFor(app))

                // A grant row for a capability the manifest does not declare changes nothing.
                val other = repo.applyWorkspaceConsent(silent, emptyList(), emptyList()).also { ids += it.id }
                repo.setGrant(other.id, Capability.NETWORK_OPEN, GrantLevel.WHILE_USING)
                assertFalse(CspFlag.OPEN_NETWORK in repo.cspPolicyFor(other).flags)
            }
            // The page served with the denied policy cannot reach an arbitrary origin.
            val probe = loadPage(
                host = "hostile-open-net.app.deft.internal",
                html = """<!doctype html><html><head>
                    <script type="module" src="./main.js"></script>
                    </head><body></body></html>""",
                extra = mapOf(
                    "main.js" to """
                        window.__deftFetch = 'pending';
                        fetch('https://example.com/feed.xml').then(function(){
                          window.__deftFetch = 'ok';
                        }).catch(function(e){
                          window.__deftFetch = 'blocked:' + (e && e.name);
                        });
                    """.trimIndent(),
                ),
                expr = "String(window.__deftFetch)",
                policy = denied!!,
            )
            assertTrue(
                "fetch without network.open should fail: $probe",
                probe.contains("blocked") || probe.contains("pending") || probe.contains("TypeError"),
            )
        } finally {
            runBlocking {
                val db = DeftApp.instance.database
                for (id in ids) {
                    db.grants().deleteForApp(id)
                    db.networkConsent().deleteForApp(id)
                    db.webApps().delete(id)
                }
            }
            runCatching { store.projectDir(declares).deleteRecursively() }
            runCatching { store.projectDir(silent).deleteRecursively() }
        }
    }

    /**
     * With "Block network" on, an app cannot reach even an origin it
     * declares and the user allowed; `net.*` refuses too, and turning it off restores the policy.
     */
    @Test
    fun blockedAppCannotFetchDeclaredOrigin() {
        val store = DeftApp.instance.myApps
        val repo = DeftApp.instance.repository
        val slug = "hostile-net-block"
        val declared = "https://example.com"
        val dir = store.projectDir(slug).also { it.deleteRecursively(); it.mkdirs() }
        File(dir, "index.html").writeText("<!doctype html><html><body></body></html>")
        File(dir, "manifest.json").writeText(
            """{"deft":1,"id":"$slug","name":"$slug","version":"0.1.0","profile":"myapp",""" +
                """"start_url":"./index.html","capabilities":[],"network":["$declared"]}""",
        )
        var appId: String? = null
        var blocked: CspPolicy? = null
        try {
            runBlocking {
                val app = repo.applyWorkspaceConsent(slug, emptyList(), emptyList()).also { appId = it.id }
                repo.recordNetworkConsent(app.id, slug, listOf(declared))
                val allowed = repo.cspPolicyFor(app)
                assertTrue(allowed.toString(), declared in allowed.connectOrigins)

                repo.setNetworkBlocked(app.id, true)
                blocked = repo.cspPolicyFor(app)
                assertTrue(CspFlag.BLOCKED in blocked!!.flags)
                val csp = CspBuilder.build(blocked!!)
                assertFalse(csp, csp.contains(declared))
                assertTrue(csp, csp.contains("connect-src 'self';"))

                repo.setNetworkBlocked(app.id, false)
                assertEquals(allowed, repo.cspPolicyFor(app))
            }
            val probe = loadPage(
                host = "hostile-net-block.app.deft.internal",
                html = """<!doctype html><html><head>
                    <script type="module" src="./main.js"></script>
                    </head><body></body></html>""",
                extra = mapOf(
                    "main.js" to """
                        window.__deftFetch = 'pending';
                        fetch('$declared/').then(function(){
                          window.__deftFetch = 'ok';
                        }).catch(function(e){
                          window.__deftFetch = 'blocked:' + (e && e.name);
                        });
                    """.trimIndent(),
                ),
                expr = "String(window.__deftFetch)",
                policy = blocked!!,
            )
            assertTrue(
                "a blocked app must not fetch its declared origin: $probe",
                probe.contains("blocked") || probe.contains("pending") || probe.contains("TypeError"),
            )
        } finally {
            runBlocking {
                val db = DeftApp.instance.database
                appId?.let { id ->
                    db.grants().deleteForApp(id)
                    db.networkConsent().deleteForApp(id)
                    db.networkLog().deleteForApp(id)
                    db.webApps().delete(id)
                }
            }
            runCatching { store.projectDir(slug).deleteRecursively() }
        }
    }

    /**
     * Only embed origins the user ticked reach the CSP, only while `web.embed` is
     * granted; a script from an unticked origin is blocked by `script-src`, and the Trusted Types
     * default policy hands out script URLs for approved origins only.
     */
    @Test
    fun unapprovedEmbedScriptIsBlocked() {
        val store = DeftApp.instance.myApps
        val repo = DeftApp.instance.repository
        val slug = "hostile-embed"
        val approved = "https://js.stripe.com"
        val refused = "https://cdn.unapproved.example"
        val dir = store.projectDir(slug).also { it.deleteRecursively(); it.mkdirs() }
        File(dir, "index.html").writeText("<!doctype html><html><body></body></html>")
        File(dir, "manifest.json").writeText(
            """{"deft":1,"id":"$slug","name":"$slug","version":"0.1.0","profile":"myapp",""" +
                """"start_url":"./index.html","capabilities":[{"id":"web.embed","reason":"Loads a payment form for a test."}],""" +
                """"network":[],"embeds":["$approved","$refused"]}""",
        )
        val ids = mutableListOf<String>()
        var policy: CspPolicy? = null
        try {
            runBlocking {
                val app = repo.applyWorkspaceConsent(slug, listOf(Capability.WEB_EMBED), listOf(Capability.WEB_EMBED))
                    .also { ids += it.id }
                assertEquals(listOf(approved, refused), repo.unconsentedEmbeds(app.id, slug))
                // Consent screen with the second origin unticked.
                repo.recordEmbedConsent(app.id, slug, listOf(approved, refused), listOf(approved))
                assertEquals(emptyList<String>(), repo.unconsentedEmbeds(app.id, slug))
                policy = repo.cspPolicyFor(app)
                assertEquals(listOf(approved), policy!!.embedOrigins)
                val csp = CspBuilder.build(policy!!)
                assertTrue(csp, csp.contains("script-src 'self' $approved;"))
                assertTrue(csp, csp.contains("frame-src $approved;"))
                assertFalse(csp, csp.contains(refused))

                // Revoking web.embed takes every embed origin out; the approvals stay for later.
                repo.setGrant(app.id, Capability.WEB_EMBED, GrantLevel.DENIED)
                assertEquals(emptyList<String>(), repo.cspPolicyFor(app).embedOrigins)
                repo.setGrant(app.id, Capability.WEB_EMBED, GrantLevel.WHILE_USING)
                assertEquals(listOf(approved), repo.cspPolicyFor(app).embedOrigins)
            }
            val probe = loadPage(
                host = "hostile-embed.app.deft.internal",
                html = """<!doctype html><html><head>
                    <script src="./first.js"></script>
                    <script src="$refused/sdk.js"></script>
                    <script type="module" src="./main.js"></script>
                    </head><body></body></html>""",
                extra = mapOf(
                    "first.js" to """
                        window.__deftBlocked = [];
                        document.addEventListener('securitypolicyviolation', function (e) {
                          window.__deftBlocked.push(e.effectiveDirective + ' ' + e.blockedURI);
                        });
                    """.trimIndent(),
                    "main.js" to """
                        function trySrc(url) {
                          try { document.createElement('script').src = url; return 'ok'; }
                          catch (e) { return 'threw:' + e.name; }
                        }
                        // Not window.__deftTT: the host start script owns it (read-only), and this strict module would throw.
                        window.__deftTtProbe = { refused: trySrc('$refused/x.js'), approved: trySrc('$approved/v3/'),
                          html: (function () { try { document.body.innerHTML = '<b>x</b>'; return 'ok'; } catch (e) { return 'threw:' + e.name; } })() };
                    """.trimIndent(),
                ),
                expr = "JSON.stringify({blocked: window.__deftBlocked, tt: window.__deftTtProbe})",
                policy = policy!!,
                startScript = WebViewFindings.HOOK_JS + EmbedScriptPolicy.js(policy!!.embedOrigins),
            )
            val result = JSONObject(JSONTokener(probe).nextValue() as String)
            val blocked = result.getJSONArray("blocked").toString()
            assertTrue("unapproved embed script must be blocked: $probe", blocked.contains("cdn.unapproved.example"))
            assertFalse("approved origin must not be blocked by CSP: $probe", blocked.contains("js.stripe.com"))
            val tt = result.getJSONObject("tt")
            assertTrue(probe, tt.getString("refused").startsWith("threw"))
            assertEquals(probe, "ok", tt.getString("approved"))
            assertTrue(probe, tt.getString("html").startsWith("threw"))
        } finally {
            runBlocking {
                val db = DeftApp.instance.database
                for (id in ids) {
                    db.grants().deleteForApp(id)
                    db.networkConsent().deleteForApp(id)
                    db.webApps().delete(id)
                }
            }
            runCatching { store.projectDir(slug).deleteRecursively() }
        }
    }

    /** A special exclusive group is refused, not taken over, while another app holds it. */
    @Test
    fun secondOriginCannotTakeExclusiveSpecial() {
        val repo = DeftApp.instance.repository
        val db = DeftApp.instance.database
        val ids = mutableListOf<String>()
        try {
            runBlocking {
                val a = repo.install(
                    name = "hostile-usage-a",
                    startUrl = "https://hostile-usage-a.app.deft.internal/",
                    origin = "https://hostile-usage-a.app.deft.internal",
                    manifestUrl = "https://hostile-usage-a.app.deft.internal/manifest.json",
                    capabilities = listOf(Capability.USAGE_READ),
                    grants = mapOf(Capability.USAGE_READ to GrantLevel.WHILE_USING),
                ).also { ids += it.id }
                val b = repo.install(
                    name = "hostile-usage-b",
                    startUrl = "https://hostile-usage-b.app.deft.internal/",
                    origin = "https://hostile-usage-b.app.deft.internal",
                    manifestUrl = "https://hostile-usage-b.app.deft.internal/manifest.json",
                    capabilities = listOf(Capability.USAGE_READ),
                    grants = mapOf(Capability.USAGE_READ to GrantLevel.WHILE_USING),
                ).also { ids += it.id }
                assertNull(repo.specialHolder("usage"))
                assertEquals(SpecialGrantResult.Granted, repo.grantSpecial(a.id, Capability.USAGE_READ))
                val refused = repo.grantSpecial(b.id, Capability.USAGE_READ)
                assertTrue("b must be refused: $refused", refused is SpecialGrantResult.HeldBy)
                assertEquals(a.id, (refused as SpecialGrantResult.HeldBy).webAppId)
                // The ordinary toggle must not take it over either.
                repo.setGrant(b.id, Capability.USAGE_READ, GrantLevel.WHILE_USING)
                assertEquals(GrantLevel.DENIED, repo.grantLevel(b.id, Capability.USAGE_READ))
                assertTrue(repo.grantLevel(a.id, Capability.USAGE_READ).isGranted())
                assertEquals(a.id, repo.specialHolder("usage"))
                // Once a lets go, b can have it.
                repo.setGrant(a.id, Capability.USAGE_READ, GrantLevel.DENIED)
                assertEquals(SpecialGrantResult.Granted, repo.grantSpecial(b.id, Capability.USAGE_READ))
                assertEquals(b.id, repo.specialHolder("usage"))
            }
        } finally {
            runBlocking {
                for (id in ids) {
                    db.grants().deleteForApp(id)
                    db.webApps().delete(id)
                }
            }
        }
    }

    /**
     * Review: a capability dropped from the manifest loses its grant row and its queued events
     * when the app is next opened, and a stale app object cannot list it on the bridge allow-list.
     */
    @Test
    fun droppedCapabilityLosesItsGrant() {
        val store = DeftApp.instance.myApps
        val repo = DeftApp.instance.repository
        val db = DeftApp.instance.database
        val slug = "hostile-dropped"
        fun manifest(caps: String) {
            val dir = store.projectDir(slug).also { it.mkdirs() }
            File(dir, "index.html").writeText("<!doctype html><html><body></body></html>")
            File(dir, "manifest.json").writeText(
                """{"deft":1,"id":"$slug","name":"$slug","version":"0.1.0","profile":"myapp",""" +
                    """"start_url":"./index.html","capabilities":[$caps],"network":[]}""",
            )
        }
        val alarms = """{"id":"alarms","reason":"Sets a reminder for a test."}"""
        val contacts = """{"id":"contacts.read","reason":"Reads contacts for a test."}"""
        store.projectDir(slug).deleteRecursively()
        manifest("$alarms,$contacts")
        var id: String? = null
        try {
            runBlocking {
                val shown = listOf(Capability.ALARMS, Capability.CONTACTS_READ)
                val stale = repo.applyWorkspaceConsent(slug, shown, shown).also { id = it.id }
                assertTrue(repo.grantLevel(stale.id, Capability.CONTACTS_READ).isGranted())
                assertTrue(
                    "contacts.read" in GrantedPrefixes.forApp(repo, stale),
                )

                // Queued while the app was closed; the event names do not start with the capability id.
                repo.enqueueEvent(stale.id, "contacts.changed", "{}")
                repo.enqueueEvent(stale.id, "alarms.fired", "{}")

                manifest(alarms)
                repo.ensureWorkspaceApp(slug)
                assertEquals(listOf("alarms.fired"), db.pendingEvents().forApp(stale.id).map { it.event })
                assertNull(db.grants().get(stale.id, Capability.CONTACTS_READ.id))
                assertTrue(repo.grantLevel(stale.id, Capability.ALARMS).isGranted())
                val prefixes = GrantedPrefixes.forApp(repo, stale)
                assertFalse(prefixes.toString(), "contacts.read" in prefixes)
                assertTrue(prefixes.toString(), "alarms" in prefixes)
                assertFalse(Capability.CONTACTS_READ in repo.grantedCapabilities(stale.id))

                // A revoke from the dashboard drops that capability's queued events too.
                repo.setGrant(stale.id, Capability.ALARMS, GrantLevel.DENIED)
                assertTrue(db.pendingEvents().forApp(stale.id).isEmpty())
            }
        } finally {
            runBlocking {
                id?.let {
                    db.pendingEvents().deleteForApp(it)
                    db.grants().deleteForApp(it)
                    db.webApps().delete(it)
                }
            }
            runCatching { store.projectDir(slug).deleteRecursively() }
        }
    }

    @Test
    fun zipSlipRejected() {
        val dest = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "slip")
        dest.deleteRecursively()
        dest.mkdirs()
        val bytes = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("../secret.txt"))
                zip.write("pwned".toByteArray())
                zip.closeEntry()
            }
            out.toByteArray()
        }
        val err = runCatching {
            app.deft.workspace.AppArchive.import(dest, ByteArrayInputStream(bytes))
        }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertFalse(File(dest, "secret.txt").exists())
        assertFalse(File(dest.parentFile, "secret.txt").exists())
    }

    private fun launchBridge(
        allowed: String,
        listener: String? = null,
        bindService: Boolean = false,
    ): ActivityScenario<BridgeHostTestActivity> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(ctx, BridgeHostTestActivity::class.java).apply {
            putExtra(BridgeHostTestActivity.EXTRA_ALLOWED, allowed)
            if (listener != null) putExtra(BridgeHostTestActivity.EXTRA_LISTENER_ORIGIN, listener)
            if (bindService) putExtra(BridgeHostTestActivity.EXTRA_BIND_SERVICE, true)
        }
        return ActivityScenario.launch(intent)
    }

    private fun awaitBridge(scenario: ActivityScenario<BridgeHostTestActivity>): JSONObject {
        val deadline = System.currentTimeMillis() + 8_000
        var last = JSONObject()
        while (System.currentTimeMillis() < deadline) {
            var activity: BridgeHostTestActivity? = null
            scenario.onActivity { activity = it }
            val raw = activity?.evalJs(
                "(function(){return window.__deftTest ? JSON.stringify(window.__deftTest) : null;})()",
            ) ?: "null"
            val text = (JSONTokener(raw).nextValue() as? String)
            if (text != null) {
                last = JSONObject(text)
                if (last.optBoolean("done")) return last
            }
            Thread.sleep(50)
        }
        throw AssertionError("bridge fixture did not finish: $last")
    }

    private fun invokeOnBridge(scenario: ActivityScenario<BridgeHostTestActivity>, method: String, args: String): String {
        var activity: BridgeHostTestActivity? = null
        scenario.onActivity { activity = it }
        activity!!.evalJs(
            """
            (function(){
              window.__deftCap = 'pending';
              deftBridge.invoke('$method', $args).then(function(){
                window.__deftCap = 'ok';
              }, function(e){
                window.__deftCap = (e && e.code) || String(e);
              });
              return 'started';
            })()
            """.trimIndent(),
        )
        val deadline = System.currentTimeMillis() + 8_000
        var code = "pending"
        while (System.currentTimeMillis() < deadline) {
            var current: BridgeHostTestActivity? = null
            scenario.onActivity { current = it }
            val raw = current?.evalJs("(function(){return window.__deftCap || 'pending';})()") ?: "\"pending\""
            code = (JSONTokener(raw).nextValue() as? String) ?: raw.trim('"')
            if (code != "pending") return code
            Thread.sleep(50)
        }
        return code
    }

    private fun writeThenReadStorage(origin: String, write: String?): String {
        val host = origin.removePrefix("https://")
        val html = if (write != null) {
            """<!doctype html><html><head>
               <script type="module" src="./main.js"></script>
               </head><body></body></html>"""
        } else {
            """<!doctype html><html><head>
               <script type="module" src="./main.js"></script>
               </head><body></body></html>"""
        }
        val js = if (write != null) {
            "localStorage.setItem('k','$write'); window.__deftStore = localStorage.getItem('k');"
        } else {
            "window.__deftStore = localStorage.getItem('k');"
        }
        return loadPage(host, html, mapOf("main.js" to js), "String(window.__deftStore)")
    }

    private fun loadPage(
        host: String,
        html: String,
        extra: Map<String, String> = emptyMap(),
        expr: String,
        policy: CspPolicy = CspPolicy.OFFLINE,
        startScript: String = WebViewFindings.HOOK_JS,
    ): String {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "hostile-$host").apply {
            deleteRecursively()
            mkdirs()
        }
        File(dir, "index.html").writeText(html)
        extra.forEach { (name, body) -> File(dir, name).writeText(body) }
        val origin = "https://$host"
        val loader = LocalOriginPathHandler.loader(
            dir,
            host,
            policy = policy,
        )
        val ready = CountDownLatch(1)
        val probe = AtomicReference("")
        val main = Handler(Looper.getMainLooper())
        val box = AtomicReference<WebView>()
        val created = CountDownLatch(1)
        main.post {
            val wv = WebView(ctx)
            box.set(wv)
            DeftWebViewConfig.apply(wv, geolocation = false)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(wv, startScript, setOf(origin))
            }
            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ) = loader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String?) {
                    ready.countDown()
                }
            }
            created.countDown()
        }
        assertTrue(created.await(10, TimeUnit.SECONDS))
        main.post { box.get().loadUrl("$origin/") }
        assertTrue(ready.await(15, TimeUnit.SECONDS))
        Thread.sleep(600)
        val done = CountDownLatch(1)
        main.post {
            box.get().evaluateJavascript(expr) {
                probe.set(it ?: "")
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        main.post { box.get().destroy() }
        return probe.get()
    }
}
