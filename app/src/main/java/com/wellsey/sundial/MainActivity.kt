package com.wellsey.sundial

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
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

/** A tappable row for one event. With no [day], it shows the event's date (for search). */
fun Context.eventRow(ev: Ev, day: LocalDate?, onClick: () -> Unit): View {
    val r = dpf(16f)
    val row = hbox().apply {
        background = rounded(Pal.surface, r)
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
    var sub = if (day != null) Fmt.whenOn(this, ev, day)
    else {
        val d = ev.startDate.format(DateTimeFormatter.ofPattern(
            if (ev.startDate.year == LocalDate.now().year) "EEE d MMM" else "EEE d MMM yyyy"))
        if (ev.allDay) d else "$d · ${Fmt.time(this, ev.begin)}"
    }
    if (ev.location.isNotBlank()) sub += "  ·  " + ev.location
    col.addView(label(sub, 13f, Pal.sub).apply { setPadding(0, dp(4), 0, 0) })
    row.addView(col, lp(0, WRAP, 1f))
    if (ev.recurring) row.addView(Icon(this, Icon.Kind.REPEAT, Pal.faint), lp(dp(16), dp(16)))
    return row
}

class MainActivity : Activity() {
    companion object {
        const val MONTH = 0
        const val WEEK = 1
        const val DAY = 2
        const val LIST = 3
    }

    private lateinit var prefs: Prefs
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var mode = MONTH
    private var month = YearMonth.now()
    private var selected = LocalDate.now()
    private var monthEvents: Map<LocalDate, List<Ev>> = emptyMap()
    private var loadToken = 0
    private var timelineScrolled = false

    private lateinit var titleMain: TextView
    private lateinit var titleSub: TextView
    private lateinit var tabs: Tabs
    private lateinit var permissionPanel: LinearLayout
    private lateinit var monthPage: LinearLayout
    private lateinit var weekRow: LinearLayout
    private lateinit var monthView: MonthView
    private lateinit var dayTitle: TextView
    private lateinit var dayList: LinearLayout
    private lateinit var timelinePage: LinearLayout
    private lateinit var tHeader: TimelineHeader
    private lateinit var tScroll: ScrollView
    private lateinit var tGrid: TimelineGrid
    private lateinit var listPage: ScrollView
    private lateinit var listBox: LinearLayout
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
    private val tick = object : Runnable {
        override fun run() {
            if (mode == WEEK || mode == DAY) tGrid.invalidate()
            ui.postDelayed(this, 60_000)
        }
    }
    private var observing = false
    private var askedBefore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        mode = prefs.mode.coerceIn(0, 3)
        Pal.init(this)
        buildUi()
        handleIntent(intent)
        val wanted = mutableListOf<String>()
        if (!Cal.hasPermission(this)) wanted += listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33 && prefs.notify &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
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
        // The sky follows the hour, and settings may have changed
        if (Backdrop.ensure(this)) window.decorView.invalidateTree()
        monthView.weekStart = prefs.weekStart
        buildWeekRow()
        if (Cal.hasPermission(this) && !observing) {
            contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            observing = true
        }
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 60_000)
        reload()
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
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
        val frame = FrameLayout(this)
        val col = vbox()
        frame.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // Header
        val header = hbox().apply { setPadding(dp(20), dp(12), dp(10), dp(6)) }
        val titles = vbox().apply {
            pressable(dpf(12f))
            setOnClickListener { pickDate() }
        }
        titleMain = label("", 30f, Pal.text, bold = true)
        titleSub = label("", 14f, Pal.sub).apply { setPadding(0, dp(3), 0, 0) }
        titles.addView(titleMain)
        titles.addView(titleSub)
        header.addView(titles, lp(0, WRAP, 1f))
        header.addView(iconButton(Icon.Kind.SEARCH) { startActivity(Intent(this, SearchActivity::class.java)) })
        header.addView(pill("Today", false) { goTo(LocalDate.now()) }.apply { glass(dpf(18f)) },
            lp(WRAP, WRAP).apply { leftMargin = dp(2); rightMargin = dp(2) })
        header.addView(iconButton(Icon.Kind.MORE) { startActivity(Intent(this, SettingsActivity::class.java)) })
        col.addView(header, lp(MATCH, WRAP))

        tabs = Tabs(this, listOf("Month", "Week", "Day", "List"), mode) { setMode(it) }
        col.addView(tabs, lp(MATCH, dp(40)).apply { setMargins(dp(14), dp(4), dp(14), dp(10)) })

        val pages = FrameLayout(this)
        col.addView(pages, lp(MATCH, 0, 1f))

        // Permission message
        permissionPanel = vbox().apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            glass(dpf(28f))
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
        pages.addView(permissionPanel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(dp(14), 0, dp(14), 0) })

        // Month page
        monthPage = vbox()
        val monthCard = vbox().apply { glass(dpf(26f)); setPadding(dp(4), dp(10), dp(4), dp(4)) }
        weekRow = hbox().apply { setPadding(0, 0, 0, dp(6)) }
        monthCard.addView(weekRow, lp(MATCH, WRAP))
        monthView = MonthView(this).apply {
            weekStart = prefs.weekStart
            onSelect = { d -> if (YearMonth.from(d) != this@MainActivity.month) goTo(d) else select(d) }
            onLongPress = { d -> select(d); newEvent(d) }
            onSwipe = { dir -> changePeriod(dir) }
        }
        monthCard.addView(monthView, lp(MATCH, 0, 1f))
        monthPage.addView(monthCard, lp(MATCH, 0, 1.3f).apply { setMargins(dp(10), 0, dp(10), dp(10)) })
        val dayCard = vbox().apply { glass(dpf(26f)); setPadding(dp(14), dp(14), dp(14), 0) }
        dayTitle = label("", 15f, Pal.sub, bold = true).apply { setPadding(dp(4), 0, 0, dp(10)) }
        dayCard.addView(dayTitle)
        val dayScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        dayList = vbox().apply { setPadding(0, 0, 0, dp(80)) }
        dayScroll.addView(dayList, FrameLayout.LayoutParams(MATCH, WRAP))
        dayCard.addView(dayScroll, lp(MATCH, 0, 1f))
        monthPage.addView(dayCard, lp(MATCH, 0, 1f).apply { setMargins(dp(10), 0, dp(10), 0) })
        pages.addView(monthPage, FrameLayout.LayoutParams(MATCH, MATCH))

        // Week / day page
        timelinePage = vbox().apply { glass(dpf(26f)); setPadding(0, dp(4), 0, 0) }
        tHeader = TimelineHeader(this).apply {
            onDayTap = { d -> selected = d; setMode(DAY) }
            onOpen = { open(it) }
        }
        timelinePage.addView(tHeader, lp(MATCH, WRAP))
        timelinePage.addView(View(this).apply { setBackgroundColor(Pal.line) }, lp(MATCH, dp(1)))
        tScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isVerticalFadingEdgeEnabled = true }
        tGrid = TimelineGrid(this).apply {
            onOpen = { open(it) }
            onCreate = { s, e -> newEventAt(s, e) }
            onMove = { ev, b, e -> moveEvent(ev, b, e) }
            onSwipe = { dir -> changePeriod(dir) }
        }
        tScroll.addView(tGrid, FrameLayout.LayoutParams(MATCH, WRAP))
        timelinePage.addView(tScroll, lp(MATCH, 0, 1f))
        pages.addView(timelinePage, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dp(10), 0, dp(10), 0) })

        // List page
        listPage = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        listBox = vbox().apply { setPadding(dp(10), 0, dp(10), dp(96)) }
        listPage.addView(listBox, FrameLayout.LayoutParams(MATCH, WRAP))
        pages.addView(listPage, FrameLayout.LayoutParams(MATCH, MATCH))

        // Add button
        fab = FrameLayout(this).apply {
            background = rounded(Pal.accent, dpf(20f))
            elevation = dpf(8f)
            pressable(dpf(20f))
            setOnClickListener { newEvent(selected) }
            addView(Icon(this@MainActivity, Icon.Kind.PLUS, Color.WHITE), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        frame.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(22), dp(22))
        })

        setSkyContent(frame)
        showPages(animate = false)
        updateTitle()
    }

    private fun buildWeekRow() {
        weekRow.removeAllViews()
        var d = prefs.weekStart
        repeat(7) {
            val weekend = d.value >= 6
            weekRow.addView(label(d.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3).uppercase(), 11f,
                if (weekend) Pal.faint else Pal.sub, bold = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.06f },
                lp(0, WRAP, 1f))
            d = d.plus(1)
        }
    }

    private fun pageFor(m: Int): View = when (m) {
        MONTH -> monthPage
        LIST -> listPage
        else -> timelinePage
    }

    private fun showPages(animate: Boolean, previous: Int = -1) {
        val hasPerm = Cal.hasPermission(this)
        permissionPanel.visibility = if (hasPerm) View.GONE else View.VISIBLE
        fab.visibility = if (hasPerm) View.VISIBLE else View.GONE
        tabs.visibility = if (hasPerm) View.VISIBLE else View.INVISIBLE
        val target = pageFor(mode)
        for (p in listOf(monthPage, timelinePage, listPage)) {
            if (!hasPerm) { p.visibility = View.GONE; continue }
            if (p === target) {
                if (p.visibility != View.VISIBLE || (animate && previous != mode)) {
                    p.visibility = View.VISIBLE
                    if (animate) {
                        p.alpha = 0f
                        p.translationY = dpf(18f)
                        p.animate().alpha(1f).translationY(0f).setDuration(260)
                            .setInterpolator(DecelerateInterpolator(1.8f)).start()
                    }
                }
            } else if (p.visibility == View.VISIBLE) {
                if (animate) {
                    p.animate().alpha(0f).setDuration(110).withEndAction {
                        if (pageFor(mode) !== p) p.visibility = View.GONE
                        p.alpha = 1f
                    }.start()
                } else p.visibility = View.GONE
            }
        }
    }

    private fun setMode(m: Int) {
        val prev = mode
        mode = m
        prefs.mode = m
        tabs.select(m)
        if (m == MONTH) month = YearMonth.from(selected)
        showPages(animate = true, previous = prev)
        updateTitle()
        reload()
    }

    private fun weekDays(): List<LocalDate> {
        val off = (selected.dayOfWeek.value - prefs.weekStart.value + 7) % 7
        val start = selected.minusDays(off.toLong())
        return (0 until 7).map { start.plusDays(it.toLong()) }
    }

    private fun updateTitle() {
        val loc = Locale.getDefault()
        when (mode) {
            MONTH -> {
                titleMain.text = month.month.getDisplayName(TextStyle.FULL, loc)
                titleSub.text = month.year.toString()
            }
            WEEK -> {
                val days = weekDays()
                val a = days.first()
                val b = days.last()
                titleMain.text = if (a.month == b.month) a.month.getDisplayName(TextStyle.FULL, loc)
                else "${a.month.getDisplayName(TextStyle.SHORT, loc)} – ${b.month.getDisplayName(TextStyle.SHORT, loc)}"
                val years = if (a.year == b.year) "${a.year}" else "${a.year}–${b.year}"
                titleSub.text = "Week ${a.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · $years"
            }
            DAY -> {
                titleMain.text = selected.dayOfWeek.getDisplayName(TextStyle.FULL, loc)
                val date = selected.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))
                val today = LocalDate.now()
                titleSub.text = when (selected) {
                    today -> "Today · $date"
                    today.plusDays(1) -> "Tomorrow · $date"
                    today.minusDays(1) -> "Yesterday · $date"
                    else -> date
                }
            }
            else -> {
                titleMain.text = "Upcoming"
                titleSub.text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
            }
        }
    }

    // ---------------------------------------------------------------- navigation

    private fun select(d: LocalDate) {
        selected = d
        monthView.selected = d
        showDay(animate = true)
    }

    private fun goTo(d: LocalDate) {
        if (mode == LIST) {
            selected = d
            setMode(MONTH)
            return
        }
        if (mode == MONTH) {
            val m = YearMonth.from(d)
            if (m != month) {
                val dir = if (m.isAfter(month)) 1 else -1
                slide(monthView, dir) { month = m; selected = d }
            } else select(d)
        } else {
            val dir = if (d.isAfter(selected)) 1 else if (d.isBefore(selected)) -1 else 0
            if (dir == 0) { reload(); return }
            slide(tScroll, dir, extra = tHeader) { selected = d }
        }
    }

    private fun changePeriod(dir: Int) {
        when (mode) {
            MONTH -> slide(monthView, dir) {
                month = month.plusMonths(dir.toLong())
                val today = LocalDate.now()
                selected = if (YearMonth.from(today) == month) today else month.atDay(1)
            }
            WEEK -> slide(tScroll, dir, extra = tHeader) { selected = selected.plusWeeks(dir.toLong()) }
            DAY -> slide(tScroll, dir, extra = tHeader) { selected = selected.plusDays(dir.toLong()) }
        }
    }

    /** Slides [view] out and back in from the other side while the period changes. */
    private fun slide(view: View, dir: Int, extra: View? = null, change: () -> Unit) {
        val shift = view.width * 0.22f * dir
        val targets = listOfNotNull(view, extra)
        for (t in targets) t.animate().cancel()
        extra?.animate()?.translationX(-shift)?.alpha(0f)?.setDuration(110)?.start()
        view.animate().translationX(-shift).alpha(0f).setDuration(110)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                change()
                if (mode == MONTH) {
                    monthView.month = month
                    monthView.selected = selected
                    monthView.events = emptyMap()
                    monthEvents = emptyMap()
                    showDay(animate = false)
                }
                updateTitle()
                reload()
                for (t in targets) {
                    t.translationX = shift
                    t.alpha = 0f
                    t.animate().translationX(0f).alpha(1f).setDuration(220)
                        .setInterpolator(DecelerateInterpolator(1.8f)).start()
                }
            }.start()
    }

    private fun pickDate() {
        val d = selected
        DatePickerDialog(this, { _, y, m, day -> goTo(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth)
            .apply { datePicker.firstDayOfWeek = prefs.weekStart.value % 7 + 1 }
            .show()
    }

    private fun newEvent(d: LocalDate) {
        startActivity(Intent(this, EventActivity::class.java).putExtra("date", d.toEpochDay()))
    }

    private fun newEventAt(start: LocalDateTime, end: LocalDateTime) {
        val z = ZoneId.systemDefault()
        startActivity(
            Intent(this, EventActivity::class.java)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.atZone(z).toInstant().toEpochMilli())
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(z).toInstant().toEpochMilli()),
        )
    }

    private fun open(ev: Ev) {
        startActivity(
            Intent(this, EventActivity::class.java)
                .putExtra("eventId", ev.eventId)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ev.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ev.end),
        )
    }

    private fun moveEvent(ev: Ev, begin: Long, end: Long) {
        val doMove = {
            io.execute {
                val ok = Cal.move(this, ev, begin, end)
                ui.post {
                    if (!ok) Toast.makeText(this, "Couldn't move the event", Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
        }
        if (ev.recurring) {
            AlertDialog.Builder(this)
                .setTitle("Move repeating event")
                .setMessage("This moves every repeat of \"${ev.title}\" by the same amount.")
                .setPositiveButton("Move all") { _, _ -> doMove() }
                .setNegativeButton("Cancel") { _, _ -> reload() }
                .setOnCancelListener { reload() }
                .show()
        } else doMove()
    }

    // ---------------------------------------------------------------- data

    private fun reload() {
        showPages(animate = false)
        if (!Cal.hasPermission(this)) return
        val token = ++loadToken
        val hidden = prefs.hidden
        when (mode) {
            LIST -> {
                val from = LocalDate.now()
                val until = from.plusDays(120)
                io.execute {
                    val map = Cal.byDay(Cal.instances(this, from, until, hidden), from, until)
                    ui.post { if (token == loadToken) showList(map, from, until) }
                }
            }
            MONTH -> {
                val m = month
                monthView.month = m
                val from = monthView.firstCell()
                val until = from.plusDays(42)
                io.execute {
                    val map = Cal.byDay(Cal.instances(this, from, until, hidden), from, until)
                    ui.post {
                        if (token == loadToken && m == month) {
                            val first = monthEvents.isEmpty()
                            monthEvents = map
                            monthView.selected = selected
                            monthView.events = map
                            showDay(animate = first)
                        }
                    }
                }
            }
            else -> {
                val days = if (mode == WEEK) weekDays() else listOf(selected)
                tHeader.days = days
                tGrid.days = days
                val from = days.first()
                val until = days.last().plusDays(1)
                io.execute {
                    val list = Cal.instances(this, from, until, hidden)
                    val map = Cal.byDay(list, from, until)
                    val writable = Cal.calendars(this).filter { it.writable }.map { it.id }.toSet()
                    ui.post {
                        if (token == loadToken) {
                            tHeader.events = list
                            tGrid.writable = writable
                            tGrid.events = map
                            if (!timelineScrolled) {
                                timelineScrolled = true
                                tScroll.post { tScroll.scrollTo(0, tGrid.initialScrollY()) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showDay(animate: Boolean) {
        dayTitle.text = Fmt.dayTitle(selected)
        dayList.removeAllViews()
        val list = monthEvents[selected].orEmpty()
        if (list.isEmpty()) {
            dayList.addView(label("Nothing planned", 15f, Pal.sub).apply { setPadding(dp(4), dp(6), 0, dp(4)) })
            dayList.addView(label("Tap + or long-press a day to add an event", 13f, Pal.faint, lines = 0)
                .apply { setPadding(dp(4), dp(4), 0, 0) })
        } else {
            for (ev in list) dayList.addView(eventRow(ev, selected) { open(ev) })
        }
        if (animate) dayList.staggerIn(this)
    }

    private fun showList(map: Map<LocalDate, List<Ev>>, from: LocalDate, until: LocalDate) {
        val first = listBox.childCount == 0
        listBox.removeAllViews()
        var d = from
        while (d.isBefore(until)) {
            val list = map[d]
            if (!list.isNullOrEmpty()) {
                val day = d
                val card = vbox().apply {
                    glass(dpf(24f))
                    setPadding(dp(12), dp(12), dp(12), dp(4))
                    layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dp(10) }
                }
                card.addView(label(Fmt.dayTitle(day), 14f, if (day == LocalDate.now()) Pal.accent else Pal.sub, bold = true)
                    .apply { setPadding(dp(4), dp(2), 0, dp(10)) })
                for (ev in list) card.addView(eventRow(ev, day) { open(ev) })
                listBox.addView(card)
            }
            d = d.plusDays(1)
        }
        if (listBox.childCount == 0) {
            listBox.addView(vbox().apply {
                glass(dpf(24f))
                setPadding(dp(20), dp(24), dp(20), dp(24))
                addView(label("Nothing coming up in the next four months", 15f, Pal.sub, lines = 0))
            })
        }
        if (first) listBox.staggerIn(this)
    }
}

/** A glass segmented control with a sliding highlight. */
class Tabs(ctx: Context, private val names: List<String>, private var current: Int, private val onPick: (Int) -> Unit) :
    FrameLayout(ctx) {
    private val indicator = View(ctx)
    private val row = ctx.hbox()
    private val labels = ArrayList<TextView>()
    private var lastW = -1

    init {
        glass(ctx.dpf(20f))
        setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        indicator.background = rounded(if (Pal.dark) 0x33FFFFFF else 0xE6FFFFFF.toInt(), ctx.dpf(16f))
        addView(indicator, LayoutParams(0, LayoutParams.MATCH_PARENT))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        for ((i, n) in names.withIndex()) {
            val t = ctx.label(n, 14f, if (i == current) Pal.text else Pal.sub, bold = true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { if (i != current) onPick(i) }
            }
            labels += t
            row.addView(t, lp(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val seg = (w - paddingLeft - paddingRight) / names.size
        if (seg != lastW) {
            lastW = seg
            post {
                indicator.layoutParams = (indicator.layoutParams as LayoutParams).also { it.width = seg }
                indicator.translationX = (current * seg).toFloat()
            }
        }
    }

    fun select(i: Int) {
        current = i
        val seg = (width - paddingLeft - paddingRight) / names.size
        indicator.animate().translationX((i * seg).toFloat()).setDuration(260)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.8f)).start()
        for ((j, t) in labels.withIndex()) t.setTextColor(if (j == i) Pal.text else Pal.sub)
    }
}
