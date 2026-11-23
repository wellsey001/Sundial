package com.wellsey.sundial

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors

object Fmt {
    fun time(ctx: Context, ms: Long): String {
        val t = Cal.localDateTime(ms)
        return t.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"))
    }

    /** What to show for [ev] on the day [day]. */
    fun whenOn(ctx: Context, ev: Ev, day: LocalDate): String {
        if (ev.allDay) {
            return if (ev.startDate == ev.endDate) "All day"
            else "All day · day ${ev.startDate.until(day).days + 1} of ${ev.startDate.until(ev.endDate).days + 1}"
        }
        val starts = ev.startDate == day
        val ends = ev.endDate == day
        return when {
            starts && ends -> if (ev.begin == ev.end) time(ctx, ev.begin) else "${time(ctx, ev.begin)} – ${time(ctx, ev.end)}"
            starts -> "From ${time(ctx, ev.begin)}"
            ends -> "Until ${time(ctx, ev.end)}"
            else -> "All day"
        }
    }

    fun dayTitle(day: LocalDate): String {
        val today = LocalDate.now()
        val base = day.format(DateTimeFormatter.ofPattern("EEEE d MMMM" + if (day.year != today.year) " yyyy" else ""))
        return when (day) {
            today -> "Today · $base"
            today.plusDays(1) -> "Tomorrow · $base"
            today.minusDays(1) -> "Yesterday · $base"
            else -> base
        }
    }
}

/** A tappable card for one event. */
fun Context.eventRow(ev: Ev, day: LocalDate, onClick: () -> Unit): View {
    val r = dpf(14f)
    val row = hbox().apply {
        background = rounded(Pal.surface2, r)
        setPadding(dp(12), dp(11), dp(14), dp(11))
        pressable(r)
        setOnClickListener { onClick() }
        layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dp(8) }
    }
    row.addView(View(this).apply {
        background = rounded(ev.color, dpf(3f))
        layoutParams = lp(dp(5), dp(36)).apply { rightMargin = dp(12) }
    })
    val col = vbox()
    col.addView(label(ev.title, 16f, Pal.text, bold = true))
    var sub = Fmt.whenOn(this, ev, day)
    if (ev.location.isNotBlank()) sub += "  ·  " + ev.location
    col.addView(label(sub, 13f, Pal.sub).apply { setPadding(0, dp(4), 0, 0) })
    row.addView(col, lp(0, WRAP, 1f))
    if (ev.recurring) row.addView(Icon(this, Icon.Kind.REPEAT, Pal.faint), lp(dp(16), dp(16)))
    return row
}

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var month = YearMonth.now()
    private var selected = LocalDate.now()
    private var monthEvents: Map<LocalDate, List<Ev>> = emptyMap()
    private var loadToken = 0

    private lateinit var titleMonth: TextView
    private lateinit var titleYear: TextView
    private lateinit var viewToggle: Icon
    private lateinit var weekRow: LinearLayout
    private lateinit var monthView: MonthView
    private lateinit var dayPanel: LinearLayout
    private lateinit var dayTitle: TextView
    private lateinit var dayList: LinearLayout
    private lateinit var agendaScroll: ScrollView
    private lateinit var agendaList: LinearLayout
    private lateinit var permissionPanel: LinearLayout
    private lateinit var fab: FrameLayout

    private val observer = object : ContentObserver(ui) {
        override fun onChange(selfChange: Boolean) {
            ui.removeCallbacks(widgetRunnable)
            ui.postDelayed(widgetRunnable, 1500)
            ui.removeCallbacks(reloadRunnable)
            ui.postDelayed(reloadRunnable, 300)
        }
    }
    private val reloadRunnable = Runnable { reload() }
    private val widgetRunnable = Runnable { io.execute { AgendaWidget.refresh(this) } }
    private var observing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        styleWindow()
        prefs = Prefs(this)
        buildUi()
        handleIntent(intent)
        val wanted = mutableListOf<String>()
        if (!Cal.hasPermission(this)) wanted += listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        if (android.os.Build.VERSION.SDK_INT >= 33 && prefs.notify &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        ReminderReceiver.ensureChannel(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && data.pathSegments.firstOrNull() == "time") {
            data.lastPathSegment?.toLongOrNull()?.let { goTo(Cal.localDate(it)) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Week start can change in settings
        monthView.weekStart = prefs.weekStart
        buildWeekRow()
        if (Cal.hasPermission(this) && !observing) {
            contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            observing = true
        }
        reload()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (observing) contentResolver.unregisterContentObserver(observer)
        io.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Cal.hasPermission(this)) {
            if (!observing) {
                contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
                observing = true
            }
            Cal.syncNow(this)
            io.execute { AgendaWidget.refresh(this) }
        }
        reload()
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Pal.bg) }
        val col = vbox()
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // Header
        val header = hbox().apply { setPadding(dp(20), dp(14), dp(10), dp(8)) }
        val titles = vbox().apply {
            pressable(dpf(12f))
            setOnClickListener { pickDate() }
        }
        titleMonth = label("", 28f, Pal.text, bold = true)
        titleYear = label("", 14f, Pal.sub).apply { setPadding(0, dp(2), 0, 0) }
        titles.addView(titleMonth)
        titles.addView(titleYear)
        header.addView(titles, lp(0, WRAP, 1f))
        header.addView(pill("Today", false) { goTo(LocalDate.now()) }, lp(WRAP, WRAP).apply { rightMargin = dp(4) })
        viewToggle = iconButton(Icon.Kind.LIST) { setAgenda(!prefs.agenda) }
        header.addView(viewToggle)
        header.addView(iconButton(Icon.Kind.MORE) { startActivity(Intent(this, SettingsActivity::class.java)) })
        col.addView(header, lp(MATCH, WRAP))

        // Permission message
        permissionPanel = vbox().apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            addView(Icon(this@MainActivity, Icon.Kind.CALENDAR, Pal.accent), lp(dp(56), dp(56)))
            addView(label("Sundial needs access to your calendars", 18f, Pal.text, bold = true, lines = 0).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(8))
            })
            addView(label(
                "It shows and edits the calendars already on your phone, so your Google and other accounts stay in sync.",
                14f, Pal.sub, lines = 0,
            ).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(20)) })
            addView(pill("Allow access", true) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR) || !askedBefore) {
                    askedBefore = true
                    requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1)
                } else {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null)))
                }
            })
        }
        col.addView(permissionPanel, lp(MATCH, 0, 1f))

        // Month
        weekRow = hbox().apply { setPadding(0, dp(4), 0, dp(6)) }
        col.addView(weekRow, lp(MATCH, WRAP))
        monthView = MonthView(this).apply {
            weekStart = prefs.weekStart
            onSelect = { d -> if (YearMonth.from(d) != this@MainActivity.month) goTo(d) else select(d) }
            onLongPress = { d -> select(d); newEvent(d) }
            onSwipe = { dir -> changeMonth(dir) }
        }
        col.addView(monthView, lp(MATCH, 0, 1.25f))

        // Selected day
        dayPanel = vbox().apply {
            background = rounded(Pal.surface, dpf(24f))
            setPadding(dp(16), dp(16), dp(16), 0)
            elevation = dpf(2f)
        }
        dayTitle = label("", 15f, Pal.sub, bold = true).apply { setPadding(dp(4), 0, 0, dp(10)) }
        dayPanel.addView(dayTitle)
        val dayScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        dayList = vbox().apply { setPadding(0, 0, 0, dp(88)) }
        dayScroll.addView(dayList, FrameLayout.LayoutParams(MATCH, WRAP))
        dayPanel.addView(dayScroll, lp(MATCH, 0, 1f))
        col.addView(dayPanel, lp(MATCH, 0, 1f).apply { topMargin = dp(6); leftMargin = dp(8); rightMargin = dp(8) })

        // Agenda
        agendaScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; visibility = View.GONE }
        agendaList = vbox().apply { setPadding(dp(16), dp(4), dp(16), dp(96)) }
        agendaScroll.addView(agendaList, FrameLayout.LayoutParams(MATCH, WRAP))
        col.addView(agendaScroll, lp(MATCH, 0, 1f))

        // Add button
        fab = FrameLayout(this).apply {
            background = rounded(Pal.accent, dpf(18f))
            elevation = dpf(6f)
            pressable(dpf(18f))
            setOnClickListener { newEvent(if (prefs.agenda) LocalDate.now() else selected) }
            addView(Icon(this@MainActivity, Icon.Kind.PLUS, Color.WHITE), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        root.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(22), dp(22))
        })

        setContentView(root)
        applyMode()
        updateTitle()
    }

    private var askedBefore = false

    private fun buildWeekRow() {
        weekRow.removeAllViews()
        var d = prefs.weekStart
        repeat(7) {
            val weekend = d.value >= 6
            weekRow.addView(label(d.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3), 12f,
                if (weekend) Pal.faint else Pal.sub, bold = true).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
            d = d.plus(1)
        }
    }

    private fun applyMode() {
        val hasPerm = Cal.hasPermission(this)
        val agenda = prefs.agenda
        permissionPanel.visibility = if (hasPerm) View.GONE else View.VISIBLE
        val showMonth = hasPerm && !agenda
        weekRow.visibility = if (showMonth) View.VISIBLE else View.GONE
        monthView.visibility = if (showMonth) View.VISIBLE else View.GONE
        dayPanel.visibility = if (showMonth) View.VISIBLE else View.GONE
        agendaScroll.visibility = if (hasPerm && agenda) View.VISIBLE else View.GONE
        fab.visibility = if (hasPerm) View.VISIBLE else View.GONE
        viewToggle.kind = if (agenda) Icon.Kind.GRID else Icon.Kind.LIST
        viewToggle.invalidate()
    }

    private fun setAgenda(on: Boolean) {
        prefs.agenda = on
        applyMode()
        updateTitle()
        reload()
    }

    private fun updateTitle() {
        if (prefs.agenda) {
            titleMonth.text = "Upcoming"
            titleYear.text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
        } else {
            titleMonth.text = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
            titleYear.text = month.year.toString()
        }
    }

    // ---------------------------------------------------------------- navigation

    private fun select(d: LocalDate) {
        selected = d
        monthView.selected = d
        showDay()
    }

    private fun goTo(d: LocalDate) {
        if (prefs.agenda) setAgenda(false)
        val m = YearMonth.from(d)
        if (m != month) {
            val dir = if (m.isAfter(month)) 1 else -1
            animateMonth(dir) { month = m; selected = d }
        } else select(d)
    }

    private fun changeMonth(dir: Int) {
        animateMonth(dir) {
            month = month.plusMonths(dir.toLong())
            val today = LocalDate.now()
            selected = if (YearMonth.from(today) == month) today else month.atDay(1)
        }
    }

    private fun animateMonth(dir: Int, change: () -> Unit) {
        val shift = monthView.width * 0.25f * dir
        monthView.animate().cancel()
        monthView.animate().translationX(-shift).alpha(0f).setDuration(110).withEndAction {
            change()
            monthView.month = month
            monthView.selected = selected
            monthView.events = emptyMap()
            updateTitle()
            showDay()
            reload()
            monthView.translationX = shift
            monthView.animate().translationX(0f).alpha(1f).setDuration(170).start()
        }.start()
    }

    private fun pickDate() {
        val d = if (prefs.agenda) LocalDate.now() else selected
        DatePickerDialog(this, { _, y, m, day -> goTo(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth)
            .apply { datePicker.firstDayOfWeek = prefs.weekStart.value % 7 + 1 }
            .show()
    }

    private fun newEvent(d: LocalDate) {
        startActivity(Intent(this, EventActivity::class.java).putExtra("date", d.toEpochDay()))
    }

    private fun open(ev: Ev) {
        startActivity(
            Intent(this, EventActivity::class.java)
                .putExtra("eventId", ev.eventId)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ev.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ev.end),
        )
    }

    // ---------------------------------------------------------------- data

    private fun reload() {
        applyMode()
        if (!Cal.hasPermission(this)) return
        val token = ++loadToken
        val hidden = prefs.hidden
        if (prefs.agenda) {
            val from = LocalDate.now()
            val until = from.plusDays(120)
            io.execute {
                val map = Cal.byDay(Cal.instances(this, from, until, hidden), from, until)
                ui.post { if (token == loadToken) showAgenda(map, from, until) }
            }
        } else {
            val m = month
            val from = monthView.firstCell()
            val until = from.plusDays(42)
            io.execute {
                val map = Cal.byDay(Cal.instances(this, from, until, hidden), from, until)
                ui.post {
                    if (token == loadToken && m == month) {
                        monthEvents = map
                        monthView.month = month
                        monthView.selected = selected
                        monthView.events = map
                        showDay()
                    }
                }
            }
        }
    }

    private fun showDay() {
        dayTitle.text = Fmt.dayTitle(selected)
        dayList.removeAllViews()
        val list = monthEvents[selected].orEmpty()
        if (list.isEmpty()) {
            dayList.addView(label("Nothing planned", 15f, Pal.faint).apply { setPadding(dp(4), dp(6), 0, dp(4)) })
            dayList.addView(label("Tap + or long-press a day to add an event", 13f, Pal.faint, lines = 0)
                .apply { setPadding(dp(4), dp(4), 0, 0) })
            return
        }
        for (ev in list) dayList.addView(eventRow(ev, selected) { open(ev) })
    }

    private fun showAgenda(map: Map<LocalDate, List<Ev>>, from: LocalDate, until: LocalDate) {
        agendaList.removeAllViews()
        var d = from
        var any = false
        while (d.isBefore(until)) {
            val list = map[d]
            if (!list.isNullOrEmpty()) {
                any = true
                val day = d
                agendaList.addView(label(Fmt.dayTitle(day), 14f, if (day == LocalDate.now()) Pal.accent else Pal.sub, bold = true)
                    .apply { setPadding(dp(4), dp(14), 0, dp(8)) })
                for (ev in list) agendaList.addView(eventRow(ev, day) { open(ev) })
            }
            d = d.plusDays(1)
        }
        if (!any) {
            agendaList.addView(label("Nothing coming up in the next four months", 15f, Pal.faint, lines = 0)
                .apply { setPadding(dp(4), dp(20), 0, 0) })
        }
    }
}
