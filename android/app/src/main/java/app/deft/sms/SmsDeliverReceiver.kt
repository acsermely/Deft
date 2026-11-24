package app.deft.sms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.deft.bridge.BridgeService

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val pending = goAsync()
        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            val from = messages.firstOrNull()?.displayOriginatingAddress.orEmpty()
            val body = messages.joinToString("") { it.displayMessageBody.orEmpty() }
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, from)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            BridgeService.incomingSms(context, from, body)
        } finally {
            pending.finish()
        }
    }
}

class WapPushDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pending = goAsync()
        try {
            val data = intent.getByteArrayExtra("data") ?: ByteArray(0)
            val parsed = MmsPdu.parseNotification(data)
            val from = parsed?.from?.ifBlank { extractSender(intent, data) } ?: extractSender(intent, data)
            val subject = parsed?.subject.orEmpty()
            if (parsed != null && parsed.contentLocation.isNotBlank()) {
                MmsDownloadReceiver.startDownload(
                    context,
                    parsed.copy(from = from, subject = subject),
                )
            } else {
                BridgeService.incomingMms(context, from, subject)
            }
        } finally {
            pending.finish()
        }
    }

    private fun extractSender(intent: Intent, data: ByteArray): String {
        return intent.getStringExtra("address")
            ?: intent.getStringExtra("originating_address")
            ?: Regex("\\+?\\d{7,15}").find(data.toString(Charsets.ISO_8859_1))?.value
            ?: ""
    }
}
