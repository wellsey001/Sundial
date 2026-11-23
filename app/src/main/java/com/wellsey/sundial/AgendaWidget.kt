package com.wellsey.sundial

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Home-screen widget: today's date and what's coming up this week. */
class AgendaWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(ctx, mgr, id)
        scheduleMidnight(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        update(ctx, mgr, id)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            Intent.ACTION_PROVIDER_CHANGED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED, ACTION_REFRESH,
            -> refresh(ctx)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.wellsey.sundial.REFRESH_WIDGET"
        private const val DAYS = 14

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, AgendaWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) update(ctx, mgr, id)
            scheduleMidnight(ctx)
        }

        private fun scheduleMidnight(ctx: Context) {
            val at = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 5_000
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, AgendaWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            ctx.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, at, pi)
        }

        private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val today = LocalDate.now()
            val v = RemoteViews(ctx.packageName, R.layout.widget_agenda)
            v.setTextViewText(R.id.w_day, today.format(DateTimeFormatter.ofPattern("EEEE")).uppercase())
            v.setTextViewText(R.id.w_date, today.format(DateTimeFormatter.ofPattern("d MMMM")))

            val openApp = PendingIntent.getActivity(
                ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags(),
            )
            v.setOnClickPendingIntent(R.id.w_header, openApp)
            v.setOnClickPendingIntent(R.id.w_empty, openApp)
            val add = PendingIntent.getActivity(
                ctx, 100_000 + id,
                Intent(ctx, EventActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                flags(),
            )
            v.setOnClickPendingIntent(R.id.w_add, add)
            v.removeAllViews(R.id.w_rows)

            if (!Cal.hasPermission(ctx)) {
                v.setTextViewText(R.id.w_empty, "Open Sundial to allow calendar access")
                v.setViewVisibility(R.id.w_empty, View.VISIBLE)
                mgr.updateAppWidget(id, v)
                return
            }

            // Fit as many lines as the widget's height allows (~40dp each, header ~64dp).
            val opts = mgr.getAppWidgetOptions(id)
            val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180).takeIf { it > 0 } ?: 180
            val maxLines = ((h - 64) / 40).coerceIn(1, 14)

            val until = today.plusDays(DAYS.toLong())
            val map = Cal.byDay(Cal.instances(ctx, today, until, Prefs(ctx).hidden), today, until)
            val now = System.currentTimeMillis()
            var used = 0
            var shown = 0
            var d = today
            loop@ while (d.isBefore(until)) {
                val list = map[d].orEmpty().filter { d != today || it.allDay || it.end > now }
                if (list.isNotEmpty()) {
                    if (d != today) {
                        if (used + 2 > maxLines) break@loop
                        val head = RemoteViews(ctx.packageName, R.layout.widget_day)
                        head.setTextViewText(R.id.w_day_title, when (d) {
                            today.plusDays(1) -> "Tomorrow"
                            else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMM"))
                        })
                        v.addView(R.id.w_rows, head)
                        used++
                    }
                    for (ev in list) {
                        if (used >= maxLines) break@loop
                        val row = RemoteViews(ctx.packageName, R.layout.widget_row)
                        row.setTextViewText(R.id.w_title, ev.title)
                        var sub = Fmt.whenOn(ctx, ev, d)
                        if (ev.location.isNotBlank()) sub += " · " + ev.location
                        row.setTextViewText(R.id.w_when, sub)
                        row.setInt(R.id.w_bar, "setColorFilter", ev.color)
                        val open = PendingIntent.getActivity(
                            ctx, (ev.eventId * 31 + ev.begin / 60_000).hashCode() xor id,
                            Intent(ctx, EventActivity::class.java)
                                .putExtra("eventId", ev.eventId)
                                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ev.begin)
                                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ev.end)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                            flags(),
                        )
                        row.setOnClickPendingIntent(R.id.w_row, open)
                        v.addView(R.id.w_rows, row)
                        used++
                        shown++
                    }
                }
                d = d.plusDays(1)
            }
            if (shown == 0) {
                v.setTextViewText(R.id.w_empty, "Nothing planned for the next two weeks")
                v.setViewVisibility(R.id.w_empty, View.VISIBLE)
            } else {
                v.setViewVisibility(R.id.w_empty, View.GONE)
            }
            mgr.updateAppWidget(id, v)
        }
    }
}
