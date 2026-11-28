package app.deft.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-app network log keeps origins and hosts only, in a ring buffer. */
class NetworkLogTest {
    private class FakeDao : NetworkLogDao {
        val rows = mutableListOf<NetworkLogEntity>()
        private var next = 1L

        override suspend fun insert(row: NetworkLogEntity): Long {
            val id = next++
            rows += row.copy(id = id)
            return id
        }

        override suspend fun trim(webAppId: String, keep: Int) {
            val mine = rows.filter { it.webAppId == webAppId }.sortedByDescending { it.id }
            val drop = mine.drop(keep).map { it.id }.toSet()
            rows.removeAll { it.id in drop }
        }

        override suspend fun recent(webAppId: String, limit: Int): List<NetworkLogEntity> =
            rows.filter { it.webAppId == webAppId }.sortedByDescending { it.id }.take(limit)

        override suspend fun deleteForApp(webAppId: String) {
            rows.removeAll { it.webAppId == webAppId }
        }
    }

    @Test
    fun entryDropsPathQueryAndFragment() {
        val row = NetworkLog.entry("a", "https://API.Example.com:8443/v1/items?token=secret#x", NetworkLog.KIND_FETCH, true, 5)!!
        assertEquals("https://api.example.com:8443", row.origin)
        assertEquals("api.example.com", row.host)
        assertFalse(row.origin.contains("secret"))
        assertEquals(5, row.at)
    }

    @Test
    fun entrySkipsLocalAndNonNetworkUrls() {
        assertNull(NetworkLog.entry("a", "https://abc.app.deft.internal/index.html", NetworkLog.KIND_FETCH, true, 0))
        assertNull(NetworkLog.entry("a", "data:image/png;base64,AAAA", NetworkLog.KIND_IMAGE, true, 0))
        assertNull(NetworkLog.entry("a", "blob:https://abc.app.deft.internal/1", NetworkLog.KIND_MEDIA, true, 0))
        assertNull(NetworkLog.entry("a", "not a url", NetworkLog.KIND_FETCH, true, 0))
        assertEquals("wss://chat.example.com", NetworkLog.entry("a", "wss://chat.example.com/socket?id=1", NetworkLog.KIND_WEBSOCKET, true, 0)!!.origin)
    }

    @Test
    fun socketEntryKeepsHostAndPortOnly() {
        val row = NetworkLog.socketEntry("a", "192.168.1.20", 8080, NetworkLog.KIND_TCP, false, 0)!!
        assertEquals("tcp://192.168.1.20:8080", row.origin)
        assertFalse(row.allowed)
        assertEquals("udp://[fe80::1]:53", NetworkLog.socketEntry("a", "fe80::1", 53, NetworkLog.KIND_UDP, true, 0)!!.origin)
        assertNull(NetworkLog.socketEntry("a", "evil/path", 1, NetworkLog.KIND_TCP, true, 0))
    }

    @Test
    fun kindFromWhatTheInterceptSees() {
        assertEquals(NetworkLog.KIND_DOCUMENT, NetworkLog.kindOf(true, "text/html", "/"))
        assertEquals(NetworkLog.KIND_IMAGE, NetworkLog.kindOf(false, "image/avif,image/webp,*/*", "/a.png"))
        assertEquals(NetworkLog.KIND_STYLE, NetworkLog.kindOf(false, "text/css,*/*;q=0.1", "/a.css"))
        assertEquals(NetworkLog.KIND_SCRIPT, NetworkLog.kindOf(false, "*/*", "/lib.mjs"))
        assertEquals(NetworkLog.KIND_FETCH, NetworkLog.kindOf(false, "*/*", "/api/items"))
        assertEquals(NetworkLog.KIND_FETCH, NetworkLog.kindOf(false, null, null))
    }

    @Test
    fun cspConsoleLinesBecomeBlockedRows() {
        val old = "Refused to connect to 'https://tracker.example.net/collect?u=1' because it violates the following " +
            "Content Security Policy directive: \"connect-src 'self'\"."
        val row = NetworkLog.fromCspConsole("a", old, 9)!!
        assertEquals("https://tracker.example.net", row.origin)
        assertEquals(NetworkLog.KIND_FETCH, row.kind)
        assertFalse(row.allowed)

        val new = "Loading the image 'https://img.example.com/a.png' violates the following Content Security Policy " +
            "directive: \"img-src 'self' data: blob:\". The action has been blocked."
        assertEquals(NetworkLog.KIND_IMAGE, NetworkLog.fromCspConsole("a", new, 0)!!.kind)

        val ws = "Connecting to 'wss://live.example.com/ws' violates the following Content Security Policy directive: \"connect-src 'self'\"."
        assertEquals(NetworkLog.KIND_WEBSOCKET, NetworkLog.fromCspConsole("a", ws, 0)!!.kind)

        assertNull(NetworkLog.fromCspConsole("a", "Uncaught TypeError: x is undefined", 0))
        // An inline-script refusal has no URL to record.
        assertNull(
            NetworkLog.fromCspConsole(
                "a",
                "Refused to execute inline script because it violates the following Content Security Policy directive: \"script-src 'self'\".",
                0,
            ),
        )
    }

    @Test
    fun storeIsARingBufferPerApp() = runBlocking {
        val dao = FakeDao()
        val store = NetworkLogStore(dao, keep = 5)
        repeat(8) { i -> store.record(NetworkLog.entry("a", "https://h$i.example.com/", NetworkLog.KIND_FETCH, true, i.toLong())!!) }
        store.record(NetworkLog.entry("b", "https://other.example.com/", NetworkLog.KIND_FETCH, true, 0)!!)
        val kept = store.recent("a")
        assertEquals(5, kept.size)
        assertEquals("h7.example.com", kept.first().host)
        assertEquals("h3.example.com", kept.last().host)
        assertEquals(1, store.recent("b").size)
        store.clear("a")
        assertTrue(store.recent("a").isEmpty())
        assertEquals(1, store.recent("b").size)
    }

    @Test
    fun hostsAreCountedMostRecentFirst() = runBlocking {
        val store = NetworkLogStore(FakeDao())
        store.record(NetworkLog.entry("a", "https://api.example.com/1", NetworkLog.KIND_FETCH, true, 1)!!)
        store.record(NetworkLog.entry("a", "https://cdn.example.com/x.png", NetworkLog.KIND_IMAGE, true, 2)!!)
        store.record(NetworkLog.entry("a", "https://api.example.com/2", NetworkLog.KIND_FETCH, false, 3)!!)
        val hosts = store.hosts("a")
        assertEquals(listOf("api.example.com", "cdn.example.com"), hosts.map { it.host })
        assertEquals(1, hosts[0].allowed)
        assertEquals(1, hosts[0].blocked)
        assertEquals(listOf(NetworkLog.KIND_IMAGE), hosts[1].kinds)
    }
}
