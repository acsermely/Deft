package app.deft.bridge.modules

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.OsPermissions
import app.deft.bridge.jsonArg
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.TimeZone

class CalendarModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        OsPermissions.ensure(
            context,
            request.webAppId, request.background,
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            "Calendar permission not granted to the launcher",
        )?.let { return it }
        return when (request.method) {
            "calendar.listCalendars" -> listCalendars()
            "calendar.listEvents" -> listEvents(request)
            "calendar.create" -> create(request)
            "calendar.update" -> update(request)
            "calendar.delete" -> delete(request)
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }


    private fun listCalendars(): BridgeOutcome {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        )
        val arr = buildJsonArray {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                var count = 0
                while (c.moveToNext() && count < 200) {
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(c.getLong(idIdx)))
                            put("name", JsonPrimitive(c.getString(nameIdx).orEmpty()))
                        },
                    )
                    count++
                }
            }
        }
        return BridgeOutcome.Ok(arr)
    }

    private fun listEvents(request: BridgeRequest): BridgeOutcome {
        val start = request.args["start"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "start required (epoch ms)")
        val end = request.args["end"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "end required (epoch ms)")
        val limit = (request.args["limit"]?.jsonPrimitive?.intOrNull ?: 100).coerceIn(1, 100)
        val calendarId = jsonArg(request.args, "calendarId")?.toLongOrNull()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start)
        ContentUris.appendId(builder, end)
        val selection = if (calendarId != null) "${CalendarContract.Instances.CALENDAR_ID} = ?" else null
        val args = if (calendarId != null) arrayOf(calendarId.toString()) else null
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.DESCRIPTION,
        )
        val arr = buildJsonArray {
            context.contentResolver.query(builder.build(), projection, selection, args, "${CalendarContract.Instances.BEGIN} ASC")
                ?.use { c ->
                    val eventIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                    val titleIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                    val beginIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                    val endIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
                    val calIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
                    val descIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
                    var count = 0
                    while (c.moveToNext() && count < limit) {
                        add(
                            buildJsonObject {
                                put("eventId", JsonPrimitive(c.getLong(eventIdx)))
                                put("title", JsonPrimitive(c.getString(titleIdx).orEmpty()))
                                put("dtStart", JsonPrimitive(c.getLong(beginIdx)))
                                put("dtEnd", JsonPrimitive(c.getLong(endIdx)))
                                put("calendarId", JsonPrimitive(c.getLong(calIdx)))
                                put("description", JsonPrimitive(c.getString(descIdx).orEmpty()))
                            },
                        )
                        count++
                    }
                }
        }
        return BridgeOutcome.Ok(arr)
    }

    private fun create(request: BridgeRequest): BridgeOutcome {
        val calendarId = jsonArg(request.args, "calendarId")?.toLongOrNull()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "calendarId required")
        val title = jsonArg(request.args, "title") ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "title required")
        val dtStart = request.args["dtStart"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "dtStart required (epoch ms)")
        val dtEnd = request.args["dtEnd"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "dtEnd required (epoch ms)")
        val description = jsonArg(request.args, "description")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, dtStart)
            put(CalendarContract.Events.DTEND, dtEnd)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (!description.isNullOrBlank()) {
                put(CalendarContract.Events.DESCRIPTION, description)
            }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "could not create event")
        val eventId = ContentUris.parseId(uri)
        return BridgeOutcome.Ok(buildJsonObject { put("eventId", JsonPrimitive(eventId)) })
    }

    private fun update(request: BridgeRequest): BridgeOutcome {
        val eventId = jsonArg(request.args, "eventId")?.toLongOrNull()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "eventId required")
        val title = jsonArg(request.args, "title")
        val dtStart = request.args["dtStart"]?.jsonPrimitive?.content?.toLongOrNull()
        val dtEnd = request.args["dtEnd"]?.jsonPrimitive?.content?.toLongOrNull()
        val description = jsonArg(request.args, "description")
        if (title == null && dtStart == null && dtEnd == null && description == null) {
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "nothing to update")
        }
        val values = ContentValues().apply {
            if (title != null) put(CalendarContract.Events.TITLE, title)
            if (dtStart != null) put(CalendarContract.Events.DTSTART, dtStart)
            if (dtEnd != null) put(CalendarContract.Events.DTEND, dtEnd)
            if (description != null) put(CalendarContract.Events.DESCRIPTION, description)
            if (dtStart != null || dtEnd != null) {
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val rows = context.contentResolver.update(uri, values, null, null)
        if (rows == 0) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "event not found")
        return BridgeOutcome.Ok(buildJsonObject { put("eventId", JsonPrimitive(eventId)) })
    }

    private fun delete(request: BridgeRequest): BridgeOutcome {
        val eventId = jsonArg(request.args, "eventId")?.toLongOrNull()
            ?: return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "eventId required")
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val rows = context.contentResolver.delete(uri, null, null)
        if (rows == 0) return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, "event not found")
        return BridgeOutcome.Ok()
    }
}
