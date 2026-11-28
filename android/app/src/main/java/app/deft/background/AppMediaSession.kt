package app.deft.background

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import app.deft.bridge.MediaSessionSpec
import app.deft.bridge.MediaSessionSpec.Action
import app.deft.bridge.MediaSessionSpec.State

// DEVICE-UNCHECKED (media session): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a podcast sample keeps playing and is controllable from the lock screen and a headset.
// Remove this note once checked.
/**
 * One app's `MediaSessionCompat`. It exists only while that app's foreground service of
 * type `mediaPlayback` runs (see [AppForegroundService.media]) and is active the whole time, so
 * the lock screen, headsets and Bluetooth reach it. Buttons the app handles become `media.action`
 * events through [onAction]. Main thread only.
 */
class AppMediaSession(
    context: Context,
    val webAppId: String,
    sessionActivity: PendingIntent,
    private val onAction: (webAppId: String, payloadJson: String) -> Unit,
) {
    private val session = MediaSessionCompat(context.applicationContext, "deft-media-${webAppId.hashCode()}")

    var title: String? = null
        private set
    var artist: String? = null
        private set
    var album: String? = null
        private set
    var durationMs: Long? = null
        private set
    /** The URL [art] came from, so the same artwork is not fetched twice. */
    var artUrl: String? = null
        private set
    var art: Bitmap? = null
        private set
    var state: State = State.NONE
        private set
    var actions: Set<Action> = emptySet()
        private set
    private var positionMs: Long? = null
    private var rate: Float = 1f

    val token: MediaSessionCompat.Token get() = session.sessionToken

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = press(Action.PLAY)
        override fun onPause() = press(Action.PAUSE)
        override fun onStop() = press(Action.STOP)
        override fun onSkipToNext() = press(Action.NEXT)
        override fun onSkipToPrevious() = press(Action.PREVIOUS)
        override fun onFastForward() = press(Action.SEEK_FORWARD)
        override fun onRewind() = press(Action.SEEK_BACKWARD)
        override fun onSeekTo(pos: Long) = press(Action.SEEK_TO, pos)
    }

    init {
        session.setCallback(callback, Handler(Looper.getMainLooper()))
        session.setSessionActivity(sessionActivity)
        applyState()
        session.isActive = true
    }

    /** A button for [action] was pressed (session callback or the notification). Unhandled actions are dropped. */
    fun press(action: Action, seekMs: Long? = null) {
        if (action !in actions) return
        onAction(webAppId, MediaSessionSpec.actionEvent(action, seekMs))
    }

    fun setMetadata(title: String, artist: String?, album: String?, durationMs: Long?, artUrl: String?, art: Bitmap?) {
        this.title = title
        this.artist = artist
        this.album = album
        this.durationMs = durationMs
        this.artUrl = artUrl
        this.art = art
        val b = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
        if (artist != null) {
            b.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            b.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
        }
        if (album != null) b.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
        if (durationMs != null) b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        if (art != null) b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
        session.setMetadata(b.build())
    }

    fun setState(state: State, positionMs: Long?, rate: Float?) {
        this.state = state
        this.positionMs = positionMs
        this.rate = rate ?: 1f
        applyState()
    }

    fun setActions(actions: Set<Action>) {
        this.actions = actions
        applyState()
    }

    private fun applyState() {
        val position = positionMs ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MediaSessionSpec.playbackActions(actions))
                .setState(state.code, position, if (state == State.PLAYING) rate else 0f, SystemClock.elapsedRealtime())
                .build(),
        )
    }

    fun release() {
        session.isActive = false
        session.release()
    }
}
