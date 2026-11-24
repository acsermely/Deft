package app.deft.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShareInboxTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun inbox() = ShareInbox(tmp.newFolder("inbox"))

    @Test
    fun itemIsReadOnceThenGone() {
        val box = inbox()
        val item = box.store("app1", "photo.jpg", "image/jpeg", "hello".byteInputStream(), now = 1_000)
        assertNotNull(item)
        val (bytes, mime) = box.take("app1", item!!.id, now = 2_000)!!
        assertArrayEquals("hello".toByteArray(), bytes)
        assertEquals("image/jpeg", mime)
        assertNull(box.take("app1", item.id, now = 2_001))
    }

    @Test
    fun otherAppCannotReadIt() {
        val box = inbox()
        val item = box.store("app1", "a.txt", "text/plain", "x".byteInputStream(), now = 1_000)!!
        assertNull(box.take("app2", item.id, now = 1_001))
        assertNotNull(box.take("app1", item.id, now = 1_002))
    }

    @Test
    fun expiredItemIsNotReturned() {
        val box = inbox()
        val item = box.store("app1", "a.txt", "text/plain", "x".byteInputStream(), now = 0)!!
        assertNull(box.take("app1", item.id, now = ShareInbox.TTL_MS + 1))
    }

    @Test
    fun oversizedAndEmptyStreamsAreRejected() {
        val box = inbox()
        val big = ByteArray((ShareInbox.MAX_ITEM_BYTES + 1).toInt())
        assertNull(box.store("app1", "big.bin", "application/octet-stream", big.inputStream(), now = 0))
        assertNull(box.store("app1", "empty", "text/plain", ByteArray(0).inputStream(), now = 0))
    }

    @Test
    fun badIdsAndNamesAreSanitised() {
        val box = inbox()
        assertNull(box.take("app1", "../../etc/passwd", now = 0))
        val item = box.store("app1", "../evil\nname", "not a mime", "x".byteInputStream(), now = 0)!!
        assertEquals(".._evil_name", item.name)
        assertEquals("application/octet-stream", item.mime)
    }

    @Test
    fun sweepRemovesExpiredAndUninstalled() {
        val box = inbox()
        val old = box.store("app1", "a", "text/plain", "x".byteInputStream(), now = 0)!!
        val fresh = box.store("app2", "b", "text/plain", "y".byteInputStream(), now = ShareInbox.TTL_MS)!!
        box.sweep(now = ShareInbox.TTL_MS + 1)
        assertNull(box.take("app1", old.id, now = ShareInbox.TTL_MS + 2))
        box.sweep(now = ShareInbox.TTL_MS + 2, webAppId = "app2")
        assertNull(box.take("app2", fresh.id, now = ShareInbox.TTL_MS + 3))
    }

    @Test
    fun perAppItemCapRefusesNewItemsUntilOneIsRead() {
        val box = ShareInbox(tmp.newFolder("capped"), maxAppItems = 3, maxAppBytes = 1_000)
        val ids = (1..3).map { box.store("app1", "a.txt", "text/plain", "x".byteInputStream(), now = 1_000)!!.id }
        assertTrue(box.full("app1", now = 1_001))
        assertNull(box.store("app1", "b.txt", "text/plain", "y".byteInputStream(), now = 1_001))
        // Another app has its own inbox.
        assertNotNull(box.store("app2", "c.txt", "text/plain", "z".byteInputStream(), now = 1_001))
        box.take("app1", ids[0], now = 1_002)
        assertFalse(box.full("app1", now = 1_003))
        assertNotNull(box.store("app1", "b.txt", "text/plain", "y".byteInputStream(), now = 1_003))
    }

    @Test
    fun perAppByteCapCountsWhatIsWaiting() {
        val box = ShareInbox(tmp.newFolder("bytes"), maxAppItems = 50, maxAppBytes = 10)
        assertNotNull(box.store("app1", "a", "text/plain", "123456".byteInputStream(), now = 1_000))
        // 6 + 5 > 10: refused, and nothing is left behind.
        assertNull(box.store("app1", "b", "text/plain", "12345".byteInputStream(), now = 1_000))
        assertNotNull(box.store("app1", "c", "text/plain", "1234".byteInputStream(), now = 1_000))
        assertTrue(box.full("app1", now = 1_000))
        assertNull(box.store("app1", "d", "text/plain", "1".byteInputStream(), now = 1_000))
    }

    @Test
    fun prefixedAppIdsRoundTrip() {
        val box = inbox()
        val platform = box.store("bundled:platform-check", "p.txt", "text/plain", "bundled".byteInputStream(), now = 0)
        val mine = box.store("myapp:foo", "m.txt", "text/plain", "mine".byteInputStream(), now = 0)
        assertNotNull(platform)
        assertNotNull(mine)
        assertArrayEquals("bundled".toByteArray(), box.take("bundled:platform-check", platform!!.id, now = 1)!!.first)
        assertArrayEquals("mine".toByteArray(), box.take("myapp:foo", mine!!.id, now = 1)!!.first)
    }

    @Test
    fun differentAppIdsHaveSeparateInboxes() {
        val box = inbox()
        val item = box.store("bundled:x", "a.txt", "text/plain", "x".byteInputStream(), now = 0)!!
        assertNull(box.take("myapp:x", item.id, now = 1))
        assertNull(box.take("bundled_x", item.id, now = 1))
        assertNotNull(box.take("bundled:x", item.id, now = 1))
    }

    @Test
    fun legacyRawIdDirectoriesExpireInTheSweep() {
        val root = tmp.newFolder("legacy")
        val old = File(File(root, "bundled-old").apply { mkdirs() }, "11111111-1111-1111-1111-111111111111")
        File(old.path + ".bin").writeText("stale")
        File(old.path + ".meta").writeText("0\ntext/plain\na.txt")
        val box = ShareInbox(root)
        box.sweep(now = ShareInbox.TTL_MS + 1)
        assertFalse(File(old.path + ".bin").exists())
        assertFalse(File(old.path + ".meta").exists())
    }

    @Test
    fun expiredItemsDoNotCountTowardsTheCap() {
        val box = ShareInbox(tmp.newFolder("expiry"), maxAppItems = 1, maxAppBytes = 1_000)
        assertNotNull(box.store("app1", "a", "text/plain", "x".byteInputStream(), now = 0))
        assertNull(box.store("app1", "b", "text/plain", "x".byteInputStream(), now = 1))
        assertNotNull(box.store("app1", "b", "text/plain", "x".byteInputStream(), now = ShareInbox.TTL_MS + 1))
    }
}
