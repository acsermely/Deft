package app.deft.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PARITY G1: the CSP is the main sandbox boundary, so every combination is pinned to the byte. */
class CspBuilderTest {
    private val a = "https://api.example.com"
    private val b = "https://cdn.example.org:8443"

    /** The string `LocalOriginPathHandler.cspWith` produced before G1, kept verbatim for comparison. */
    private fun beforeG1(connectSrc: String, workerSrc: String, extraScript: String = ""): String =
        buildString {
            append("default-src 'none'; script-src 'self'$extraScript; style-src 'self'; ")
            append("img-src 'self' data: blob:; font-src 'self'; media-src 'self' blob: mediastream:; ")
            append("connect-src $connectSrc; worker-src $workerSrc; ")
            append("manifest-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; ")
            append("frame-ancestors 'none'; base-uri 'none'; form-action 'none'; ")
            append("require-trusted-types-for 'script'; trusted-types deft")
        }

    private val noGrants =
        "default-src 'none'; script-src 'self'; style-src 'self'; " +
            "img-src 'self' data: blob:; font-src 'self'; media-src 'self' blob: mediastream:; " +
            "connect-src 'self'; worker-src 'self'; " +
            "manifest-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; " +
            "frame-ancestors 'none'; base-uri 'none'; form-action 'none'; " +
            "require-trusted-types-for 'script'; trusted-types deft"

    private val consented =
        "default-src 'none'; script-src 'self'; style-src 'self'; " +
            "img-src 'self' data: blob: https://api.example.com https://cdn.example.org:8443; font-src 'self'; " +
            "media-src 'self' blob: mediastream: https://api.example.com https://cdn.example.org:8443; " +
            "connect-src 'self' https://api.example.com https://cdn.example.org:8443; worker-src 'self'; " +
            "manifest-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; " +
            "frame-ancestors 'none'; base-uri 'none'; form-action 'none'; " +
            "require-trusted-types-for 'script'; trusted-types deft"

    private val open =
        "default-src 'none'; script-src 'self'; style-src 'self'; " +
            "img-src 'self' data: blob: https:; font-src 'self'; media-src 'self' blob: mediastream: https:; " +
            "connect-src 'self' https: wss:; worker-src 'self'; " +
            "manifest-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; " +
            "frame-ancestors 'none'; base-uri 'none'; form-action 'none'; " +
            "require-trusted-types-for 'script'; trusted-types deft"

    private val offline =
        "default-src 'none'; script-src 'self'; style-src 'self'; " +
            "img-src 'self' data: blob:; font-src 'self'; media-src 'self' blob: mediastream:; " +
            "connect-src 'none'; worker-src 'none'; " +
            "manifest-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; " +
            "frame-ancestors 'none'; base-uri 'none'; form-action 'none'; " +
            "require-trusted-types-for 'script'; trusted-types deft"

    /** PARITY G2: `'wasm-unsafe-eval'` is appended to script-src, after 'self' and any import-map hash. */
    private fun wasm(csp: String): String = csp.replace("script-src 'self';", "script-src 'self' 'wasm-unsafe-eval';")

    @Test
    fun noGrantsNoOrigins() {
        assertEquals(noGrants, CspBuilder.build(emptyList(), emptySet()))
        assertEquals(noGrants, CspBuilder.build(CspPolicy()))
    }

    @Test
    fun withoutNewGrantsOnlyImgAndMediaChange() {
        // No origins: byte-for-byte what the host served before G1.
        assertEquals(beforeG1("'self'", "'self'"), CspBuilder.build(CspPolicy()))
        // Consented origins: identical except that img-src and media-src gain the same origins (P2).
        val old = beforeG1("'self' $a $b", "'self'")
        val expected = old
            .replace("img-src 'self' data: blob:;", "img-src 'self' data: blob: $a $b;")
            .replace("media-src 'self' blob: mediastream:;", "media-src 'self' blob: mediastream: $a $b;")
        assertEquals(expected, CspBuilder.build(CspPolicy(listOf(a, b))))
        // The Home canvas (connect 'none', worker 'none') is unchanged.
        assertEquals(beforeG1("'none'", "'none'"), CspBuilder.build(CspPolicy.OFFLINE))
        // So is the import-map hash path.
        assertEquals(
            beforeG1("'self'", "'self'", " 'sha256-abc='"),
            CspBuilder.build(CspPolicy(), " 'sha256-abc='"),
        )
    }

    @Test
    fun consentedOriginsGoToConnectImgAndMedia() {
        assertEquals(consented, CspBuilder.build(listOf(a, b), emptySet()))
    }

    @Test
    fun openNetwork() {
        assertEquals(open, CspBuilder.build(emptyList(), setOf(CspFlag.OPEN_NETWORK)))
    }

    @Test
    fun openNetworkSubsumesConsentedOrigins() {
        assertEquals(open, CspBuilder.build(listOf(a, b), setOf(CspFlag.OPEN_NETWORK)))
    }

    @Test
    fun offline() {
        assertEquals(offline, CspBuilder.build(emptyList(), setOf(CspFlag.NO_NETWORK)))
    }

    @Test
    fun offlineWinsOverEverything() {
        assertEquals(offline, CspBuilder.build(listOf(a), setOf(CspFlag.NO_NETWORK, CspFlag.OPEN_NETWORK)))
    }

    @Test
    fun wasmOnlyAddsUnsafeEvalToScriptSrc() {
        assertEquals(
            "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self'; " +
                "img-src 'self' data: blob:; font-src 'self'; media-src 'self' blob: mediastream:; " +
                "connect-src 'self'; worker-src 'self'; " +
                "manifest-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; " +
                "frame-ancestors 'none'; base-uri 'none'; form-action 'none'; " +
                "require-trusted-types-for 'script'; trusted-types deft",
            CspBuilder.build(emptyList(), setOf(CspFlag.WASM)),
        )
        assertEquals(wasm(noGrants), CspBuilder.build(emptyList(), setOf(CspFlag.WASM)))
    }

    @Test
    fun wasmKeepsEveryOtherDirective() {
        assertEquals(wasm(open), CspBuilder.build(emptyList(), setOf(CspFlag.OPEN_NETWORK, CspFlag.WASM)))
        assertEquals(wasm(offline), CspBuilder.build(emptyList(), setOf(CspFlag.NO_NETWORK, CspFlag.WASM)))
    }

    @Test
    fun wasmSitsAfterTheImportMapHash() {
        val csp = CspBuilder.build(CspPolicy(flags = setOf(CspFlag.WASM)), " 'sha256-xyz='")
        assertEquals(
            noGrants.replace("script-src 'self';", "script-src 'self' 'sha256-xyz=' 'wasm-unsafe-eval';"),
            csp,
        )
    }

    @Test
    fun everyFlagCombinationIsPinned() {
        val expected = mapOf(
            emptySet<CspFlag>() to consented,
            setOf(CspFlag.OPEN_NETWORK) to open,
            setOf(CspFlag.NO_NETWORK) to offline,
            setOf(CspFlag.OPEN_NETWORK, CspFlag.NO_NETWORK) to offline,
            setOf(CspFlag.WASM) to wasm(consented),
            setOf(CspFlag.OPEN_NETWORK, CspFlag.WASM) to wasm(open),
            setOf(CspFlag.NO_NETWORK, CspFlag.WASM) to wasm(offline),
            setOf(CspFlag.OPEN_NETWORK, CspFlag.NO_NETWORK, CspFlag.WASM) to wasm(offline),
        )
        // Fails when a flag is added without deciding its output here.
        assertEquals(1 shl CspFlag.entries.size, expected.size)
        for ((flags, csp) in expected) assertEquals(flags.toString(), csp, CspBuilder.build(listOf(a, b), flags))
    }

    @Test
    fun importMapHashOnlyTouchesScriptSrc() {
        val csp = CspBuilder.build(CspPolicy(flags = setOf(CspFlag.OPEN_NETWORK)), " 'sha256-xyz='")
        assertEquals(open.replace("script-src 'self';", "script-src 'self' 'sha256-xyz=';"), csp)
    }

    @Test
    fun originsThatCouldInjectDirectivesAreDropped() {
        val hostile = listOf(
            "https://a.example.com; script-src *",
            "https://a.example.com 'unsafe-inline'",
            "*",
            "https:",
            "http://plain.example.com",
            "https://*.example.com",
            "https://a.example.com/path",
            "https://A.EXAMPLE.COM",
            "'unsafe-eval'",
            "",
            "https://a.example.com,https://b.example.com",
        )
        assertEquals(noGrants, CspBuilder.build(hostile, emptySet()))
        val csp = CspBuilder.build(hostile + a, emptySet())
        assertTrue(csp.contains("connect-src 'self' $a;"))
        assertFalse(csp.contains("unsafe"))
    }

    @Test
    fun duplicatesAreServedOnce() {
        assertEquals(
            CspBuilder.build(listOf(a), emptySet()),
            CspBuilder.build(listOf(a, a), emptySet()),
        )
    }

    private val stripe = "https://js.stripe.com"
    private val stripeFrames = "https://*.js.stripe.com"

    /** PARITY G14: the exact policy for an app with two approved embed origins and nothing else. */
    private val twoEmbeds =
        "default-src 'none'; script-src 'self' https://js.stripe.com https://*.js.stripe.com; style-src 'self'; " +
            "img-src 'self' data: blob: https://js.stripe.com https://*.js.stripe.com; font-src 'self'; " +
            "media-src 'self' blob: mediastream:; " +
            "connect-src 'self' https://js.stripe.com https://*.js.stripe.com; worker-src 'self'; " +
            "manifest-src 'none'; object-src 'none'; frame-src https://js.stripe.com https://*.js.stripe.com; " +
            "child-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'; " +
            "require-trusted-types-for 'script'; trusted-types deft default"

    @Test
    fun twoApprovedEmbedOrigins() {
        assertEquals(twoEmbeds, CspBuilder.build(CspPolicy(embedOrigins = listOf(stripe, stripeFrames))))
        assertEquals(twoEmbeds, CspBuilder.build(emptyList(), emptySet(), listOf(stripe, stripeFrames)))
    }

    @Test
    fun embedsFollowConsentedOriginsAndAreNotRepeated() {
        val csp = CspBuilder.build(listOf(a, stripe), emptySet(), listOf(stripe, stripeFrames))
        assertTrue(csp, csp.contains("connect-src 'self' $a $stripe $stripeFrames;"))
        assertTrue(csp, csp.contains("img-src 'self' data: blob: $a $stripe $stripeFrames;"))
        // media-src follows consented origins only (P2); embeds do not widen it.
        assertTrue(csp, csp.contains("media-src 'self' blob: mediastream: $a $stripe;"))
        assertTrue(csp, csp.contains("script-src 'self' $stripe $stripeFrames;"))
        assertTrue(csp, csp.contains("frame-src $stripe $stripeFrames;"))
    }

    @Test
    fun wildcardEmbedOverAPublicSuffixIsDropped() {
        assertEquals(
            listOf(stripe, "https://*.me.github.io"),
            CspBuilder.safeEmbeds(listOf(stripe, "https://*.github.io", "https://*.co.uk", "https://*.vercel.app", "https://*.me.github.io")),
        )
    }

    @Test
    fun embedsWithOpenNetworkOnlyAddScriptAndFrame() {
        val csp = CspBuilder.build(emptyList(), setOf(CspFlag.OPEN_NETWORK), listOf(stripe))
        assertEquals(
            open
                .replace("script-src 'self';", "script-src 'self' $stripe;")
                .replace("frame-src 'none';", "frame-src $stripe;")
                .replace("trusted-types deft", "trusted-types deft default"),
            csp,
        )
    }

    @Test
    fun embedsSitAfterImportMapHashAndWasm() {
        val csp = CspBuilder.build(CspPolicy(flags = setOf(CspFlag.WASM), embedOrigins = listOf(stripe)), " 'sha256-xyz='")
        assertTrue(csp, csp.contains("script-src 'self' 'sha256-xyz=' 'wasm-unsafe-eval' $stripe;"))
    }

    @Test
    fun offlineServesNoEmbeds() {
        assertEquals(offline, CspBuilder.build(emptyList(), setOf(CspFlag.NO_NETWORK), listOf(stripe, stripeFrames)))
    }

    @Test
    fun noEmbedsKeepsFramesClosedAndOnePolicy() {
        val csp = CspBuilder.build(CspPolicy(embedOrigins = emptyList()))
        assertEquals(noGrants, csp)
        assertTrue(csp.endsWith("trusted-types deft"))
    }

    @Test
    fun embedOriginsThatCouldInjectDirectivesAreDropped() {
        val hostile = listOf(
            "https://js.stripe.com; script-src *",
            "https://js.stripe.com 'unsafe-inline'",
            "*",
            "https:",
            "https://*",
            "https://*.com",
            "https://a.*.example.com",
            "https://*.*.example.com",
            "https://**.example.com",
            "*.example.com",
            "http://js.stripe.com",
            "wss://js.stripe.com",
            "https://js.stripe.com/v3/",
            "https://JS.STRIPE.COM",
            "'unsafe-eval'",
            "'self'",
            "data:",
            "",
            "https://a.example.com,https://b.example.com",
        )
        assertEquals(noGrants, CspBuilder.build(emptyList(), emptySet(), hostile))
        val csp = CspBuilder.build(emptyList(), emptySet(), hostile + stripe)
        assertTrue(csp, csp.contains("script-src 'self' $stripe;"))
        assertTrue(csp, csp.contains("frame-src $stripe;"))
        assertFalse(csp, csp.contains("unsafe"))
        assertEquals(listOf(stripe), CspBuilder.safeEmbeds(hostile + stripe + stripe))
    }

    @Test
    fun embedsWithEveryFlagCombination() {
        for (bits in 0 until (1 shl CspFlag.entries.size)) {
            val flags = CspFlag.entries.filterIndexed { i, _ -> bits and (1 shl i) != 0 }.toSet()
            val csp = CspBuilder.build(listOf(a), flags, listOf(stripe, stripeFrames))
            if (CspFlag.NO_NETWORK in flags) {
                assertEquals(csp, CspBuilder.build(listOf(a), flags), csp)
                continue
            }
            assertTrue(csp, csp.contains(" $stripe $stripeFrames; style-src 'self';"))
            assertTrue(csp, csp.contains("frame-src $stripe $stripeFrames; child-src 'none';"))
            assertTrue(csp, csp.endsWith("require-trusted-types-for 'script'; trusted-types deft default"))
            assertTrue(csp, csp.contains("object-src 'none'"))
            assertTrue(csp, csp.contains("base-uri 'none'; form-action 'none';"))
            assertFalse(csp, csp.replace("'wasm-unsafe-eval'", "").contains("unsafe"))
            // Without embeds the same flags give exactly the pre-G14 policy.
            val plain = CspBuilder.build(listOf(a), flags)
            assertTrue(plain, plain.contains("frame-src 'none'") && plain.endsWith("trusted-types deft"))
        }
    }

    @Test
    fun embedScriptPolicyPassesOnlyApprovedScriptUrls() {
        assertEquals("", EmbedScriptPolicy.js(emptyList()))
        assertEquals("", EmbedScriptPolicy.js(listOf("https://*.com", "http://js.stripe.com", "\";alert(1)//")))
        val js = EmbedScriptPolicy.js(listOf(stripe, stripeFrames, "https://x.example.com\"];alert(1)//"))
        assertTrue(js, js.contains("var allowed = [\"js.stripe.com\",\"*.js.stripe.com\"];"))
        assertTrue(js, js.contains("trustedTypes.createPolicy('default'"))
        assertTrue(js, js.contains("createScriptURL"))
        // HTML and script sinks stay closed: the policy defines no other factory.
        assertFalse(js, js.contains("createHTML"))
        assertFalse(js, js.contains("createScript:"))
        assertFalse(js, js.contains("alert"))
    }

    @Test
    fun noDirectiveEverAllowsUnsafeOrWildcards() {
        val all = CspFlag.entries.toSet()
        val combos = listOf(emptySet(), setOf(CspFlag.OPEN_NETWORK), setOf(CspFlag.NO_NETWORK), all)
        for (flags in combos) {
            val csp = CspBuilder.build(listOf(a, b), flags)
            // The only "unsafe" keyword allowed anywhere is wasm-unsafe-eval, and only with the WASM flag.
            assertFalse(csp, csp.replace("'wasm-unsafe-eval'", "").contains("unsafe"))
            assertEquals(csp, CspFlag.WASM in flags, csp.contains("'wasm-unsafe-eval'"))
            assertFalse(csp, csp.contains(" * ") || csp.contains(" *;"))
            assertFalse(csp, csp.contains("http:"))
            assertTrue(csp, csp.contains("frame-src 'none'"))
            assertTrue(csp, csp.contains("object-src 'none'"))
            assertTrue(csp, csp.startsWith("default-src 'none'; script-src 'self'"))
            assertTrue(csp, csp.contains("; style-src 'self';"))
        }
    }

    // W1: the Home canvas frames the exact origins of placed widget apps and nothing else; the frame adds no script.
    @Test
    fun frameOriginsAreExactMyAppOrigins() {
        val flags = setOf(CspFlag.NO_NETWORK)
        val csp = CspBuilder.build(
            CspPolicy(
                flags = flags,
                frameOrigins = listOf(
                    "https://notes.app.deft.internal",
                    "https://notes.app.deft.internal",
                    "https://*.app.deft.internal",
                    "https://evil.example.com",
                    "https://x.app.deft.internal.evil.com",
                    "https://a.app.deft.internal; script-src *",
                    "http://b.app.deft.internal",
                ),
            ),
        )
        // Placed widgets: the canvas reads its own widgets.json, so connect-src is 'self' (worker-src stays 'none').
        assertEquals(
            offline.replace("frame-src 'none'", "frame-src https://notes.app.deft.internal").replace("connect-src 'none'", "connect-src 'self'"),
            csp,
        )
        assertTrue(csp, csp.contains("connect-src 'self'; worker-src 'none';"))
        assertTrue(CspBuilder.build(CspPolicy(flags = flags)).contains("connect-src 'none'; worker-src 'none';"))
        assertTrue(CspBuilder.build(CspPolicy(flags = flags, frameOrigins = listOf("https://evil.example.com"))).contains("connect-src 'none'"))
        assertTrue(CspBuilder.build(CspPolicy(flags = flags)).contains("frame-src 'none'"))
        assertTrue(CspBuilder.build(CspPolicy(flags = flags, frameOrigins = listOf("https://*.app.deft.internal"))).contains("frame-src 'none'"))
    }

    @Test
    fun frameAncestorsAreExactMyAppOrigins() {
        val flags = setOf(CspFlag.NO_NETWORK)
        val csp = CspBuilder.build(
            CspPolicy(
                flags = flags,
                frameAncestors = listOf(
                    "https://home.app.deft.internal",
                    "https://home.app.deft.internal",
                    "https://*.app.deft.internal",
                    "https://evil.example.com",
                    "http://b.app.deft.internal",
                ),
            ),
        )
        assertEquals(offline.replace("frame-ancestors 'none'", "frame-ancestors https://home.app.deft.internal"), csp)
        assertTrue(CspBuilder.build(CspPolicy(flags = flags, frameAncestors = listOf("https://evil.example.com"))).contains("frame-ancestors 'none'"))
        assertTrue(CspBuilder.build(CspPolicy(flags = flags)).contains("frame-ancestors 'none'"))
    }
}
