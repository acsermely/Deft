package app.deft.bridge.modules

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.deft.DeftApp
import app.deft.background.AppForegroundService
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.MediaSessionSpec
import app.deft.bridge.jsonArg
import app.deft.workspace.BundledApps
import app.deft.webview.LocalOriginPathHandler
import app.deft.workspace.DepStore
import app.deft.workspace.OverlayStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * `media.setMetadata`, `media.setState`, `media.setActions`, `media.release` (PARITY D4): the
 * app's media session for lock-screen, headset and Bluetooth controls. Works only while the app's
 * foreground service of type `mediaPlayback` runs, on screen or not. Reached through
 * [MediaModule], which owns the `media` prefix.
 */
class MediaSessionModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = when (request.method) {
        "media.setMetadata" -> setMetadata(request)
        "media.setState" -> setState(request)
        "media.setActions" -> setActions(request)
        "media.release" -> withContext(Dispatchers.Main) {
            AppForegroundService.releaseMedia(request.webAppId)
            BridgeOutcome.Ok()
        }
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
    }

    private suspend fun setMetadata(request: BridgeRequest): BridgeOutcome {
        val title = text(request, "title")
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "title required")
        val artist = text(request, "artist")
        val album = text(request, "album")
        val durationRaw = jsonArg(request.args, "durationMs")
        val duration = durationRaw?.let {
            MediaSessionSpec.millis(it) ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "durationMs must be 0 or more")
        }
        val artworkUrl = jsonArg(request.args, "artworkUrl")?.trim()?.takeIf { it.isNotEmpty() }
        val artwork = artworkUrl?.let { MediaSessionSpec.artwork(it, request.origin) }
        when (artwork) {
            MediaSessionSpec.Artwork.Rejected -> return BridgeOutcome.Err(
                BridgeErrors.INVALID_ARGS,
                "artworkUrl must be on the app's own origin or a data: image",
            )
            MediaSessionSpec.Artwork.TooLarge -> return BridgeOutcome.Err(
                BridgeErrors.INVALID_ARGS,
                "artwork is larger than ${MediaSessionSpec.ARTWORK_MAX_BYTES / 1024} KiB",
            )
            else -> Unit
        }
        val previousUrl = withContext(Dispatchers.Main) {
            val media = AppForegroundService.media(context, request.webAppId) ?: return@withContext null
            media.artUrl ?: ""
        } ?: return noSession()
        // The same artwork as last time is kept, not fetched again (setPositionState resends metadata).
        val reuse = artworkUrl != null && artworkUrl == previousUrl
        val bitmap = if (reuse || artwork == null) null else withContext(Dispatchers.IO) { load(request, artwork) }
        return withContext(Dispatchers.Main) {
            val media = AppForegroundService.media(context, request.webAppId) ?: return@withContext noSession()
            val art = if (reuse) media.art else bitmap
            media.setMetadata(title, artist, album, duration, artworkUrl.takeIf { art != null }, art)
            AppForegroundService.mediaChanged()
            BridgeOutcome.Ok(buildJsonObject { put("artwork", JsonPrimitive(art != null)) })
        }
    }

    private suspend fun setState(request: BridgeRequest): BridgeOutcome {
        val state = MediaSessionSpec.State.fromId(jsonArg(request.args, "state"))
            ?: return BridgeOutcome.Err(
                BridgeErrors.INVALID_ARGS,
                "state must be one of ${MediaSessionSpec.State.entries.joinToString { it.id }}",
            )
        val position = jsonArg(request.args, "positionMs")?.let {
            MediaSessionSpec.millis(it) ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "positionMs must be 0 or more")
        }
        val rate = jsonArg(request.args, "rate")?.let {
            MediaSessionSpec.rate(it) ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "rate must be more than 0 and at most 16")
        }
        return withContext(Dispatchers.Main) {
            val media = AppForegroundService.media(context, request.webAppId) ?: return@withContext noSession()
            val changed = media.state != state
            media.setState(state, position, rate)
            if (changed) AppForegroundService.mediaChanged()
            BridgeOutcome.Ok()
        }
    }

    private suspend fun setActions(request: BridgeRequest): BridgeOutcome {
        val list = request.args["actions"] as? JsonArray
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "actions must be an array")
        val actions = MediaSessionSpec.actionsFrom(list.map { (it as? JsonPrimitive)?.contentOrNull })
            ?: return BridgeOutcome.Err(
                BridgeErrors.INVALID_ARGS,
                "actions must be from ${MediaSessionSpec.Action.entries.joinToString { it.id }}",
            )
        return withContext(Dispatchers.Main) {
            val media = AppForegroundService.media(context, request.webAppId) ?: return@withContext noSession()
            val changed = media.actions != actions
            media.setActions(actions)
            if (changed) AppForegroundService.mediaChanged()
            BridgeOutcome.Ok()
        }
    }

    private fun noSession() = BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "start fgs type mediaPlayback first")

    private fun text(request: BridgeRequest, key: String): String? =
        jsonArg(request.args, key)?.trim()?.take(MediaSessionSpec.TEXT_MAX)?.takeIf { it.isNotEmpty() }

    /** The artwork as a bitmap no larger than [MediaSessionSpec.ARTWORK_MAX_PX]; null when it cannot be read. */
    private suspend fun load(request: BridgeRequest, artwork: MediaSessionSpec.Artwork): Bitmap? {
        val bytes = when (artwork) {
            is MediaSessionSpec.Artwork.Data -> artwork.bytes
            is MediaSessionSpec.Artwork.SameOrigin -> withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                runCatching { fetchSameOrigin(request, artwork.url) }.getOrNull()
            }
            else -> null
        } ?: return null
        return runCatching { decode(bytes) }.getOrNull()
    }

    /** The app's own file, through the same files the host serves, or its https origin when it has none here. */
    private suspend fun fetchSameOrigin(request: BridgeRequest, url: String): ByteArray? {
        val app = DeftApp.instance.repository.getApp(request.webAppId) ?: return null
        val dir = app.workspaceSlug?.let { DeftApp.instance.myApps.projectDir(it) }
            ?: BundledApps.idFromWebAppId(app.id)?.let { BundledApps.dir(context, it) }
        if (dir != null) {
            val loader = LocalOriginPathHandler.loader(
                dir,
                LocalOriginPathHandler.hostOfOrigin(app.origin),
                overlay = OverlayStore.dir(context, app.id),
                depStore = DepStore.root(context),
            )
            val response = loader.shouldInterceptRequest(Uri.parse(url)) ?: return null
            if (response.statusCode != 200 || response.mimeType?.startsWith("image/") != true) return null
            return response.data?.use { readCapped(it) }
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = FETCH_TIMEOUT_MS.toInt()
            conn.readTimeout = FETCH_TIMEOUT_MS.toInt()
            if (conn.responseCode != 200 || conn.contentType?.startsWith("image/") != true) return null
            conn.inputStream.use { readCapped(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MediaSessionSpec.ARTWORK_MAX_BYTES) return null
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = MediaSessionSpec.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    companion object {
        val METHODS = setOf("media.setMetadata", "media.setState", "media.setActions", "media.release")
        private const val FETCH_TIMEOUT_MS = 5_000L
    }
}
