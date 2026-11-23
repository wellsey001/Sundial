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

    var agenda: Boolean
        get() = p.getBoolean("agenda", false)
        set(v) = p.edit().putBoolean("agenda", v).apply()

    /** Minutes before, -1 for none. */
    var defaultReminder: Int
        get() = p.getInt("defaultReminder", 10)
        set(v) = p.edit().putInt("defaultReminder", v).apply()
}
