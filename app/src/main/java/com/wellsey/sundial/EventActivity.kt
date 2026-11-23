package com.wellsey.sundial

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Adds, edits, views and deletes one event. */
class EventActivity : Activity() {
    private lateinit var prefs: Prefs
    private var d = EventData()
    private var oldRrule: String? = null
    private var instanceBegin = 0L
    private var isNew = true
    private var readOnly = false
    private var calendars: List<CalInfo> = emptyList()

    private lateinit var titleEdit: EditText
    private lateinit var allDaySwitch: Switch
    private lateinit var startDate: TextView
    private lateinit var startTime: TextView
    private lateinit var endDate: TextView
    private lateinit var endTime: TextView
    private lateinit var repeatText: TextView
    private lateinit var repeatNote: TextView
    private lateinit var calDot: View
    private lateinit var calText: TextView
    private lateinit var reminderText: TextView
    private lateinit var locationEdit: EditText
    private lateinit var notesEdit: EditText

    private val repeats = listOf(
        null to "Does not repeat",
        "FREQ=DAILY" to "Every day",
        "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR" to "Every weekday",
        "FREQ=WEEKLY" to "Every week",
        "FREQ=WEEKLY;INTERVAL=2" to "Every two weeks",
        "FREQ=MONTHLY" to "Every month",
        "FREQ=YEARLY" to "Every year",
    )
    private val reminders = listOf(-1, 0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        styleWindow()
        prefs = Prefs(this)
        if (!Cal.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1)
            return
        }
        setup()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Cal.hasPermission(this)) setup() else finish()
    }

    private fun setup() {
        calendars = Cal.calendars(this)
        if (!readIntent()) {
            Toast.makeText(this, "That event couldn't be found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        buildUi()
        refresh()
        if (isNew) {
            titleEdit.requestFocus()
            titleEdit.postDelayed({
                getSystemService(InputMethodManager::class.java)?.showSoftInput(titleEdit, InputMethodManager.SHOW_IMPLICIT)
            }, 250)
        }
    }

    /** Works out which event to show. Returns false if it doesn't exist. */
    private fun readIntent(): Boolean {
        val i = intent
        var id = i.getLongExtra("eventId", -1)
        val data = i.data
        if (id < 0 && data != null && data.pathSegments.size >= 2 && data.pathSegments[0] == "events") {
            id = try { ContentUris.parseId(data) } catch (_: Exception) { -1 }
        }
        val begin = i.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0)
        val end = i.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0)

        if (id >= 0) {
            d = Cal.load(this, id, begin, end) ?: return false
            isNew = false
            oldRrule = d.rrule
            instanceBegin = if (d.rrule != null) (if (begin > 0) begin else d.dtstart) else 0
            val cal = calendars.firstOrNull { it.id == d.calendarId }
            readOnly = cal == null || !cal.writable
            return true
        }

        // New event
        isNew = true
        val allDay = i.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
        val start: LocalDateTime
        var finish: LocalDateTime
        if (begin > 0) {
            start = if (allDay) Cal.utcDate(begin).atStartOfDay() else Cal.localDateTime(begin)
            finish = when {
                end <= 0 -> if (allDay) start else start.plusHours(1)
                allDay -> Cal.utcDate(end).minusDays(1).atStartOfDay()
                else -> Cal.localDateTime(end)
            }
            if (finish.isBefore(start)) finish = start
        } else {
            val day = if (i.hasExtra("date")) LocalDate.ofEpochDay(i.getLongExtra("date", 0)) else LocalDate.now()
            val now = LocalTime.now()
            // Next whole hour today, 9am on other days
            val t = if (day == LocalDate.now() && now.hour < 23) LocalTime.of(now.hour + 1, 0) else LocalTime.of(9, 0)
            start = day.atTime(t)
            finish = start.plusHours(1)
        }
        d = EventData(
            title = i.getStringExtra(Events.TITLE) ?: "",
            location = i.getStringExtra(Events.EVENT_LOCATION) ?: "",
            notes = i.getStringExtra(Events.DESCRIPTION) ?: "",
            allDay = allDay,
            start = start,
            end = finish,
            reminder = if (allDay) -1 else prefs.defaultReminder,
        )
        i.getStringExtra(Events.RRULE)?.takeIf { it.isNotEmpty() }?.let { d.rrule = it }
        d.calendarId = pickDefaultCalendar()
        return true
    }

    private fun writableCalendars() = calendars.filter { it.writable && it.id !in prefs.hidden }
        .ifEmpty { calendars.filter { it.writable } }

    private fun pickDefaultCalendar(): Long {
        var list = writableCalendars()
        if (list.isEmpty()) {
            Cal.createLocalCalendar(this)
            calendars = Cal.calendars(this)
            list = writableCalendars()
        }
        return list.firstOrNull { it.id == prefs.defaultCalendar }?.id
            ?: list.firstOrNull { it.primary && it.accountType == "com.google" }?.id
            ?: list.firstOrNull { it.primary }?.id
            ?: list.firstOrNull()?.id
            ?: -1
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi() {
        val root = vbox().apply { setBackgroundColor(Pal.bg) }

        val bar = hbox().apply { setPadding(dp(8), dp(10), dp(14), dp(10)) }
        bar.addView(iconButton(Icon.Kind.CLOSE) { finish() })
        bar.addView(label(if (readOnly) "Event" else if (isNew) "New event" else "Edit event", 18f, Pal.text, bold = true)
            .apply { setPadding(dp(8), 0, 0, 0) }, lp(0, WRAP, 1f))
        if (!readOnly) bar.addView(pill("Save", true) { save() })
        root.addView(bar, lp(MATCH, WRAP))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        val body = vbox().apply { setPadding(dp(14), dp(4), dp(14), dp(32)) }
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, lp(MATCH, 0, 1f))

        // Title
        titleEdit = edit("Add title", 22f, multi = false).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        body.addView(card(titleEdit))

        // When
        val whenCard = vbox()
        allDaySwitch = Switch(this).apply {
            isEnabled = !readOnly
            setOnCheckedChangeListener { _, on ->
                if (on == d.allDay) return@setOnCheckedChangeListener
                d.allDay = on
                if (on) {
                    d.start = d.start.toLocalDate().atStartOfDay()
                    d.end = d.end.toLocalDate().atStartOfDay()
                } else {
                    d.start = d.start.toLocalDate().atTime(9, 0)
                    d.end = d.end.toLocalDate().atTime(10, 0)
                }
                refresh()
            }
        }
        whenCard.addView(row(Icon.Kind.CLOCK, label("All day", 16f), allDaySwitch) { if (!readOnly) allDaySwitch.toggle() })
        startDate = chip { pickDate(true) }
        startTime = chip { pickTime(true) }
        endDate = chip { pickDate(false) }
        endTime = chip { pickTime(false) }
        whenCard.addView(timeRow("Starts", startDate, startTime))
        whenCard.addView(timeRow("Ends", endDate, endTime))
        repeatText = label("", 16f)
        whenCard.addView(row(Icon.Kind.REPEAT, repeatText, null) { pickRepeat() })
        repeatNote = label("Changes apply to every repeat of this event.", 12f, Pal.sub, lines = 0)
            .apply { setPadding(dp(52), 0, dp(8), dp(10)) }
        whenCard.addView(repeatNote)
        body.addView(card(whenCard))

        // Calendar + reminder
        val metaCard = vbox()
        val calBox = hbox()
        calDot = View(this)
        calBox.addView(calDot, lp(dp(12), dp(12)).apply { rightMargin = dp(10) })
        calText = label("", 16f)
        calBox.addView(calText, lp(0, WRAP, 1f))
        metaCard.addView(row(Icon.Kind.CALENDAR, calBox, null) { pickCalendar() })
        reminderText = label("", 16f)
        metaCard.addView(row(Icon.Kind.BELL, reminderText, null) { pickReminder() })
        body.addView(card(metaCard))

        // Location + notes
        val moreCard = vbox()
        locationEdit = edit("Add location", 16f, multi = false)
        notesEdit = edit("Add notes", 16f, multi = true)
        moreCard.addView(row(Icon.Kind.PIN, locationEdit, null, null))
        moreCard.addView(row(Icon.Kind.NOTES, notesEdit, null, null))
        body.addView(card(moreCard))

        if (!isNew && !readOnly) {
            body.addView(label("Delete event", 16f, Pal.danger, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
                pressable(dpf(16f))
                setOnClickListener { confirmDelete() }
            }, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        if (readOnly) {
            body.addView(label("This calendar is read-only, so this event can't be changed here.", 13f, Pal.sub, lines = 0)
                .apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(12), dp(16), 0) })
        }

        titleEdit.setText(d.title)
        locationEdit.setText(d.location)
        notesEdit.setText(d.notes)
        allDaySwitch.isChecked = d.allDay
        setContentView(root)
    }

    private fun card(content: View): View {
        val c = FrameLayout(this).apply {
            background = rounded(Pal.surface, dpf(20f))
            setPadding(dp(4), dp(6), dp(4), dp(6))
            layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        c.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        return c
    }

    private fun row(icon: Icon.Kind, content: View, trailing: View?, onClick: (() -> Unit)? = null): View {
        val r = hbox(if (content is EditText && (content.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) Gravity.TOP else Gravity.CENTER_VERTICAL)
        r.setPadding(dp(14), dp(10), dp(14), dp(10))
        r.minimumHeight = dp(52)
        r.addView(Icon(this, icon, Pal.sub), lp(dp(22), dp(22)).apply { rightMargin = dp(16); topMargin = dp(2) })
        r.addView(content, lp(0, WRAP, 1f))
        if (trailing != null) r.addView(trailing)
        if (onClick != null && !readOnly) {
            r.pressable(dpf(16f))
            r.setOnClickListener { onClick() }
        }
        return r
    }

    private fun timeRow(name: String, date: TextView, time: TextView): View {
        val r = hbox()
        r.setPadding(dp(52), dp(4), dp(14), dp(8))
        r.addView(label(name, 15f, Pal.sub), lp(0, WRAP, 1f))
        r.addView(date, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        r.addView(time)
        return r
    }

    private fun chip(onClick: () -> Unit): TextView = label("", 15f, Pal.text, bold = true).apply {
        val radius = dpf(12f)
        background = rounded(Pal.surface2, radius)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        if (!readOnly) {
            pressable(radius)
            setOnClickListener { onClick() }
        }
    }

    private fun edit(hint: String, size: Float, multi: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        textSize = size
        setTextColor(Pal.text)
        setHintTextColor(Pal.faint)
        background = null
        setPadding(0, dp(4), 0, dp(4))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        if (multi) minLines = 3
        isEnabled = !readOnly
        if (readOnly) setTextColor(Pal.text)
    }

    // ---------------------------------------------------------------- state

    private fun refresh() {
        val dateFmt = DateTimeFormatter.ofPattern(if (d.start.year == d.end.year && d.start.year == LocalDate.now().year) "EEE d MMM" else "EEE d MMM yyyy")
        val timeFmt = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a")
        startDate.text = d.start.format(dateFmt)
        endDate.text = d.end.format(dateFmt)
        startTime.text = d.start.format(timeFmt)
        endTime.text = d.end.format(timeFmt)
        startTime.visibility = if (d.allDay) View.GONE else View.VISIBLE
        endTime.visibility = if (d.allDay) View.GONE else View.VISIBLE
        val badEnd = if (d.allDay) d.end.toLocalDate().isBefore(d.start.toLocalDate()) else d.end.isBefore(d.start)
        endDate.paintFlags = if (badEnd) endDate.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        else endDate.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()

        repeatText.text = repeats.firstOrNull { it.first == d.rrule }?.second ?: describeRule(d.rrule)
        repeatNote.visibility = if (!isNew && oldRrule != null && !readOnly) View.VISIBLE else View.GONE

        val cal = calendars.firstOrNull { it.id == d.calendarId }
        calText.text = cal?.name ?: "No calendar"
        calDot.background = rounded(cal?.color ?: Pal.faint, dpf(6f))
        reminderText.text = reminderLabel(d.reminder)
    }

    private fun describeRule(rule: String?): String {
        if (rule == null) return "Does not repeat"
        val freq = Regex("FREQ=(\\w+)").find(rule)?.groupValues?.get(1)
        return when (freq) {
            "DAILY" -> "Repeats daily (custom)"
            "WEEKLY" -> "Repeats weekly (custom)"
            "MONTHLY" -> "Repeats monthly (custom)"
            "YEARLY" -> "Repeats yearly (custom)"
            else -> "Repeats (custom)"
        }
    }

    private fun reminderLabel(m: Int): String = when {
        m < 0 -> "No reminder"
        m == 0 -> "At the time"
        m % 10080 == 0 -> plural(m / 10080, "week") + " before"
        m % 1440 == 0 -> plural(m / 1440, "day") + " before"
        m % 60 == 0 -> plural(m / 60, "hour") + " before"
        else -> plural(m, "minute") + " before"
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    private fun pickDate(isStart: Boolean) {
        val cur = if (isStart) d.start else d.end
        DatePickerDialog(this, { _, y, m, day ->
            val chosen = LocalDate.of(y, m + 1, day)
            if (isStart) {
                val length = Duration.between(d.start, d.end)
                d.start = chosen.atTime(d.start.toLocalTime())
                d.end = d.start.plus(if (length.isNegative) Duration.ZERO else length)
            } else {
                d.end = chosen.atTime(d.end.toLocalTime())
                if (d.end.isBefore(d.start)) {
                    d.end = if (d.allDay) d.start else d.start.plusHours(1)
                }
            }
            refresh()
        }, cur.year, cur.monthValue - 1, cur.dayOfMonth).apply {
            datePicker.firstDayOfWeek = prefs.weekStart.value % 7 + 1
        }.show()
    }

    private fun pickTime(isStart: Boolean) {
        val cur = if (isStart) d.start else d.end
        TimePickerDialog(this, { _, h, min ->
            if (isStart) {
                val length = Duration.between(d.start, d.end)
                d.start = d.start.toLocalDate().atTime(h, min)
                d.end = d.start.plus(if (length.isNegative) Duration.ofHours(1) else length)
            } else {
                d.end = d.end.toLocalDate().atTime(h, min)
                if (d.end.isBefore(d.start)) {
                    // Probably meant the next day
                    d.end = d.end.plusDays(1)
                }
            }
            refresh()
        }, cur.hour, cur.minute, DateFormat.is24HourFormat(this)).show()
    }

    private fun pickRepeat() {
        val items = repeats.map { it.second }.toMutableList()
        val custom = d.rrule != null && repeats.none { it.first == d.rrule }
        if (custom) items += describeRule(d.rrule)
        val checked = if (custom) items.size - 1 else repeats.indexOfFirst { it.first == d.rrule }
        AlertDialog.Builder(this)
            .setTitle("Repeat")
            .setSingleChoiceItems(items.toTypedArray(), checked) { dlg, which ->
                if (which < repeats.size) d.rrule = repeats[which].first
                refresh()
                dlg.dismiss()
            }
            .show()
    }

    private fun pickReminder() {
        val opts = reminders.toMutableList()
        if (d.reminder !in opts) opts += d.reminder
        AlertDialog.Builder(this)
            .setTitle("Reminder")
            .setSingleChoiceItems(opts.map { reminderLabel(it) }.toTypedArray(), opts.indexOf(d.reminder)) { dlg, which ->
                d.reminder = opts[which]
                refresh()
                dlg.dismiss()
            }
            .show()
    }

    private fun pickCalendar() {
        if (!isNew) {
            Toast.makeText(this, "Events can't be moved to another calendar", Toast.LENGTH_SHORT).show()
            return
        }
        val list = writableCalendars()
        if (list.isEmpty()) return
        val names = list.map { if (it.account.isNotBlank() && it.account != it.name) "${it.name}  ·  ${it.account}" else it.name }
        AlertDialog.Builder(this)
            .setTitle("Calendar")
            .setSingleChoiceItems(names.toTypedArray(), list.indexOfFirst { it.id == d.calendarId }) { dlg, which ->
                d.calendarId = list[which].id
                prefs.defaultCalendar = d.calendarId
                refresh()
                dlg.dismiss()
            }
            .show()
    }

    private fun save() {
        d.title = titleEdit.text.toString().trim()
        d.location = locationEdit.text.toString().trim()
        d.notes = notesEdit.text.toString().trim()
        if (d.calendarId < 0) {
            Toast.makeText(this, "There's no calendar to save to", Toast.LENGTH_SHORT).show()
            return
        }
        if (d.allDay) {
            if (d.end.toLocalDate().isBefore(d.start.toLocalDate())) d.end = d.start
        } else if (d.end.isBefore(d.start)) {
            Toast.makeText(this, "The event ends before it starts", Toast.LENGTH_SHORT).show()
            return
        }
        val id = Cal.save(this, d, oldRrule, instanceBegin)
        if (id < 0) {
            Toast.makeText(this, "Couldn't save the event", Toast.LENGTH_SHORT).show()
            return
        }
        setResult(RESULT_OK)
        AgendaWidget.refresh(this)
        finish()
    }

    private fun confirmDelete() {
        if (oldRrule != null && instanceBegin > 0) {
            AlertDialog.Builder(this)
                .setTitle("Delete repeating event")
                .setItems(arrayOf("Only this one", "This and all repeats")) { _, which ->
                    val ok = if (which == 0) Cal.deleteOccurrence(this, d.id, instanceBegin) else Cal.deleteEvent(this, d.id)
                    done(ok)
                }
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Delete this event?")
                .setPositiveButton("Delete") { _, _ -> done(Cal.deleteEvent(this, d.id)) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun done(ok: Boolean) {
        if (!ok) {
            Toast.makeText(this, "Couldn't delete the event", Toast.LENGTH_SHORT).show()
            return
        }
        AgendaWidget.refresh(this)
        finish()
    }
}
