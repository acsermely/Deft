package app.deft.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Decodes image files into [AgentAttachment.Image] (downscaled JPEG plus a UI thumbnail). */
object AgentImages {
    const val MAX_SIDE = 1568      // px, long side sent to the model
    const val JPEG_QUALITY = 85
    const val THUMB_SIDE = 160     // px, long side of the UI thumbnail

    /**
     * Decodes [uri] at a reduced size (long side at most [MAX_SIDE], EXIF rotation applied), flattens it onto white,
     * and encodes it as a JPEG data URL. Returns null if the image cannot be read or decoded. Runs on IO.
     */
    suspend fun load(context: Context, uri: Uri, name: String): AgentAttachment.Image? = withContext(Dispatchers.IO) {
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (w, h) = targetSize(info.size.width, info.size.height, MAX_SIDE)
                decoder.setTargetSize(w, h)
            }
            val flat = flattenOnWhite(decoded)
            decoded.recycle()

            val out = ByteArrayOutputStream()
            if (!flat.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                flat.recycle()
                return@runCatching null
            }
            val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

            val (tw, th) = targetSize(flat.width, flat.height, THUMB_SIDE)
            val thumb = Bitmap.createScaledBitmap(flat, tw, th, true)
            flat.recycle()
            AgentAttachment.Image(
                id = AgentAttachments.nextId(),
                name = name,
                dataUrl = "data:image/jpeg;base64,$base64",
                thumb = thumb.asImageBitmap(),
            )
        }.getOrNull()
    }

    /** Output size for a [width] x [height] image whose long side is capped at [maxSide]. Never upscales. */
    internal fun targetSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val w = max(width, 1)
        val h = max(height, 1)
        val long = max(w, h)
        if (long <= maxSide) return w to h
        val scale = maxSide.toDouble() / long
        return max((w * scale).roundToInt(), 1) to max((h * scale).roundToInt(), 1)
    }

    private fun flattenOnWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }
}
