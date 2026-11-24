package app.deft.registry

/**
 * PARITY G14: a wildcard embed (`*.` followed by a base host) must not cover a whole public suffix, where
 * every subdomain belongs to someone else (`*.github.io`, `*.co.uk`, `*.herokuapp.com`).
 *
 * The full Public Suffix List is ~10 000 rules; this compact rule catches the cases that matter
 * for embeds instead. A base is a public suffix when it is:
 * - a two-label ccTLD registry zone: a generic second level ([CC_SECOND_LEVELS]: `co`, `com`,
 *   `org`, `gov`, `ac`, …) under a two-letter top-level domain (`co.uk`, `com.au`, `co.jp`); or
 * - one of the shared-hosting suffixes in [SHARED_HOSTING] (from the list's PRIVATE section).
 *
 * Single labels (`*.com`) are already refused by the "two labels after `*.`" rule. Kept identical
 * to deft-lint FL712 (`publicSuffix` in tools/deft-lint/src/index.js).
 */
object EmbedSuffixes {
    val CC_SECOND_LEVELS: Set<String> = setOf(
        "ac", "co", "com", "edu", "go", "gob", "gouv", "gov", "lg", "ltd", "mil",
        "ne", "net", "nhs", "nic", "nom", "or", "org", "plc", "sch",
    )

    val SHARED_HOSTING: Set<String> = setOf(
        "amazonaws.com", "appspot.com", "azurestaticapps.net", "azurewebsites.net", "blogspot.com",
        "cloudfront.net", "deno.dev", "firebaseapp.com", "fly.dev", "github.io", "gitlab.io",
        "glitch.me", "herokuapp.com", "myshopify.com", "netlify.app", "ngrok-free.app", "ngrok.io",
        "onrender.com", "pages.dev", "r2.dev", "repl.co", "replit.app", "s3.amazonaws.com",
        "surge.sh", "vercel.app", "web.app", "wixsite.com", "wordpress.com", "workers.dev",
    )

    /** True when [host] (lowercase, no port) is a public suffix by the rule above. */
    fun publicSuffix(host: String): Boolean {
        if (host in SHARED_HOSTING) return true
        val labels = host.split('.')
        return labels.size == 2 && labels[1].length == 2 && labels[0] in CC_SECOND_LEVELS
    }
}
