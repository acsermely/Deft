package app.deft.bridge.modules

import app.deft.bridge.PasskeyPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

/**
 * Reads and caches each website's opt-in list ([PasskeyPolicy.OPT_IN_PATH]). https only, no redirects,
 * no cookies (the launcher sets no `CookieHandler`), no cache, 10 s timeout, at most 64 KB. A list that names the app is kept for 24 hours.
 * A failed read, or a list that does not name the app, is kept for 1 minute. Neither of those ever removes a fresh 24-hour entry.
 */
object PasskeyOptIn {
    /** A list that names the app, read at [at]. */
    private class Entry(val origins: Set<String>, val at: Long)

    /** A failed read, or a list that does not name the app, got at [at]. */
    private class Refusal(val result: Result, val at: Long)

    // Kept apart so that a refusal can never overwrite a fresh positive entry. Each key is only written by one kind.
    private val listed = ConcurrentHashMap<String, Entry>()
    private val refused = ConcurrentHashMap<String, Refusal>()

    sealed interface Result {
        data class Read(val origins: Set<String>) : Result
        data class Failed(val reason: String) : Result
    }

    /** True when [rpId]'s list names [originId] (from the cache when fresh, else read again). */
    suspend fun allows(rpId: String, originId: String, now: Long = System.currentTimeMillis()): Result {
        listed[rpId]?.let { entry ->
            if (now - entry.at < PasskeyPolicy.OPT_IN_CACHE_MS && originId in entry.origins) return Result.Read(entry.origins)
        }
        refused[rpId]?.let { cached ->
            if (now - cached.at < PasskeyPolicy.OPT_IN_FAILURE_CACHE_MS) return cached.result
        }
        val read = fetch(rpId)
        if (read is Result.Read && originId in read.origins) {
            listed[rpId] = Entry(read.origins, now)
            refused.remove(rpId)
        } else {
            refused[rpId] = Refusal(read, now)
        }
        return read
    }

    private suspend fun fetch(rpId: String): Result = withContext(Dispatchers.IO) {
        val url = URL(PasskeyPolicy.optInUrl(rpId))
        val conn = (url.openConnection() as? HttpsURLConnection) ?: return@withContext Result.Failed("not https")
        try {
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = PasskeyPolicy.OPT_IN_TIMEOUT_MS
            conn.readTimeout = PasskeyPolicy.OPT_IN_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            val status = conn.responseCode
            PasskeyPolicy.responseProblem(status, conn.contentType, conn.contentLengthLong)
                ?.let { return@withContext Result.Failed(it) }
            val body = readCapped(conn) ?: return@withContext Result.Failed("the website's list is too large")
            val origins = PasskeyPolicy.parseOptIn(body)
                ?: return@withContext Result.Failed("the website's list is not {passkeys: {origins: [...]}}")
            Result.Read(origins)
        } catch (e: java.io.IOException) {
            Result.Failed("could not reach the website")
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(conn: HttpURLConnection): String? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8_192)
        conn.inputStream.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > PasskeyPolicy.MAX_OPT_IN_BYTES) return null
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
