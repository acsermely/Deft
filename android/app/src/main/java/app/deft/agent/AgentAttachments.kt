package app.deft.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicLong

/** One file attached to a message, prepared for the model and for the UI. */
sealed interface AgentAttachment {
    val id: Long
    val name: String

    /** A downscaled JPEG for the model ([dataUrl]) plus a small bitmap for the UI. */
    class Image(
        override val id: Long,
        override val name: String,
        val dataUrl: String /* "data:image/jpeg;base64,..." */,
        val thumb: ImageBitmap,
    ) : AgentAttachment

    /** UTF-8 text, inlined into the user's message text when sent. */
    class Text(
        override val id: Long,
        override val name: String,
        val text: String,
        val truncated: Boolean,
    ) : AgentAttachment

    /** A PDF sent as a file part. [bytes] is the file size. */
    class Pdf(
        override val id: Long,
        override val name: String,
        val dataUrl: String /* "data:application/pdf;base64,..." */,
        val bytes: Int,
    ) : AgentAttachment
}

object AgentAttachments {
    const val MAX_ATTACHMENTS = 4
    const val MAX_TEXT_CHARS = 100_000
    const val MAX_TEXT_BYTES = 2_000_000
    const val MAX_PDF_BYTES = 5_000_000
    const val SNIFF_BYTES = 8 * 1024
    const val MAX_NAME_CHARS = 120

    enum class Kind { IMAGE, PDF, TEXT }

    sealed interface Result {
        class Ok(val attachment: AgentAttachment) : Result
        class Rejected(val reason: String) : Result
    }

    private val ids = AtomicLong(1)

    internal fun nextId(): Long = ids.getAndIncrement()

    /** Reads [uri] on IO and classifies it: image, PDF or text. Never throws. */
    suspend fun load(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        runCatching {
            when (kindFor(name, context.contentResolver.getType(uri))) {
                Kind.IMAGE -> loadImage(context, uri, name)
                Kind.PDF -> loadPdf(context, uri, name)
                Kind.TEXT -> loadText(context, uri, name)
                null -> sniffText(context, uri, name)
            }
        }.getOrElse { Result.Rejected("Couldn't read $name") }
    }

    private suspend fun loadImage(context: Context, uri: Uri, name: String): Result {
        val image = AgentImages.load(context, uri, name) ?: return Result.Rejected("Couldn't read $name")
        return Result.Ok(image)
    }

    private fun loadPdf(context: Context, uri: Uri, name: String): Result {
        val input = context.contentResolver.openInputStream(uri) ?: return Result.Rejected("Couldn't read $name")
        val bytes = readCapped(input, MAX_PDF_BYTES) ?: return Result.Rejected("PDF over 5 MB: $name")
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return Result.Ok(
            AgentAttachment.Pdf(
                id = nextId(),
                name = name,
                dataUrl = "data:application/pdf;base64,$base64",
                bytes = bytes.size,
            ),
        )
    }

    private fun loadText(context: Context, uri: Uri, name: String): Result {
        val input = context.contentResolver.openInputStream(uri) ?: return Result.Rejected("Couldn't read $name")
        val bytes = readCapped(input, MAX_TEXT_BYTES)
            ?: return Result.Rejected("File too large: $name (over 2 MB)")
        val (text, truncated) = decodeText(bytes)
        return Result.Ok(AgentAttachment.Text(id = nextId(), name = name, text = text, truncated = truncated))
    }

    /** No known type: take it as text only if the first bytes look like text. */
    private fun sniffText(context: Context, uri: Uri, name: String): Result {
        val sample = context.contentResolver.openInputStream(uri)?.let { readPrefix(it, SNIFF_BYTES) }
            ?: return Result.Rejected("Couldn't read $name")
        if (!looksLikeText(sample)) {
            return Result.Rejected("Can't attach $name: only images, PDFs and text files")
        }
        return loadText(context, uri, name)
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() } ?: "file"
    }

    /** Classifies by MIME type first, then by extension. Null means unknown: the caller sniffs the bytes. */
    internal fun kindFor(name: String, mime: String?): Kind? {
        val type = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            type == "image/svg+xml" -> Kind.TEXT
            type.startsWith("image/") -> Kind.IMAGE
            type == "application/pdf" || ext == "pdf" -> Kind.PDF
            type.startsWith("text/") || type in TEXT_MIMES -> Kind.TEXT
            ext in TEXT_EXTENSIONS -> Kind.TEXT
            ext in IMAGE_EXTENSIONS -> Kind.IMAGE
            else -> null
        }
    }

    /** True when there is no NUL byte in the first [SNIFF_BYTES] bytes and they decode as UTF-8. */
    internal fun looksLikeText(bytes: ByteArray): Boolean {
        val sample = bytes.copyOf(minOf(bytes.size, SNIFF_BYTES))
        if (sample.any { it == 0.toByte() }) return false
        // A sample cut off in the middle of a multi-byte character still counts as text.
        val slack = if (bytes.size > SNIFF_BYTES) 3 else 0
        for (cut in 0..slack) {
            if (decodesAsUtf8(sample.copyOf(sample.size - cut))) return true
        }
        return false
    }

    private fun decodesAsUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /** UTF-8 text without a BOM, with \r\n as \n, clipped to [MAX_TEXT_CHARS]. */
    internal fun decodeText(bytes: ByteArray): Pair<String, Boolean> {
        var text = String(bytes, Charsets.UTF_8)
        if (text.startsWith('\uFEFF')) text = text.substring(1)
        text = text.replace("\r\n", "\n")
        return if (text.length > MAX_TEXT_CHARS) {
            text.substring(0, MAX_TEXT_CHARS) to true
        } else {
            text to false
        }
    }

    /** The name as it appears in a text block: no quotes, angle brackets or control characters, at most 120 chars. */
    internal fun safeName(name: String): String {
        val cleaned = name.map { c ->
            when {
                c == '"' || c == '<' || c == '>' -> '_'
                c < ' ' -> ' '
                else -> c
            }
        }.joinToString("").trim()
        return cleaned.take(MAX_NAME_CHARS).ifEmpty { "file" }
    }

    /** The message text the model reads: the typed text, then each text file as a block. Blank parts are dropped. */
    fun modelText(typed: String, attachments: List<AgentAttachment>): String =
        (listOf(typed) + attachments.filterIsInstance<AgentAttachment.Text>()
            .map { textBlock(it.name, it.text, it.truncated) })
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

    /** The inlined form of a text file, as the model reads it. */
    internal fun textBlock(name: String, text: String, truncated: Boolean): String = buildString {
        append("<attached_file name=\"").append(safeName(name)).append("\">\n")
        // A file that contains the closing tag must not end its own block early.
        append(text.replace("</attached_file", "<\\/attached_file"))
        if (truncated) append("\n[file truncated after ").append(MAX_TEXT_CHARS).append(" characters]")
        append("\n</attached_file>")
    }

    /** Reads at most [max] bytes; null when the stream holds more. */
    internal fun readCapped(input: InputStream, max: Int): ByteArray? {
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (out.size() + n > max) return null
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    /** Reads up to [max] bytes, fewer at the end of the stream. */
    internal fun readPrefix(input: InputStream, max: Int): ByteArray = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(max)
        while (out.size() < max) {
            val n = stream.read(buf, 0, max - out.size())
            if (n < 0) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    private val TEXT_MIMES = setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/x-yaml",
        "application/yaml",
        "application/toml",
        "application/x-sh",
        "application/sql",
        "application/x-subrip",
    )

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "html", "htm", "css", "js", "mjs",
        "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs",
        "swift", "php", "sh", "bash", "zsh", "fish", "sql", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "properties", "gradle", "log", "srt", "vtt", "tex", "rst", "org", "env", "gitignore", "dockerfile",
        "svg",
    )

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
}
