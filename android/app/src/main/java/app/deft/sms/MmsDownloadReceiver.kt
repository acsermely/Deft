package app.deft.sms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.FileProvider
import app.deft.bridge.BridgeService
import java.io.File

class MmsDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DOWNLOADED) return
        val path = intent.getStringExtra(EXTRA_PATH) ?: return
        val fallbackFrom = intent.getStringExtra(EXTRA_FROM).orEmpty()
        val fallbackSubject = intent.getStringExtra(EXTRA_SUBJECT).orEmpty()
        val file = File(path)
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
        file.delete()
        val parsed = MmsPdu.parseRetrieve(bytes)
        val from = parsed?.from?.ifBlank { fallbackFrom } ?: fallbackFrom
        val subject = parsed?.subject?.ifBlank { fallbackSubject } ?: fallbackSubject
        if (parsed != null) {
            insertInbox(context, from, subject, parsed)
        }
        BridgeService.incomingMms(context, from, subject)
    }

    companion object {
        const val ACTION_DOWNLOADED = "app.deft.MMS_DOWNLOADED"
        const val EXTRA_PATH = "path"
        const val EXTRA_FROM = "from"
        const val EXTRA_SUBJECT = "subject"

        fun startDownload(context: Context, notification: MmsNotification) {
            val file = File(File(context.cacheDir, "mms").apply { mkdirs() }, "in_${System.currentTimeMillis()}.pdu")
            file.writeBytes(ByteArray(0))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val done = Intent(context, MmsDownloadReceiver::class.java).apply {
                action = ACTION_DOWNLOADED
                putExtra(EXTRA_PATH, file.absolutePath)
                putExtra(EXTRA_FROM, notification.from)
                putExtra(EXTRA_SUBJECT, notification.subject)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                file.name.hashCode(),
                done,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val sms = SmsManager.getDefault()
            runCatching {
                sms.downloadMultimediaMessage(context, notification.contentLocation, uri, null, pi)
            }.onFailure { e ->
                Log.w("MmsDownload", "download failed", e)
                BridgeService.incomingMms(context, notification.from, notification.subject)
            }
        }

        fun insertInbox(context: Context, from: String, subject: String, parsed: RetrievedMms) {
            val values = ContentValues().apply {
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L)
                put(Telephony.Mms.READ, 0)
                if (subject.isNotBlank()) put(Telephony.Mms.SUBJECT, subject.take(100))
            }
            val uri = context.contentResolver.insert(Telephony.Mms.Inbox.CONTENT_URI, values) ?: return
            val id = uri.lastPathSegment ?: return
            if (from.isNotBlank()) {
                val addr = ContentValues().apply {
                    put(Telephony.Mms.Addr.ADDRESS, from)
                    put(Telephony.Mms.Addr.TYPE, 137)
                    put(Telephony.Mms.Addr.MSG_ID, id.toLong())
                    put(Telephony.Mms.Addr.CHARSET, 106)
                }
                context.contentResolver.insert(Uri.parse("content://mms/$id/addr"), addr)
            }
            for (part in parsed.parts) {
                val pv = ContentValues().apply {
                    put(Telephony.Mms.Part.MSG_ID, id.toLong())
                    put(Telephony.Mms.Part.CONTENT_TYPE, part.mime)
                    put(Telephony.Mms.Part.FILENAME, part.name)
                    put(Telephony.Mms.Part.NAME, part.name)
                    if (part.text != null) put(Telephony.Mms.Part.TEXT, part.text)
                }
                val partUri = context.contentResolver.insert(Uri.parse("content://mms/$id/part"), pv)
                if (part.text == null && partUri != null && part.bytes.isNotEmpty()) {
                    context.contentResolver.openOutputStream(partUri)?.use { it.write(part.bytes) }
                }
            }
        }
    }
}
