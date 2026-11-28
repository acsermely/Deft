package app.deft.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import java.net.URI
import java.util.Locale

/**
 * One outgoing request of an app: WebView traffic (fetch, XHR, images,
 * media, scripts, frames) and the launcher's `net.*` sockets and `net.fetch`. Only the target
 * origin and host are kept; never a path, query string, body or header.
 */
@Entity(tableName = "network_log", indices = [Index("webAppId")])
data class NetworkLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val webAppId: String,
    /** `scheme://host[:port]` of the target, lowercase. */
    val origin: String,
    /** Host name or IP literal of the target, lowercase. */
    val host: String,
    /** One of the [NetworkLog] `KIND_*` values. */
    val kind: String,
    val at: Long,
    /** False when the CSP, the "Block network" switch or a socket rule stopped it. */
    val allowed: Boolean,
)

@Dao
interface NetworkLogDao {
    @Insert
    suspend fun insert(row: NetworkLogEntity): Long

    /** Keeps the newest [keep] rows of the app (the ring buffer). */
    @Query(
        "DELETE FROM network_log WHERE webAppId = :webAppId AND id NOT IN " +
            "(SELECT id FROM network_log WHERE webAppId = :webAppId ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun trim(webAppId: String, keep: Int)

    @Query("SELECT * FROM network_log WHERE webAppId = :webAppId ORDER BY id DESC LIMIT :limit")
    suspend fun recent(webAppId: String, limit: Int): List<NetworkLogEntity>

    @Query("DELETE FROM network_log WHERE webAppId = :webAppId")
    suspend fun deleteForApp(webAppId: String)
}

/** One line of the dashboard's "Network" section. */
data class NetworkHostSummary(
    val host: String,
    val allowed: Int,
    val blocked: Int,
    val lastAt: Long,
    val kinds: List<String>,
)

/** Pure rules of the per-app network log; unit tested in `NetworkLogTest`. */
object NetworkLog {
    /** Rows kept per app; older ones are dropped on insert. */
    const val MAX_ROWS = 500

    /** The "Block network" switch is a row in `network_consent` with this source (like the agent-tools switch). */
    const val SOURCE_BLOCK = "net-block"
    const val BLOCK_MARK = "on"

    const val KIND_DOCUMENT = "document"
    const val KIND_FETCH = "fetch"
    const val KIND_IMAGE = "image"
    const val KIND_MEDIA = "media"
    const val KIND_SCRIPT = "script"
    const val KIND_STYLE = "style"
    const val KIND_FONT = "font"
    const val KIND_FRAME = "frame"
    const val KIND_WEBSOCKET = "websocket"
    const val KIND_TCP = "tcp"
    const val KIND_UDP = "udp"
    const val KIND_NET_FETCH = "net.fetch"

    /** The plain message `net.*` returns while the switch is on. */
    const val BLOCKED_MESSAGE = "Network is blocked for this app. Turn off \"Block network\" on the dashboard to allow it."

    private val NETWORK_SCHEMES = setOf("http", "https", "ws", "wss")

    /**
     * A row for [url], or null when it is not network traffic (`data:`, `blob:`, the launcher's
     * own `*.deft.internal` origins) or cannot be parsed. Path, query and fragment are dropped.
     */
    fun entry(webAppId: String, url: String, kind: String, allowed: Boolean, at: Long): NetworkLogEntity? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in NETWORK_SCHEMES) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        if (host == "deft.internal" || host.endsWith(".deft.internal")) return null
        val origin = "$scheme://$host" + if (uri.port != -1) ":${uri.port}" else ""
        return NetworkLogEntity(webAppId = webAppId, origin = origin, host = host, kind = kind, at = at, allowed = allowed)
    }

    /** A socket target (`net.tcpConnect`, `net.udpSend`): host and port only. */
    fun socketEntry(webAppId: String, host: String, port: Int, kind: String, allowed: Boolean, at: Long): NetworkLogEntity? {
        val h = host.trim().lowercase(Locale.ROOT).trimEnd('.').takeIf { it.isNotEmpty() && it.length <= 253 } ?: return null
        if (h.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' }) return null
        val shown = if (':' in h && !h.startsWith("[")) "[$h]" else h
        return NetworkLogEntity(webAppId = webAppId, origin = "$kind://$shown:$port", host = h, kind = kind, at = at, allowed = allowed)
    }

    /** What a WebView request is, from what `shouldInterceptRequest` sees (no `Sec-Fetch-Dest` there). */
    fun kindOf(isMainFrame: Boolean, accept: String?, path: String?): String {
        if (isMainFrame) return KIND_DOCUMENT
        val a = accept.orEmpty().lowercase(Locale.ROOT)
        val p = path.orEmpty().lowercase(Locale.ROOT).substringBefore('?')
        return when {
            a.startsWith("text/html") -> KIND_FRAME
            a.startsWith("image/") -> KIND_IMAGE
            a.startsWith("text/css") -> KIND_STYLE
            a.startsWith("video/") || a.startsWith("audio/") -> KIND_MEDIA
            p.endsWith(".js") || p.endsWith(".mjs") -> KIND_SCRIPT
            p.endsWith(".woff2") || p.endsWith(".woff") || p.endsWith(".ttf") || p.endsWith(".otf") -> KIND_FONT
            else -> KIND_FETCH
        }
    }

    /** Both Chromium wordings: "Refused to connect to 'u' because it violates …" and "Connecting to 'u' violates …". */
    private val CSP_REFUSED = Regex(
        "'([a-z][a-z0-9+.-]*://[^'\\s]+)'[^\"]*?Content Security Policy directive:?\\s*\"([a-z-]+)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A blocked row from a Chromium CSP console message ("Refused to connect to 'https://x/…'
     * because it violates the following Content Security Policy directive: "connect-src …""), or null. Requests the CSP stops never
     * reach `shouldInterceptRequest`, so this is how they show up. A page can print such a line
     * itself, which only adds to its own log.
     */
    fun fromCspConsole(webAppId: String, message: String, at: Long): NetworkLogEntity? {
        val m = CSP_REFUSED.find(message.take(2_000)) ?: return null
        val kind = when (m.groupValues[2].lowercase(Locale.ROOT)) {
            "connect-src" -> KIND_FETCH
            "img-src" -> KIND_IMAGE
            "media-src" -> KIND_MEDIA
            "script-src", "script-src-elem" -> KIND_SCRIPT
            "style-src", "style-src-elem" -> KIND_STYLE
            "font-src" -> KIND_FONT
            "frame-src", "child-src" -> KIND_FRAME
            else -> KIND_FETCH
        }
        val url = m.groupValues[1]
        val k = if (kind == KIND_FETCH && (url.startsWith("ws:") || url.startsWith("wss:"))) KIND_WEBSOCKET else kind
        return entry(webAppId, url, k, allowed = false, at = at)
    }

    /** Hosts of [rows], most recent first, with allowed and blocked counts. */
    fun summarize(rows: List<NetworkLogEntity>): List<NetworkHostSummary> =
        rows.groupBy { it.host }.map { (host, list) ->
            NetworkHostSummary(
                host = host,
                allowed = list.count { it.allowed },
                blocked = list.count { !it.allowed },
                lastAt = list.maxOf { it.at },
                kinds = list.map { it.kind }.distinct().sorted(),
            )
        }.sortedByDescending { it.lastAt }
}

/** Writes and reads the log; a ring buffer of [keep] rows per app. */
class NetworkLogStore(private val dao: NetworkLogDao, private val keep: Int = NetworkLog.MAX_ROWS) {
    suspend fun record(row: NetworkLogEntity) {
        dao.insert(row)
        dao.trim(row.webAppId, keep)
    }

    suspend fun recent(webAppId: String, limit: Int = keep): List<NetworkLogEntity> = dao.recent(webAppId, limit.coerceIn(1, keep))

    suspend fun hosts(webAppId: String): List<NetworkHostSummary> = NetworkLog.summarize(dao.recent(webAppId, keep))

    suspend fun clear(webAppId: String) = dao.deleteForApp(webAppId)
}
