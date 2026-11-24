package com.wellsey.sundial

import android.content.Context
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("sundial", Context.MODE_PRIVATE)

    var hidden: Set<Long>
        get() = p.getStringSet("hidden", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = p.edit().putStringSet("hidden", v.map { it.toString() }.toSet()).apply()

    var weekStart: DayOfWeek
        get() {
            val v = p.getInt("weekStart", 0)
            return if (v in 1..7) DayOfWeek.of(v) else WeekFields.of(Locale.getDefault()).firstDayOfWeek
        }
        set(v) = p.edit().putInt("weekStart", v.value).apply()

    var defaultCalendar: Long
        get() = p.getLong("defaultCal", -1)
        set(v) = p.edit().putLong("defaultCal", v).apply()

    /** 0 month, 1 week, 2 day, 3 list. */
    var mode: Int
        get() = p.getInt("mode", if (p.getBoolean("agenda", false)) 3 else 0)
        set(v) = p.edit().putInt("mode", v).apply()

    /** Minutes before, -1 for none. */
    var defaultReminder: Int
        get() = p.getInt("defaultReminder", 10)
        set(v) = p.edit().putInt("defaultReminder", v).apply()

    /** Show reminder notifications. */
    var notify: Boolean
        get() = p.getBoolean("notify", true)
        set(v) = p.edit().putBoolean("notify", v).apply()
}
