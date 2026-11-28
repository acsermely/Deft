package app.deft.bridge

import android.support.v4.media.session.PlaybackStateCompat
import app.deft.bridge.MediaSessionSpec.Action
import app.deft.bridge.MediaSessionSpec.Artwork
import app.deft.bridge.MediaSessionSpec.State
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** State and action names, notification buttons, the artwork rule and argument limits. */
class MediaSessionSpecTest {
    private val origin = "https://abc.app.deft.internal"

    @Test
    fun statesMapToPlaybackStates() {
        assertEquals(PlaybackStateCompat.STATE_PLAYING, State.fromId("playing")!!.code)
        assertEquals(PlaybackStateCompat.STATE_PAUSED, State.fromId("paused")!!.code)
        assertEquals(PlaybackStateCompat.STATE_STOPPED, State.fromId("stopped")!!.code)
        assertEquals(PlaybackStateCompat.STATE_NONE, State.fromId("none")!!.code)
        assertNull(State.fromId("buffering"))
        assertNull(State.fromId("PLAYING"))
        assertNull(State.fromId(null))
    }

    @Test
    fun actionsMapToFlags() {
        val all = MediaSessionSpec.actionsFrom(
            listOf("play", "pause", "stop", "next", "previous", "seekto", "seekforward", "seekbackward"),
        )!!
        assertEquals(8, all.size)
        val flags = MediaSessionSpec.playbackActions(all)
        for (f in listOf(
            PlaybackStateCompat.ACTION_PLAY, PlaybackStateCompat.ACTION_PAUSE, PlaybackStateCompat.ACTION_STOP,
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
            PlaybackStateCompat.ACTION_SEEK_TO, PlaybackStateCompat.ACTION_FAST_FORWARD, PlaybackStateCompat.ACTION_REWIND,
            PlaybackStateCompat.ACTION_PLAY_PAUSE,
        )) {
            assertTrue(flags and f == f)
        }
    }

    @Test
    fun unknownActionRejectsTheList() {
        assertNull(MediaSessionSpec.actionsFrom(listOf("play", "nexttrack")))
        assertNull(MediaSessionSpec.actionsFrom(listOf("play", null)))
        assertEquals(emptySet<Action>(), MediaSessionSpec.actionsFrom(emptyList()))
        assertEquals(setOf(Action.PLAY), MediaSessionSpec.actionsFrom(listOf("play", "play")))
    }

    @Test
    fun playPauseToggleOnlyWithPlayOrPause() {
        assertEquals(0L, MediaSessionSpec.playbackActions(setOf(Action.NEXT)) and PlaybackStateCompat.ACTION_PLAY_PAUSE)
        assertEquals(
            PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE,
            MediaSessionSpec.playbackActions(setOf(Action.PAUSE)),
        )
        assertEquals(0L, MediaSessionSpec.playbackActions(emptySet()))
    }

    @Test
    fun notificationButtonsFollowState() {
        val all = Action.entries.toSet()
        assertEquals(listOf(Action.PREVIOUS, Action.PAUSE, Action.NEXT), MediaSessionSpec.notificationButtons(all, State.PLAYING))
        assertEquals(listOf(Action.PREVIOUS, Action.PLAY, Action.NEXT), MediaSessionSpec.notificationButtons(all, State.PAUSED))
        assertEquals(listOf(Action.PLAY), MediaSessionSpec.notificationButtons(setOf(Action.PLAY, Action.SEEK_TO), State.NONE))
        // Only pause handled: it still shows while paused.
        assertEquals(listOf(Action.PAUSE), MediaSessionSpec.notificationButtons(setOf(Action.PAUSE), State.PAUSED))
        assertEquals(emptyList<Action>(), MediaSessionSpec.notificationButtons(emptySet(), State.PLAYING))
    }

    @Test
    fun actionEventPayload() {
        assertEquals("""{"action":"play"}""", MediaSessionSpec.actionEvent(Action.PLAY))
        assertEquals("""{"action":"next"}""", MediaSessionSpec.actionEvent(Action.NEXT, 500))
        assertEquals("""{"action":"seekto","seekTime":12.5}""", MediaSessionSpec.actionEvent(Action.SEEK_TO, 12_500))
        assertEquals("""{"action":"seekto","seekTime":0.0}""", MediaSessionSpec.actionEvent(Action.SEEK_TO, -3))
    }

    @Test
    fun millisAndRate() {
        assertEquals(1500L, MediaSessionSpec.millis("1500"))
        assertEquals(1500L, MediaSessionSpec.millis("1500.7"))
        assertEquals(0L, MediaSessionSpec.millis("0"))
        assertNull(MediaSessionSpec.millis("-1"))
        assertNull(MediaSessionSpec.millis("NaN"))
        assertNull(MediaSessionSpec.millis("soon"))
        assertNull(MediaSessionSpec.millis(null))
        assertEquals(1.5f, MediaSessionSpec.rate("1.5"))
        assertEquals(16f, MediaSessionSpec.rate("16"))
        assertNull(MediaSessionSpec.rate("0"))
        assertNull(MediaSessionSpec.rate("-1"))
        assertNull(MediaSessionSpec.rate("17"))
    }

    @Test
    fun artworkSameOriginOnly() {
        assertEquals(Artwork.SameOrigin("$origin/art/cover.png"), MediaSessionSpec.artwork("$origin/art/cover.png", origin))
        assertEquals(Artwork.SameOrigin("$origin/a.png?v=2"), MediaSessionSpec.artwork("$origin/a.png?v=2#x", origin))
        assertEquals(Artwork.SameOrigin("https://ABC.app.deft.internal:443/a.png"), MediaSessionSpec.artwork("https://ABC.app.deft.internal:443/a.png", origin))
        for (bad in listOf(
            "https://evil.example/a.png",
            "https://abc.app.deft.internal.evil.example/a.png",
            "https://xyz.app.deft.internal/a.png",
            "http://abc.app.deft.internal/a.png",
            "https://abc.app.deft.internal:8443/a.png",
            "https://user@abc.app.deft.internal/a.png",
            "//abc.app.deft.internal/a.png",
            "/a.png",
            "file:///sdcard/a.png",
            "content://media/external/images/1",
            "javascript:alert(1)",
            "https://abc.app.deft.internal/../a.png",
            "https://abc.app.deft.internal",
            "not a url",
        )) {
            assertEquals(bad, Artwork.Rejected, MediaSessionSpec.artwork(bad, origin))
        }
    }

    @Test
    fun artworkDataImages() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
        val url = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        val art = MediaSessionSpec.artwork(url, origin)
        assertTrue(art is Artwork.Data)
        assertArrayEquals(png, (art as Artwork.Data).bytes)
        assertTrue(MediaSessionSpec.artwork("data:image/jpeg;base64,AAAA", origin) is Artwork.Data)
        assertEquals(Artwork.Rejected, MediaSessionSpec.artwork("data:image/svg+xml;base64,AAAA", origin))
        assertEquals(Artwork.Rejected, MediaSessionSpec.artwork("data:text/html;base64,AAAA", origin))
        assertEquals(Artwork.Rejected, MediaSessionSpec.artwork("data:image/png,rawbytes", origin))
        assertEquals(Artwork.Rejected, MediaSessionSpec.artwork("data:image/png;base64,", origin))
        assertEquals(Artwork.Rejected, MediaSessionSpec.artwork("data:image/png;base64,!!!", origin))
        val big = ByteArray(MediaSessionSpec.ARTWORK_MAX_BYTES + 1)
        assertEquals(
            Artwork.TooLarge,
            MediaSessionSpec.artwork("data:image/png;base64," + Base64.getEncoder().encodeToString(big), origin),
        )
        val fits = ByteArray(MediaSessionSpec.ARTWORK_MAX_BYTES)
        val enc = "data:image/png;base64," + Base64.getEncoder().encodeToString(fits)
        assertTrue(enc.length <= MediaSessionSpec.ARTWORK_URL_MAX)
        assertTrue(MediaSessionSpec.artwork(enc, origin) is Artwork.Data)
    }

    @Test
    fun sampleSizeKeepsArtworkSmall() {
        assertEquals(1, MediaSessionSpec.sampleSize(512, 512))
        assertEquals(2, MediaSessionSpec.sampleSize(513, 100))
        assertEquals(4, MediaSessionSpec.sampleSize(2000, 1500))
        assertEquals(8, MediaSessionSpec.sampleSize(100, 4096))
    }

    @Test
    fun argLimits() {
        fun args(vararg pairs: Pair<String, String>): JsonObject =
            buildJsonObject { for ((k, v) in pairs) put(k, JsonPrimitive(v)) }
        assertNull(BridgeArgs.validate("media.setMetadata", args("title" to "Episode 1", "artist" to "Me")))
        assertNotNull(BridgeArgs.validate("media.setMetadata", args("artist" to "Me")))
        assertNotNull(BridgeArgs.validate("media.setMetadata", args("title" to "x".repeat(257))))
        assertNotNull(
            BridgeArgs.validate("media.setMetadata", args("title" to "t", "artworkUrl" to "x".repeat(MediaSessionSpec.ARTWORK_URL_MAX + 1))),
        )
        assertNull(BridgeArgs.validate("media.setState", args("state" to "playing", "positionMs" to "1000")))
        assertNotNull(BridgeArgs.validate("media.setState", args("positionMs" to "1000")))
        val nine = buildJsonObject { put("actions", buildJsonArray { repeat(9) { add(JsonPrimitive("play")) } }) }
        assertNotNull(BridgeArgs.validate("media.setActions", nine))
        val ok = buildJsonObject { put("actions", buildJsonArray { add(JsonPrimitive("play")) }) }
        assertNull(BridgeArgs.validate("media.setActions", ok))
        assertNotNull(BridgeArgs.validate("media.setActions", args("actions" to "play")))
    }
}
