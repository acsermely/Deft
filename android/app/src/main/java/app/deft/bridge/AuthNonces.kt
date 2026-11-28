package app.deft.bridge

import java.net.URI
import java.security.SecureRandom

/**
 * One-time nonces that tie a sign-in to the app that asked for it. `auth.prepare`
 * issues one; `auth.start` marks it started for the same app; the redirect activity completes it
 * once. A nonce is refused for any other app, a second use, or after [TTL_MS]. Free of Android
 * types so the rules can be unit tested with a fake clock.
 */
class AuthNonces(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private class Entry(val webAppId: String, val expiresAt: Long) {
        var started = false
    }

    // Insertion order, so the oldest nonce of an app is the first one found.
    private val entries = LinkedHashMap<String, Entry>()

    /** A new nonce of [BYTES] random bytes, as lowercase hex, bound to [webAppId]. */
    @Synchronized
    fun issue(webAppId: String): String {
        prune()
        val bytes = ByteArray(BYTES).also { random.nextBytes(it) }
        val nonce = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        entries[nonce] = Entry(webAppId, clock() + TTL_MS)
        val mine = entries.filterValues { it.webAppId == webAppId }.keys.toList()
        if (mine.size > MAX_PER_APP) mine.take(mine.size - MAX_PER_APP).forEach { entries.remove(it) }
        return nonce
    }

    /** True when [nonce] was issued to [webAppId], has not expired and has not started yet. */
    @Synchronized
    fun start(nonce: String, webAppId: String): Boolean {
        prune()
        val entry = entries[nonce] ?: return false
        if (entry.webAppId != webAppId || entry.started) return false
        entry.started = true
        return true
    }

    /**
     * Used by the redirect: returns the app the started sign-in belongs to and removes the nonce,
     * so a second call returns null. Null for unknown, expired, or never started nonces.
     */
    @Synchronized
    fun complete(nonce: String): String? {
        prune()
        val entry = entries.remove(nonce) ?: return null
        return if (entry.started) entry.webAppId else null
    }

    /** Removes the nonce whatever its state. */
    @Synchronized
    fun cancel(nonce: String) {
        entries.remove(nonce)
    }

    /** Removes a started nonce because the user came back without a redirect. False when it was not started. */
    @Synchronized
    fun abandon(nonce: String): Boolean {
        prune()
        if (entries[nonce]?.started != true) return false
        entries.remove(nonce)
        return true
    }

    /** Nonces of [webAppId] whose sign-in page has been opened and not yet finished. */
    @Synchronized
    fun startedNonces(webAppId: String): List<String> {
        prune()
        return entries.filterValues { it.started && it.webAppId == webAppId }.keys.toList()
    }

    private fun prune() {
        val now = clock()
        entries.entries.removeAll { now >= it.value.expiresAt }
    }

    companion object {
        const val BYTES = 16
        const val TTL_MS = 10 * 60 * 1000L
        const val MAX_PER_APP = 8

        val shared = AuthNonces()
    }
}

/** The rules for sign-in URLs and redirect paths. */
object AuthSpec {
    const val MAX_URL = 8_192
    private const val PATH_PREFIX = "/cb/"
    private val NONCE = Regex("^[0-9a-f]{32}$")

    /** The redirect scheme: `<applicationId>.auth`, so debug and release builds never share it. */
    fun scheme(packageName: String): String = "$packageName.auth"

    fun redirectUri(scheme: String, nonce: String): String = "$scheme:$PATH_PREFIX$nonce"

    fun isNonce(text: String): Boolean = NONCE.matches(text)

    /** The nonce of a redirect path `/cb/<nonce>`, or null. */
    fun nonceFromPath(path: String?): String? {
        val rest = path?.takeIf { it.startsWith(PATH_PREFIX) }?.removePrefix(PATH_PREFIX) ?: return null
        return rest.takeIf { isNonce(it) }
    }

    /** Why [url] cannot be opened for sign-in, or null when it can: https, with a host, no user info. */
    fun startUrlProblem(url: String): String? {
        if (url.length > MAX_URL) return "url is too long"
        val uri = runCatching { URI(url) }.getOrNull() ?: return "url is not valid"
        if (!"https".equals(uri.scheme, ignoreCase = true)) return "url must use https"
        if (uri.host.isNullOrBlank()) return "url needs a host"
        if (uri.rawUserInfo != null) return "url may not contain user info"
        return null
    }
}
