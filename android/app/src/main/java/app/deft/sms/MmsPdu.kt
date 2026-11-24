package app.deft.sms

import java.io.ByteArrayOutputStream
import java.util.UUID

data class MmsNotification(
    val from: String,
    val subject: String,
    val contentLocation: String,
)

data class RetrievedMms(
    val from: String,
    val subject: String,
    val parts: List<MmsPart>,
)

data class MmsPart(
    val mime: String,
    val name: String,
    val bytes: ByteArray,
    val text: String?,
)

object MmsPdu {
    const val MAX_ATTACHMENT_BYTES = 512 * 1024

    private const val MESSAGE_TYPE = 0x8C
    private const val TRANSACTION_ID = 0x98
    private const val MMS_VERSION = 0x8D
    private const val FROM = 0x89
    private const val TO = 0x97
    private const val SUBJECT = 0x96
    private const val CONTENT_TYPE = 0x84
    private const val CONTENT_LOCATION = 0x83
    private const val INSERT_ADDRESS = 0x81
    private const val ADDRESS_PRESENT = 0x80

    private const val SEND_REQ = 0x80
    private const val NOTIFICATION_IND = 0x82
    private const val RETRIEVE_CONF = 0x84
    private const val MMS_VERSION_1_0 = 0x90

    private const val CT_MIXED = 0x23
    private const val CT_TEXT_PLAIN = 0x03
    private const val CT_JPEG = 0x1E
    private const val CT_GIF = 0x1D
    private const val CT_PNG = 0x20

    fun encodeSendReq(to: String, body: String?, mime: String?, attachment: ByteArray?): ByteArray {
        val parts = mutableListOf<EncodedPart>()
        if (!body.isNullOrBlank()) {
            parts += EncodedPart(CT_TEXT_PLAIN, body.toByteArray(Charsets.UTF_8))
        }
        if (attachment != null && attachment.isNotEmpty()) {
            val type = mime ?: "image/jpeg"
            parts += EncodedPart(shortType(type), attachment)
        }
        require(parts.isNotEmpty()) { "empty MMS" }

        val headers = ByteArrayOutputStream()
        headers.write(MESSAGE_TYPE)
        headers.write(SEND_REQ)
        writeTextHeader(headers, TRANSACTION_ID, UUID.randomUUID().toString().replace("-", ""))
        headers.write(MMS_VERSION)
        headers.write(MMS_VERSION_1_0)
        headers.write(FROM)
        headers.write(0x01)
        headers.write(INSERT_ADDRESS)
        writeTextHeader(headers, TO, plmn(to))
        headers.write(CONTENT_TYPE)
        headers.write(0x80 or CT_MIXED)

        val bodyOut = ByteArrayOutputStream()
        writeUintvar(bodyOut, parts.size)
        for (part in parts) {
            val partHeaders = ByteArrayOutputStream()
            partHeaders.write(0x80 or part.shortType)
            writeUintvar(bodyOut, partHeaders.size())
            writeUintvar(bodyOut, part.data.size)
            bodyOut.write(partHeaders.toByteArray())
            bodyOut.write(part.data)
        }
        return headers.toByteArray() + bodyOut.toByteArray()
    }

    fun parseNotification(raw: ByteArray): MmsNotification? {
        val pdu = locateMms(raw) ?: return null
        val r = Reader(pdu)
        var type = -1
        var from = ""
        var subject = ""
        var location = ""
        while (r.hasRemaining()) {
            val header = r.u8()
            when (header) {
                MESSAGE_TYPE -> type = r.u8()
                TRANSACTION_ID -> r.text()
                MMS_VERSION -> r.u8()
                FROM -> from = readFrom(r)
                SUBJECT -> subject = r.encodedString()
                CONTENT_LOCATION -> location = r.text()
                CONTENT_TYPE -> {
                    skipContentType(r)
                    break
                }
                else -> skipUnknown(r, header)
            }
        }
        if (type != NOTIFICATION_IND && location.isBlank()) return null
        if (location.isBlank()) return null
        return MmsNotification(from, subject, location)
    }

    fun parseRetrieve(raw: ByteArray): RetrievedMms? {
        val pdu = locateMms(raw) ?: return null
        val r = Reader(pdu)
        var type = -1
        var from = ""
        var subject = ""
        while (r.hasRemaining()) {
            val header = r.u8()
            when (header) {
                MESSAGE_TYPE -> type = r.u8()
                TRANSACTION_ID -> r.text()
                MMS_VERSION -> r.u8()
                FROM -> from = readFrom(r)
                TO -> r.encodedString()
                SUBJECT -> subject = r.encodedString()
                CONTENT_TYPE -> {
                    skipContentType(r)
                    break
                }
                else -> skipUnknown(r, header)
            }
        }
        if (type != RETRIEVE_CONF && type != SEND_REQ) {
            // Some carriers omit or use retrieve-conf only; still try parts.
        }
        val parts = parseMultipart(r)
        return RetrievedMms(from, subject, parts)
    }

    private fun parseMultipart(r: Reader): List<MmsPart> {
        if (!r.hasRemaining()) return emptyList()
        val n = r.uintvar()
        val out = mutableListOf<MmsPart>()
        repeat(n.coerceAtMost(16)) {
            if (!r.hasRemaining()) return@repeat
            val headerLen = r.uintvar()
            val dataLen = r.uintvar()
            val headerBytes = r.bytes(headerLen)
            val data = r.bytes(dataLen)
            val mime = parsePartContentType(headerBytes)
            val text = if (mime.startsWith("text/")) data.toString(Charsets.UTF_8) else null
            val name = when {
                mime.contains("jpeg") || mime.contains("jpg") -> "image.jpg"
                mime.contains("png") -> "image.png"
                mime.contains("gif") -> "image.gif"
                mime.startsWith("text/") -> "text.txt"
                else -> "part.bin"
            }
            out += MmsPart(mime, name, data, text)
        }
        return out
    }

    private fun parsePartContentType(headers: ByteArray): String {
        if (headers.isEmpty()) return "application/octet-stream"
        val b = headers[0].toInt() and 0xFF
        if (b >= 0x80) return wellKnownMime(b and 0x7F)
        val s = headers.toString(Charsets.US_ASCII)
        return s.substringBefore('\u0000').substringBefore(';').ifBlank { "application/octet-stream" }
    }

    private fun wellKnownMime(id: Int): String = when (id) {
        CT_TEXT_PLAIN -> "text/plain"
        CT_JPEG -> "image/jpeg"
        CT_PNG -> "image/png"
        CT_GIF -> "image/gif"
        CT_MIXED -> "application/vnd.wap.multipart.mixed"
        else -> "application/octet-stream"
    }

    private fun shortType(mime: String): Int = when {
        mime.contains("png") -> CT_PNG
        mime.contains("gif") -> CT_GIF
        mime.startsWith("text/") -> CT_TEXT_PLAIN
        else -> CT_JPEG
    }

    private fun plmn(to: String): String {
        val digits = to.filter { it.isDigit() || it == '+' }
        return "$digits/TYPE=PLMN"
    }

    private fun writeTextHeader(out: ByteArrayOutputStream, header: Int, value: String) {
        out.write(header)
        out.write(value.toByteArray(Charsets.US_ASCII))
        out.write(0)
    }

    private fun writeUintvar(out: ByteArrayOutputStream, value: Int) {
        var v = value.coerceAtLeast(0)
        val stack = ArrayDeque<Int>()
        stack.addFirst(v and 0x7F)
        v = v ushr 7
        while (v > 0) {
            stack.addFirst((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        for (b in stack) out.write(b)
    }

    private fun locateMms(raw: ByteArray): ByteArray? {
        val idx = raw.indexOf(MESSAGE_TYPE.toByte())
        if (idx < 0) return raw
        return raw.copyOfRange(idx, raw.size)
    }

    private fun readFrom(r: Reader): String {
        val len = r.valueLength()
        if (len <= 0) return ""
        val start = r.pos
        val token = r.u8()
        val addr = if (token == INSERT_ADDRESS) {
            ""
        } else {
            if (token != ADDRESS_PRESENT) {
                r.pos = start
            }
            r.text()
        }
        r.pos = start + len
        return addr.substringBefore("/TYPE")
    }

    private fun skipContentType(r: Reader) {
        if (!r.hasRemaining()) return
        val first = r.peek()
        when {
            first <= 30 -> {
                val len = r.u8()
                r.skip(len)
            }
            first == 31 -> {
                r.u8()
                val len = r.uintvar()
                r.skip(len)
            }
            first >= 0x80 -> r.u8()
            else -> r.text()
        }
    }

    private fun skipUnknown(r: Reader, header: Int) {
        if (!r.hasRemaining()) return
        val first = r.peek()
        when {
            header and 0x80 == 0 -> r.text()
            first <= 30 -> {
                val len = r.u8()
                r.skip(len)
            }
            first == 31 -> {
                r.u8()
                r.skip(r.uintvar())
            }
            first >= 0x80 -> r.u8()
            else -> r.text()
        }
    }

    private class EncodedPart(
        val shortType: Int,
        val data: ByteArray,
    )

    private class Reader(private val data: ByteArray) {
        var pos = 0
        fun hasRemaining() = pos < data.size
        fun peek() = data[pos].toInt() and 0xFF
        fun u8(): Int {
            if (pos >= data.size) return 0
            return data[pos++].toInt() and 0xFF
        }
        fun skip(n: Int) {
            pos = (pos + n).coerceAtMost(data.size)
        }
        fun bytes(n: Int): ByteArray {
            val end = (pos + n).coerceAtMost(data.size)
            val out = data.copyOfRange(pos, end)
            pos = end
            return out
        }
        fun uintvar(): Int {
            var result = 0
            repeat(5) {
                val b = u8()
                result = (result shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) return result
            }
            return result
        }
        fun text(): String {
            val start = pos
            while (pos < data.size && data[pos] != 0.toByte()) pos++
            val s = data.copyOfRange(start, pos).toString(Charsets.US_ASCII)
            if (pos < data.size) pos++
            return s
        }
        fun encodedString(): String {
            if (!hasRemaining()) return ""
            val first = peek()
            if (first <= 31) {
                valueLength()
                if (hasRemaining() && peek() >= 0x80) u8()
            }
            return text()
        }
        fun valueLength(): Int {
            val first = u8()
            return if (first < 31) first else uintvar()
        }
    }
}
