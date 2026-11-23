package com.wellsey.sundial

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.CalendarContract.CalendarAlerts
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Shows event reminders. The phone's calendar storage sends EVENT_REMINDER when a reminder is due;
 * we post a notification and mark it as fired, so another calendar app won't show it twice.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            CalendarContract.ACTION_EVENT_REMINDER -> {
                if (!Prefs(ctx).notify || !Cal.hasPermission(ctx)) return
                val pending = goAsync()
                Thread {
                    try {
                        fireDue(ctx)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            ACTION_SNOOZE -> {
                val r = Reminder.from(intent)
                ctx.getSystemService(NotificationManager::class.java)?.cancel(r.code)
                val show = PendingIntent.getBroadcast(
                    ctx, r.code,
                    r.into(Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_SHOW)),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                ctx.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + SNOOZE_MS, show,
                )
            }
            ACTION_SHOW -> post(ctx, Reminder.from(intent))
        }
    }

    private fun fireDue(ctx: Context) {
        val now = System.currentTimeMillis()
        val cr = ctx.contentResolver
        val proj = arrayOf(
            CalendarAlerts._ID, CalendarAlerts.EVENT_ID, CalendarAlerts.BEGIN, CalendarAlerts.END,
            CalendarAlerts.TITLE, CalendarAlerts.EVENT_LOCATION, CalendarAlerts.ALL_DAY,
        )
        val due = ArrayList<Pair<Long, Reminder>>()
        try {
            cr.query(
                CalendarAlerts.CONTENT_URI, proj,
                "${CalendarAlerts.STATE}=? AND ${CalendarAlerts.ALARM_TIME}<=?",
                arrayOf(CalendarAlerts.STATE_SCHEDULED.toString(), (now + 60_000).toString()),
                "${CalendarAlerts.ALARM_TIME} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    due += c.getLong(0) to Reminder(
                        eventId = c.getLong(1),
                        begin = c.getLong(2),
                        end = c.getLong(3),
                        title = c.getString(4)?.takeIf { it.isNotBlank() } ?: "(No title)",
                        location = c.getString(5) ?: "",
                        allDay = c.getInt(6) != 0,
                    )
                }
            }
        } catch (_: Exception) {
            return
        }
        val hidden = Prefs(ctx).hidden
        for ((alertId, r) in due) {
            // Skip events that are already over (e.g. the phone was off)
            if (r.end > now && !isHidden(ctx, r.eventId, hidden)) post(ctx, r)
            try {
                cr.update(
                    ContentUris.withAppendedId(CalendarAlerts.CONTENT_URI, alertId),
                    ContentValues().apply {
                        put(CalendarAlerts.STATE, CalendarAlerts.STATE_FIRED)
                        put(CalendarAlerts.RECEIVED_TIME, now)
                        put(CalendarAlerts.NOTIFY_TIME, now)
                    },
                    null, null,
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun isHidden(ctx: Context, eventId: Long, hidden: Set<Long>): Boolean {
        if (hidden.isEmpty()) return false
        return try {
            ctx.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                arrayOf(CalendarContract.Events.CALENDAR_ID), null, null, null,
            )?.use { c -> c.moveToFirst() && c.getLong(0) in hidden } ?: false
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.wellsey.sundial.SNOOZE"
        const val ACTION_SHOW = "com.wellsey.sundial.SHOW_REMINDER"
        const val CHANNEL = "reminders"
        const val SNOOZE_MS = 10 * 60_000L

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Event reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Reminders for your calendar events"
                    enableVibration(true)
                },
            )
        }

        fun post(ctx: Context, r: Reminder) {
            ensureChannel(ctx)
            val open = PendingIntent.getActivity(
                ctx, r.code,
                Intent(ctx, EventActivity::class.java)
                    .putExtra("eventId", r.eventId)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, r.begin)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, r.end)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val snooze = PendingIntent.getBroadcast(
                ctx, r.code,
                r.into(Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_SNOOZE)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(r.title)
                .setContentText(r.describe(ctx))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_EVENT)
                .setColor(0xFFFF8A5C.toInt())
                .setWhen(if (r.allDay) System.currentTimeMillis() else r.begin)
                .setShowWhen(!r.allDay)
                .addAction(
                    Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Snooze 10 min", snooze).build(),
                )
                .build()
            ctx.getSystemService(NotificationManager::class.java)?.notify(r.code, n)
        }
    }
}

/** What a reminder notification needs to show. */
data class Reminder(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val title: String,
    val location: String,
    val allDay: Boolean,
) {
    val code: Int get() = (eventId * 31 + begin / 60_000).hashCode()

    fun describe(ctx: Context): String {
        val day = if (allDay) Cal.utcDate(begin) else Cal.localDate(begin)
        val today = LocalDate.now()
        val dayText = when (day) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM"))
        }
        var s = if (allDay) "$dayText · All day" else "$dayText · ${Fmt.time(ctx, begin)} – ${Fmt.time(ctx, end)}"
        if (location.isNotBlank()) s += " · $location"
        return s
    }

    fun into(i: Intent): Intent = i
        .putExtra("eventId", eventId).putExtra("begin", begin).putExtra("end", end)
        .putExtra("title", title).putExtra("location", location).putExtra("allDay", allDay)

    companion object {
        fun from(i: Intent) = Reminder(
            eventId = i.getLongExtra("eventId", -1),
            begin = i.getLongExtra("begin", 0),
            end = i.getLongExtra("end", 0),
            title = i.getStringExtra("title") ?: "",
            location = i.getStringExtra("location") ?: "",
            allDay = i.getBooleanExtra("allDay", false),
        )
    }
}
