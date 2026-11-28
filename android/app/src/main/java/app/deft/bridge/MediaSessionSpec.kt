package app.deft.bridge

import android.support.v4.media.session.PlaybackStateCompat
import java.net.URI
import java.util.Base64

/**
 * Names, limits and the artwork rule of `media.*`. Pure (the Android values are
 * compile-time constants), unit tested.
 */
object MediaSessionSpec {
    /** Longest `artworkUrl`, in characters: a data: image of about [ARTWORK_MAX_BYTES]. */
    const val ARTWORK_URL_MAX = 700_000

    /** Largest artwork the host reads, in bytes, before decoding. */
    const val ARTWORK_MAX_BYTES = 512 * 1024

    /** Artwork is scaled down until neither side is larger than this many pixels. */
    const val ARTWORK_MAX_PX = 512

    /** Longest title, artist and album kept, in characters. */
    const val TEXT_MAX = 256

    private const val RATE_MAX = 16f

    enum class State(val id: String, val code: Int) {
        PLAYING("playing", PlaybackStateCompat.STATE_PLAYING),
        PAUSED("paused", PlaybackStateCompat.STATE_PAUSED),
        STOPPED("stopped", PlaybackStateCompat.STATE_STOPPED),
        NONE("none", PlaybackStateCompat.STATE_NONE),
        ;

        companion object {
            fun fromId(id: String?): State? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * Bridge action names (the Media Session API's `nexttrack` / `previoustrack` are `next` /
     * `previous`; the page shim maps them) and the `PlaybackStateCompat.ACTION_*` flag of each.
     */
    enum class Action(val id: String, val flag: Long) {
        PLAY("play", PlaybackStateCompat.ACTION_PLAY),
        PAUSE("pause", PlaybackStateCompat.ACTION_PAUSE),
        STOP("stop", PlaybackStateCompat.ACTION_STOP),
        NEXT("next", PlaybackStateCompat.ACTION_SKIP_TO_NEXT),
        PREVIOUS("previous", PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS),
        SEEK_TO("seekto", PlaybackStateCompat.ACTION_SEEK_TO),
        SEEK_FORWARD("seekforward", PlaybackStateCompat.ACTION_FAST_FORWARD),
        SEEK_BACKWARD("seekbackward", PlaybackStateCompat.ACTION_REWIND),
        ;

        companion object {
            fun fromId(id: String?): Action? = entries.firstOrNull { it.id == id }
        }
    }

    /** The actions named in [ids]; null when one of them is not an action name. */
    fun actionsFrom(ids: List<String?>): Set<Action>? {
        val out = LinkedHashSet<Action>()
        for (id in ids) out += Action.fromId(id) ?: return null
        return out
    }

    /**
     * Flags for `PlaybackStateCompat.Builder.setActions`. Play or pause also adds play/pause, so a
     * headset's single button toggles.
     */
    fun playbackActions(actions: Set<Action>): Long {
        var flags = actions.fold(0L) { acc, a -> acc or a.flag }
        if (Action.PLAY in actions || Action.PAUSE in actions) flags = flags or PlaybackStateCompat.ACTION_PLAY_PAUSE
        return flags
    }

    /**
     * The media buttons of the notification, in order: previous, play or pause (pause while
     * playing, when the app handles it), next. At most three, so all fit the compact view.
     */
    fun notificationButtons(actions: Set<Action>, state: State): List<Action> {
        val out = mutableListOf<Action>()
        if (Action.PREVIOUS in actions) out += Action.PREVIOUS
        val toggle = when {
            state == State.PLAYING && Action.PAUSE in actions -> Action.PAUSE
            state != State.PLAYING && Action.PLAY in actions -> Action.PLAY
            Action.PAUSE in actions -> Action.PAUSE
            Action.PLAY in actions -> Action.PLAY
            else -> null
        }
        if (toggle != null) out += toggle
        if (Action.NEXT in actions) out += Action.NEXT
        return out
    }

    /** `media.action` payload. [seekMs] (for `seekto`) becomes `seekTime` in seconds, as on the web. */
    fun actionEvent(action: Action, seekMs: Long? = null): String =
        if (seekMs != null && action == Action.SEEK_TO) {
            """{"action":"${action.id}","seekTime":${seekMs.coerceAtLeast(0) / 1000.0}}"""
        } else {
            """{"action":"${action.id}"}"""
        }

    /** A position or duration in milliseconds: a whole or decimal number, 0 or more. */
    fun millis(text: String?): Long? {
        val v = text?.trim()?.toDoubleOrNull() ?: return null
        if (v.isNaN() || v < 0 || v > Long.MAX_VALUE / 2) return null
        return v.toLong()
    }

    /** Playback speed: more than 0, at most 16. */
    fun rate(text: String?): Float? {
        val v = text?.trim()?.toFloatOrNull() ?: return null
        return if (v > 0f && v <= RATE_MAX) v else null
    }

    sealed interface Artwork {
        /** A file of the app itself: [url] is absolute, on the app's own origin. */
        data class SameOrigin(val url: String) : Artwork

        /** A data: image, already decoded. */
        class Data(val bytes: ByteArray) : Artwork

        /** Not https on the app's origin and not a data: image. */
        data object Rejected : Artwork

        /** Larger than [ARTWORK_MAX_BYTES]. */
        data object TooLarge : Artwork
    }

    private val DATA_IMAGE = Regex("^data:image/(png|jpeg|jpg|webp|gif)(;[a-z0-9=.+-]*)*;base64,", RegexOption.IGNORE_CASE)

    /**
     * The artwork rule: only the app's own files (https, same host and port as [appOrigin], no
     * user name) or a base64 data: image. The host fetches it; the page never hands it a URL
     * elsewhere.
     */
    fun artwork(url: String, appOrigin: String): Artwork {
        val text = url.trim()
        if (text.startsWith("data:", ignoreCase = true)) {
            val head = DATA_IMAGE.find(text) ?: return Artwork.Rejected
            val body = text.substring(head.range.last + 1)
            // base64 is 4 characters per 3 bytes; refuse before decoding.
            if (body.length / 4L * 3 > ARTWORK_MAX_BYTES + 3) return Artwork.TooLarge
            val bytes = runCatching { Base64.getMimeDecoder().decode(body) }.getOrNull() ?: return Artwork.Rejected
            if (bytes.isEmpty()) return Artwork.Rejected
            if (bytes.size > ARTWORK_MAX_BYTES) return Artwork.TooLarge
            return Artwork.Data(bytes)
        }
        val u = runCatching { URI(text) }.getOrNull() ?: return Artwork.Rejected
        val o = runCatching { URI(appOrigin) }.getOrNull() ?: return Artwork.Rejected
        if (!u.isAbsolute || u.isOpaque) return Artwork.Rejected
        if (!u.scheme.equals("https", ignoreCase = true) || !o.scheme.equals("https", ignoreCase = true)) return Artwork.Rejected
        if (u.rawUserInfo != null) return Artwork.Rejected
        val host = u.host ?: return Artwork.Rejected
        if (!host.equals(o.host, ignoreCase = true)) return Artwork.Rejected
        if (port(u) != port(o)) return Artwork.Rejected
        if (u.rawPath.isNullOrEmpty() || u.rawPath.contains("..")) return Artwork.Rejected
        return Artwork.SameOrigin(u.toASCIIString().substringBefore('#'))
    }

    private fun port(u: URI): Int = if (u.port == -1) 443 else u.port

    /** BitmapFactory `inSampleSize` (a power of two) that brings [width] x [height] to [ARTWORK_MAX_PX] or below. */
    fun sampleSize(width: Int, height: Int, max: Int = ARTWORK_MAX_PX): Int {
        var size = 1
        while (width / size > max || height / size > max) size *= 2
        return size
    }
}
