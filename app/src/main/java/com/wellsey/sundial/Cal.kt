package com.wellsey.sundial

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A calendar on the phone (Google, Outlook, Samsung, local...). */
data class CalInfo(
    val id: Long,
    val name: String,
    val account: String,
    val accountType: String,
    val color: Int,
    val access: Int,
    val primary: Boolean,
) {
    val writable get() = access >= Calendars.CAL_ACCESS_CONTRIBUTOR
}

/** One occurrence of an event, as shown in the month and lists. */
data class Ev(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val location: String,
    val recurring: Boolean,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

/** Everything the editor needs about one event. */
class EventData(
    var id: Long = -1,
    var calendarId: Long = -1,
    var title: String = "",
    var location: String = "",
    var notes: String = "",
    var allDay: Boolean = false,
    var start: LocalDateTime = LocalDateTime.now(),
    /** For all-day events this is the last day (inclusive). */
    var end: LocalDateTime = LocalDateTime.now(),
    var rrule: String? = null,
    /** Minutes before, or -1 for no reminder. */
    var reminder: Int = -1,
    var dtstart: Long = 0,
)

object Cal {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private val zone get() = ZoneId.systemDefault()

    fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun utcDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    fun localDateTime(ms: Long): LocalDateTime = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()

    fun calendars(ctx: Context): List<CalInfo> {
        if (!hasPermission(ctx)) return emptyList()
        val out = ArrayList<CalInfo>()
        val proj = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.IS_PRIMARY,
        )
        try {
            ctx.contentResolver.query(Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out += CalInfo(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "Calendar",
                        account = c.getString(2) ?: "",
                        accountType = c.getString(3) ?: "",
                        color = c.getInt(4) or 0xFF000000.toInt(),
                        access = c.getInt(5),
                        primary = !c.isNull(6) && c.getInt(6) != 0,
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out.sortedWith(compareBy({ it.account.lowercase() }, { !it.primary }, { it.name.lowercase() }))
    }

    /** Event occurrences touching [from] up to (not including) [until]. */
    fun instances(ctx: Context, from: LocalDate, until: LocalDate, hidden: Set<Long>): List<Ev> {
        if (!hasPermission(ctx)) return emptyList()
        // Widen by a day each way: all-day events are stored at UTC midnight.
        val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = until.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val proj = arrayOf(
            Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.BEGIN, Instances.END,
            Instances.ALL_DAY, Instances.DISPLAY_COLOR, Instances.EVENT_LOCATION, Instances.RRULE,
            Instances.RDATE, Instances.STATUS,
        )
        val out = ArrayList<Ev>()
        try {
            ctx.contentResolver.query(uri, proj, null, null, "${Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val calId = c.getLong(1)
                    if (calId in hidden) continue
                    if (!c.isNull(10) && c.getInt(10) == Events.STATUS_CANCELED) continue
                    val b = c.getLong(3)
                    val e = c.getLong(4)
                    val allDay = c.getInt(5) != 0
                    val sd: LocalDate
                    var ed: LocalDate
                    if (allDay) {
                        sd = utcDate(b)
                        ed = utcDate(e).minusDays(1)
                    } else {
                        sd = localDate(b)
                        ed = localDate(maxOf(b, e - 1))
                    }
                    if (ed.isBefore(sd)) ed = sd
                    if (ed.isBefore(from) || !sd.isBefore(until)) continue
                    out += Ev(
                        eventId = c.getLong(0),
                        calendarId = calId,
                        title = c.getString(2)?.takeIf { it.isNotBlank() } ?: "(No title)",
                        begin = b,
                        end = e,
                        allDay = allDay,
                        color = c.getInt(6) or 0xFF000000.toInt(),
                        location = c.getString(7) ?: "",
                        recurring = !c.getString(8).isNullOrEmpty() || !c.getString(9).isNullOrEmpty(),
                        startDate = sd,
                        endDate = ed,
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Groups occurrences by each day they cover, all-day first then by time. */
    fun byDay(events: List<Ev>, from: LocalDate, until: LocalDate): Map<LocalDate, List<Ev>> {
        val map = HashMap<LocalDate, MutableList<Ev>>()
        for (ev in events) {
            var d = if (ev.startDate.isBefore(from)) from else ev.startDate
            while (!d.isAfter(ev.endDate) && d.isBefore(until)) {
                map.getOrPut(d) { ArrayList() }.add(ev)
                d = d.plusDays(1)
            }
        }
        val order = compareBy<Ev>({ !(it.allDay || it.startDate != it.endDate) }, { it.begin }, { it.title })
        return map.mapValues { it.value.sortedWith(order) }
    }

    fun load(ctx: Context, id: Long, instanceBegin: Long, instanceEnd: Long): EventData? {
        val proj = arrayOf(
            Events.CALENDAR_ID, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.ALL_DAY,
            Events.DTSTART, Events.DTEND, Events.DURATION, Events.RRULE,
        )
        val d = EventData(id = id)
        try {
            ctx.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), proj, null, null, null)
                ?.use { c ->
                    if (!c.moveToFirst()) return null
                    d.calendarId = c.getLong(0)
                    d.title = c.getString(1) ?: ""
                    d.location = c.getString(2) ?: ""
                    d.notes = c.getString(3) ?: ""
                    d.allDay = c.getInt(4) != 0
                    d.dtstart = c.getLong(5)
                    d.rrule = c.getString(8)?.takeIf { it.isNotEmpty() }
                    var b = d.dtstart
                    var e = if (!c.isNull(6) && c.getLong(6) > 0) c.getLong(6)
                    else b + parseDuration(c.getString(7))
                    if (instanceBegin > 0) {
                        b = instanceBegin
                        e = if (instanceEnd > 0) instanceEnd else b + (e - d.dtstart)
                    }
                    if (d.allDay) {
                        val sd = utcDate(b)
                        var ed = utcDate(e).minusDays(1)
                        if (ed.isBefore(sd)) ed = sd
                        d.start = sd.atStartOfDay()
                        d.end = ed.atStartOfDay()
                    } else {
                        d.start = localDateTime(b)
                        d.end = localDateTime(maxOf(b, e))
                    }
                    Unit
                } ?: return null
            ctx.contentResolver.query(
                Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES),
                "${Reminders.EVENT_ID}=?", arrayOf(id.toString()), "${Reminders.MINUTES} ASC",
            )?.use { c -> if (c.moveToFirst()) d.reminder = c.getInt(0) }
        } catch (_: Exception) {
            return null
        }
        return d
    }

    /** RFC 2445 durations such as P3600S, PT1H30M, P1D, P1W. */
    fun parseDuration(s: String?): Long {
        if (s.isNullOrEmpty()) return 3_600_000
        val m = Regex("""([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""").matchEntire(s.trim())
            ?: return 3_600_000
        fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        val secs = g(2) * 604800 + g(3) * 86400 + g(4) * 3600 + g(5) * 60 + g(6)
        return secs * 1000
    }

    /**
     * Saves [d]. When editing one occurrence of a repeating event, [instanceBegin] is that
     * occurrence's start, so moving it shifts the whole series by the same amount.
     * Returns the event id, or -1 if it failed.
     */
    fun save(ctx: Context, d: EventData, oldRrule: String?, instanceBegin: Long): Long {
        val startMs: Long
        val endMs: Long
        val tz: String
        if (d.allDay) {
            tz = "UTC"
            startMs = d.start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            var last = d.end.toLocalDate()
            if (last.isBefore(d.start.toLocalDate())) last = d.start.toLocalDate()
            endMs = last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            tz = zone.id
            startMs = d.start.atZone(zone).toInstant().toEpochMilli()
            endMs = maxOf(startMs, d.end.atZone(zone).toInstant().toEpochMilli())
        }
        val v = ContentValues()
        if (d.id < 0) v.put(Events.CALENDAR_ID, d.calendarId)
        v.put(Events.TITLE, d.title)
        v.put(Events.EVENT_LOCATION, d.location)
        v.put(Events.DESCRIPTION, d.notes)
        v.put(Events.ALL_DAY, if (d.allDay) 1 else 0)
        v.put(Events.EVENT_TIMEZONE, tz)
        val rule = d.rrule
        if (rule != null) {
            val dtstart = if (d.id >= 0 && oldRrule != null && instanceBegin > 0) d.dtstart + (startMs - instanceBegin)
            else startMs
            v.put(Events.DTSTART, dtstart)
            v.put(Events.RRULE, rule)
            v.putNull(Events.DTEND)
            v.put(
                Events.DURATION,
                if (d.allDay) "P${(endMs - startMs) / 86_400_000}D" else "P${(endMs - startMs) / 1000}S",
            )
        } else {
            v.put(Events.DTSTART, startMs)
            v.put(Events.DTEND, endMs)
            v.putNull(Events.RRULE)
            v.putNull(Events.DURATION)
        }
        val cr = ctx.contentResolver
        return try {
            val id = if (d.id < 0) {
                val uri = cr.insert(Events.CONTENT_URI, v) ?: return -1
                ContentUris.parseId(uri)
            } else {
                cr.update(ContentUris.withAppendedId(Events.CONTENT_URI, d.id), v, null, null)
                d.id
            }
            cr.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID}=?", arrayOf(id.toString()))
            if (d.reminder >= 0) {
                cr.insert(Reminders.CONTENT_URI, ContentValues().apply {
                    put(Reminders.EVENT_ID, id)
                    put(Reminders.MINUTES, d.reminder)
                    put(Reminders.METHOD, Reminders.METHOD_ALERT)
                })
            }
            id
        } catch (_: Exception) {
            -1
        }
    }

    fun deleteEvent(ctx: Context, id: Long): Boolean = try {
        ctx.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null) > 0
    } catch (_: Exception) {
        false
    }

    /** Cancels just one occurrence of a repeating event. */
    fun deleteOccurrence(ctx: Context, id: Long, instanceBegin: Long): Boolean = try {
        val v = ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, instanceBegin)
            put(Events.STATUS, Events.STATUS_CANCELED)
        }
        ctx.contentResolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, id), v) != null
    } catch (_: Exception) {
        false
    }

    /** Makes a calendar that lives only on this phone. Returns its id. */
    fun createLocalCalendar(ctx: Context): Long {
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, "Sundial")
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, "Sundial")
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, "sundial")
            put(Calendars.CALENDAR_DISPLAY_NAME, "On this phone")
            put(Calendars.CALENDAR_COLOR, 0xFFF4A72C.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, "Sundial")
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
        }
        return try {
            ctx.contentResolver.insert(uri, v)?.let { ContentUris.parseId(it) } ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    /** Asks each account to sync its calendars now. */
    fun syncNow(ctx: Context) {
        val accounts = calendars(ctx).filter { it.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL }
            .map { it.account to it.accountType }.distinct()
        for ((name, type) in accounts) {
            try {
                val extras = Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                }
                ContentResolver.requestSync(Account(name, type), CalendarContract.AUTHORITY, extras)
            } catch (_: Exception) {
            }
        }
    }
}
