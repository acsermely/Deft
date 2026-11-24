package app.deft.bridge.modules

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.jsonArg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class ContactsModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        return when (request.method) {
            "contacts.list", "contacts.search" -> list(request)
            "contacts.get" -> get(request)
            "contacts.create" -> create(request)
            "contacts.update" -> update(request)
            "contacts.delete" -> delete(request)
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private fun list(request: BridgeRequest): BridgeOutcome {
        val query = jsonArg(request.args, "query").orEmpty()
        val limit = request.args["limit"]?.jsonPrimitive?.intOrNull ?: 100
        val contacts = loadContacts(query, limit)
        val arr = buildJsonArray {
            for ((id, row) in contacts) {
                add(row.toJson(id))
            }
        }
        return BridgeOutcome.Ok(arr)
    }

    private fun get(request: BridgeRequest): BridgeOutcome {
        val id = jsonArg(request.args, "id") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "id required")
        val row = loadContacts("", 1, contactId = id)[id]
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "not found")
        return BridgeOutcome.Ok(row.toJson(id))
    }

    private fun create(request: BridgeRequest): BridgeOutcome {
        val name = jsonArg(request.args, "name") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "name required")
        val phone = jsonArg(request.args, "phone")
        val email = jsonArg(request.args, "email")
        val org = jsonArg(request.args, "org")
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
        ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build()
        if (!phone.isNullOrBlank()) ops += phoneOp(phone)
        if (!email.isNullOrBlank()) ops += emailOp(email)
        if (!org.isNullOrBlank()) ops += orgOp(org)
        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val uri = results.firstOrNull()?.uri
        return BridgeOutcome.Ok(buildJsonObject { put("rawContactUri", JsonPrimitive(uri?.toString() ?: "")) })
    }

    private fun update(request: BridgeRequest): BridgeOutcome {
        val id = jsonArg(request.args, "id") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "id required")
        val name = jsonArg(request.args, "name")
        val phone = jsonArg(request.args, "phone")
        val email = jsonArg(request.args, "email")
        val org = jsonArg(request.args, "org")
        if (name != null) {
            val values = android.content.ContentValues().apply {
                put(ContactsContract.Contacts.DISPLAY_NAME, name)
            }
            context.contentResolver.update(
                ContactsContract.Data.CONTENT_URI,
                values,
                "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(id, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
            )
        }
        val rawId = rawContactId(id)
        if (rawId != null) {
            if (phone != null) replacePhone(rawId, id, phone)
            if (email != null) replaceEmail(rawId, id, email)
            if (org != null) replaceOrg(rawId, id, org)
        }
        return BridgeOutcome.Ok()
    }

    private fun delete(request: BridgeRequest): BridgeOutcome {
        val id = jsonArg(request.args, "id") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "id required")
        val uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "false")
            .build()
        context.contentResolver.delete(uri, "${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(id))
        return BridgeOutcome.Ok()
    }

    private fun replacePhone(rawId: Long, contactId: String, number: String) {
        deleteDataRow(contactId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
        if (number.isBlank()) return
        context.contentResolver.applyBatch(
            ContactsContract.AUTHORITY,
            arrayListOf(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build(),
            ),
        )
    }

    private fun replaceEmail(rawId: Long, contactId: String, address: String) {
        deleteDataRow(contactId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
        if (address.isBlank()) return
        context.contentResolver.applyBatch(
            ContactsContract.AUTHORITY,
            arrayListOf(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, address)
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
                    .build(),
            ),
        )
    }

    private fun replaceOrg(rawId: Long, contactId: String, company: String) {
        deleteDataRow(contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
        if (company.isBlank()) return
        context.contentResolver.applyBatch(
            ContactsContract.AUTHORITY,
            arrayListOf(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, company)
                    .build(),
            ),
        )
    }

    private fun deleteDataRow(contactId: String, mime: String) {
        context.contentResolver.delete(
            ContactsContract.Data.CONTENT_URI,
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(contactId, mime),
        )
    }

    private fun rawContactId(contactId: String): Long? {
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID}=?",
            arrayOf(contactId),
            null,
        )?.use { c ->
            if (c.moveToNext()) return c.getLong(0)
        }
        return null
    }

    private fun phoneOp(number: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            .build()

    private fun emailOp(address: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, address)
            .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
            .build()

    private fun orgOp(company: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, company)
            .build()

    private fun loadContacts(query: String, limit: Int, contactId: String? = null): LinkedHashMap<String, ContactRow> {
        val map = linkedMapOf<String, ContactRow>()
        val nameSelection = when {
            contactId != null -> "${ContactsContract.Contacts._ID}=?"
            query.isBlank() -> null
            else -> "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
        }
        val nameArgs = when {
            contactId != null -> arrayOf(contactId)
            query.isBlank() -> null
            else -> arrayOf("%$query%")
        }
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            nameSelection,
            nameArgs,
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext() && map.size < limit) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: ""
                map[id] = ContactRow(name)
            }
        }
        if (map.isEmpty()) return map
        val ids = map.keys.toList()
        val placeholders = ids.joinToString(",") { "?" }
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
            ),
            "${ContactsContract.Data.CONTACT_ID} IN ($placeholders)",
            ids.toTypedArray(),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val row = map[id] ?: continue
                val mime = c.getString(1) ?: continue
                val data1 = c.getString(2) ?: ""
                when (mime) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        if (data1.isNotBlank()) row.phones += data1
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        if (data1.isNotBlank()) row.emails += data1
                    }
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        if (data1.isNotBlank()) row.org = data1
                    }
                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE -> row.hasPhoto = true
                }
            }
        }
        for ((id, row) in map) {
            if (row.hasPhoto) {
                row.photoUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id.toLong())
                    .toString() + "/display_photo"
            }
        }
        return map
    }

    private data class ContactRow(
        var name: String,
        val phones: MutableList<String> = mutableListOf(),
        val emails: MutableList<String> = mutableListOf(),
        var org: String = "",
        var hasPhoto: Boolean = false,
        var photoUri: String? = null,
    ) {
        fun toJson(id: String): JsonObject = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("name", JsonPrimitive(name))
            put("phones", JsonArray(phones.map { JsonPrimitive(it) }))
            put("emails", JsonArray(emails.map { JsonPrimitive(it) }))
            put("org", JsonPrimitive(org))
            if (photoUri != null) put("photoUri", JsonPrimitive(photoUri))
        }
    }

    companion object {
        fun observe(context: Context, scope: CoroutineScope, onChange: suspend () -> Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            scope.launch {
                val handler = Handler(Looper.getMainLooper())
                val observer = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        handler.removeCallbacksAndMessages(null)
                        handler.postDelayed({
                            scope.launch { onChange() }
                        }, 400L)
                    }
                }
                try {
                    context.contentResolver.registerContentObserver(
                        ContactsContract.Contacts.CONTENT_URI,
                        true,
                        observer,
                    )
                } catch (_: SecurityException) {
                    return@launch
                }
                try {
                    awaitCancellation()
                } finally {
                    handler.removeCallbacksAndMessages(null)
                    context.contentResolver.unregisterContentObserver(observer)
                }
            }
        }
    }
}
