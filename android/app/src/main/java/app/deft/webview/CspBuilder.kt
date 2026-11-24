package app.deft.webview

import app.deft.registry.EmbedSuffixes

/**
 * Switches that widen one app's Content-Security-Policy. Each one is a grant the user gave;
 * the host passes only flags whose capability is declared **and** granted.
 */
enum class CspFlag {
    /** PARITY P1 `network.open`: any HTTPS / WSS origin for fetch, XHR, WebSocket, EventSource, images, media. */
    OPEN_NETWORK,

    /** No network at all (`connect-src 'none'`, or `'self'` with placed widgets; `worker-src 'none'`); the Home canvas. Wins over [OPEN_NETWORK]. */
    NO_NETWORK,

    /** PARITY G2 `wasm`: `'wasm-unsafe-eval'` in script-src, so the app may compile its shipped `.wasm` files. */
    WASM,
}

/** What one app's CSP is built from. Compared by value: a change means the page must reload. */
data class CspPolicy(
    /** Consented origins (manifest + consent screen, plus user servers with `network.user`). */
    val connectOrigins: List<String> = emptyList(),
    val flags: Set<CspFlag> = emptySet(),
    /**
     * PARITY G14 `web.embed`: third-party origins the user approved one by one (manifest `embeds`).
     * The host fills this only when `web.embed` is declared **and** granted.
     */
    val embedOrigins: List<String> = emptyList(),
    /** W1: exact my-app origins the Home canvas may frame (`frame-src`): the widget apps it places, nothing wider. */
    val frameOrigins: List<String> = emptyList(),
    /** W1: exact my-app origins allowed to frame this document (`frame-ancestors`); empty keeps 'none'. */
    val frameAncestors: List<String> = emptyList(),
) {
    companion object {
        val OFFLINE = CspPolicy(flags = setOf(CspFlag.NO_NETWORK))
    }
}

/**
 * The one place that turns a [CspPolicy] into the header string (the main sandbox boundary).
 * Pure: unit tested on exact output in `CspBuilderTest`.
 */
object CspBuilder {
    /** `https://host[:port]`, lowercase. Anything else (spaces, `;`, wildcards, paths) is dropped, never served. */
    private val SAFE_ORIGIN = Regex("^https://[a-z0-9.-]+(?::[0-9]{1,5})?$")

    /**
     * PARITY G14: like [SAFE_ORIGIN], plus an optional `*.` as the whole leftmost label, and at
     * least two labels after it (a bare `*.com` is dropped) that are not a public suffix
     * (`*.github.io`, `*.co.uk` are dropped, [EmbedSuffixes]). Anything else is dropped.
     */
    private val SAFE_EMBED = Regex("^https://(?:\\*\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?::[0-9]{1,5})?$")

    /** W1: a frame origin is one exact my-app origin (`https://<label>.app.deft.internal`), never a wildcard. */
    private val SAFE_FRAME = Regex("^https://[a-z0-9-]+\\.app\\.deft\\.internal$")

    private fun safeEmbed(origin: String): Boolean {
        if (!SAFE_EMBED.matches(origin)) return false
        val rest = origin.removePrefix("https://")
        if (!rest.startsWith("*.")) return true
        return !EmbedSuffixes.publicSuffix(rest.removePrefix("*.").substringBefore(':'))
    }

    /** The embed origins that [build] would serve; also what [EmbedScriptPolicy] allows. */
    fun safeEmbeds(embedOrigins: List<String>): List<String> =
        embedOrigins.filter { safeEmbed(it) }.distinct()

    fun build(policy: CspPolicy, extraScript: String = ""): String =
        build(policy.connectOrigins, policy.flags, policy.embedOrigins, extraScript, policy.frameOrigins, policy.frameAncestors)

    /**
     * @param extraScript appended verbatim to `script-src`; only the host's own import-map hash
     *   (`" 'sha256-…'"`), never app input.
     */
    fun build(
        connectOrigins: List<String>,
        flags: Set<CspFlag>,
        embedOrigins: List<String> = emptyList(),
        extraScript: String = "",
        frameOrigins: List<String> = emptyList(),
        frameAncestors: List<String> = emptyList(),
    ): String {
        val offline = CspFlag.NO_NETWORK in flags
        val open = !offline && CspFlag.OPEN_NETWORK in flags
        val origins = if (offline || open) emptyList() else connectOrigins.filter { SAFE_ORIGIN.matches(it) }.distinct()
        // G14: approved embed origins; none at all for an offline document.
        val embeds = if (offline) emptyList() else safeEmbeds(embedOrigins)
        // P2: images and media follow connect-src (consented origins, or https: with network.open).
        val remote = when {
            open -> listOf("https:")
            else -> origins
        }
        // G14: embeds widen script, frame, connect and img; with network.open, https: already covers the last two.
        val embedRemote = if (open) emptyList() else embeds.filter { it !in origins }
        val frames = embeds + frameOrigins.filter { SAFE_FRAME.matches(it) }.distinct()
        // The Home canvas with placed widgets reads its own `/_deft/v1/widgets.json`: same-origin only, and the
        // canvas origin is served entirely by the launcher, so this opens no network.
        val connect = when {
            offline -> if (frameOrigins.any { SAFE_FRAME.matches(it) }) "'self'" else "'none'"
            open -> "'self' https: wss:"
            else -> (listOf("'self'") + origins + embedRemote).joinToString(" ")
        }
        val ancestors = frameAncestors.filter { SAFE_FRAME.matches(it) }.distinct().ifEmpty { listOf("'none'") }.joinToString(" ")
        val worker = if (offline) "'none'" else "'self'"
        val wasm = if (CspFlag.WASM in flags) " 'wasm-unsafe-eval'" else ""
        val scriptEmbeds = embeds.joinToString("") { " $it" }
        val img = (listOf("'self'", "data:", "blob:") + remote + embedRemote).joinToString(" ")
        val media = (listOf("'self'", "blob:", "mediastream:") + remote).joinToString(" ")
        val frame = if (frames.isEmpty()) "'none'" else frames.joinToString(" ")
        // G14: Stripe.js loads more scripts at run time (a Trusted Types script-URL sink). The host
        // creates a `default` policy that passes only approved embed origins (EmbedScriptPolicy);
        // the CSP must allow that one extra policy name. Trusted Types stays enforced.
        val trusted = if (embeds.isEmpty()) "deft" else "deft default"
        return buildString {
            append("default-src 'none'; script-src 'self'$extraScript$wasm$scriptEmbeds; style-src 'self'; ")
            append("img-src $img; font-src 'self'; media-src $media; ")
            append("connect-src $connect; worker-src $worker; ")
            append("manifest-src 'none'; object-src 'none'; frame-src $frame; child-src 'none'; ")
            append("frame-ancestors $ancestors; base-uri 'none'; form-action 'none'; ")
            append("require-trusted-types-for 'script'; trusted-types $trusted")
        }
    }
}

/**
 * PARITY G14: the Trusted Types `default` policy for an app with approved embed origins, added to
 * the host's document-start script (app origin only, before any page script, so page code cannot
 * create its own `default`). It answers only `createScriptURL`, and only for `https:` URLs on an
 * approved origin (`*.` matches any subdomain, as in CSP). Every other string is refused, so HTML
 * and script sinks stay closed. This is Stripe's documented Trusted Types setup, generalised to the
 * approved list. Empty string when there is nothing approved.
 */
object EmbedScriptPolicy {
    fun js(embedOrigins: List<String>): String {
        val safe = CspBuilder.safeEmbeds(embedOrigins)
        if (safe.isEmpty()) return ""
        // Safe origins hold only [a-z0-9.-:*/]; quoting them cannot break out of the string.
        val list = safe.joinToString(",") { "\"" + it.removePrefix("https://") + "\"" }
        return """
(function(){
  try {
    if (!window.trustedTypes || trustedTypes.defaultPolicy) return;
    var allowed = [$list];
    var ok = function (u) {
      var x;
      try { x = new URL(String(u), location.href); } catch (e) { return false; }
      if (x.protocol !== 'https:' || x.username || x.password) return false;
      for (var i = 0; i < allowed.length; i++) {
        var a = allowed[i];
        if (a.indexOf('*.') === 0) {
          var tail = a.slice(1);
          if (x.host.length > tail.length && x.host.slice(-tail.length) === tail) return true;
        } else if (x.host === a) return true;
      }
      return false;
    };
    trustedTypes.createPolicy('default', {
      createScriptURL: function (u) { return ok(u) ? String(u) : null; }
    });
  } catch (e) {}
})();
"""
    }
}
