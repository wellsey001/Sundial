package com.wellsey.sundial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private fun Context.gutterW() = dpf(50f)

private fun dayStartMs(d: LocalDate) = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Is this event shown in the all-day strip rather than on the hour grid? */
fun Ev.inStrip() = allDay || startDate != endDate

/** Day names and dates across the top of week/day view, with all-day and multi-day events below. */
class TimelineHeader(ctx: Context) : View(ctx) {
    var days: List<LocalDate> = listOf(LocalDate.now())
        set(v) { field = v; relayout() }
    var events: List<Ev> = emptyList()
        set(v) { field = v; relayout() }
    var onDayTap: ((LocalDate) -> Unit)? = null
    var onOpen: ((Ev) -> Unit)? = null

    private val dateRowH = ctx.dpf(56f)
    private val barH = ctx.dpf(20f)
    private val gap = ctx.dpf(3f)
    private val maxRows = 3
    private class Bar(val ev: Ev, val from: Int, val to: Int, var row: Int = 0, val rect: RectF = RectF())
    private var bars: List<Bar> = emptyList()
    private var rows = 0
    private var hiddenCount = IntArray(0)

    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.sp(11f); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.sp(19f); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val barText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.sp(11f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun relayout() {
        val first = days.firstOrNull() ?: return
        val last = days.last()
        val list = events.filter { it.inStrip() && !it.endDate.isBefore(first) && !it.startDate.isAfter(last) }
            .sortedWith(compareBy<Ev>({ it.startDate }, { -it.startDate.until(it.endDate).days }, { it.title }))
        val rowEnds = ArrayList<Int>()
        hiddenCount = IntArray(days.size)
        val out = ArrayList<Bar>()
        for (ev in list) {
            val from = max(0, first.until(ev.startDate).days)
            val to = min(days.size - 1, first.until(ev.endDate).days)
            var row = rowEnds.indexOfFirst { it < from }
            if (row < 0) { row = rowEnds.size; rowEnds.add(to) } else rowEnds[row] = to
            if (row >= maxRows) {
                for (i in from..to) hiddenCount[i]++
                continue
            }
            out += Bar(ev, from, to, row)
        }
        bars = out
        rows = min(rowEnds.size, maxRows) + if (hiddenCount.any { it > 0 }) 1 else 0
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = dateRowH + rows * (barH + gap) + if (rows > 0) context.dpf(6f) else 0f
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onDraw(c: Canvas) {
        val ctx = context
        val gutter = ctx.gutterW()
        val colW = (width - gutter) / days.size
        val today = LocalDate.now()

        // Week number in the corner
        small.color = Pal.faint
        small.textAlign = Paint.Align.CENTER
        days.getOrNull(min(3, days.size - 1))?.let {
            c.drawText("W${it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}", gutter / 2, dateRowH / 2 + ctx.dpf(4f), small)
        }

        for ((i, d) in days.withIndex()) {
            val cx = gutter + colW * i + colW / 2
            val isToday = d == today
            small.color = if (isToday) Pal.accent else Pal.sub
            c.drawText(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(), cx, ctx.dpf(18f), small)
            val cy = ctx.dpf(38f)
            if (isToday) {
                fill.color = Pal.accent
                c.drawCircle(cx, cy, ctx.dpf(15f), fill)
            }
            big.color = if (isToday) Color.WHITE else Pal.text
            c.drawText(d.dayOfMonth.toString(), cx, cy - (big.descent() + big.ascent()) / 2, big)
        }

        val pad = ctx.dpf(2f)
        for (b in bars) {
            val top = dateRowH + b.row * (barH + gap)
            b.rect.set(gutter + colW * b.from + pad, top, gutter + colW * (b.to + 1) - pad, top + barH)
            fill.color = b.ev.color
            c.drawRoundRect(b.rect, ctx.dpf(5f), ctx.dpf(5f), fill)
            barText.color = Color.WHITE
            val avail = b.rect.width() - ctx.dpf(10f)
            val n = barText.breakText(b.ev.title, true, max(0f, avail), null)
            c.drawText(b.ev.title.substring(0, n), b.rect.left + ctx.dpf(5f),
                b.rect.centerY() - (barText.descent() + barText.ascent()) / 2, barText)
        }
        if (hiddenCount.any { it > 0 }) {
            val top = dateRowH + maxRows * (barH + gap)
            small.color = Pal.sub
            for ((i, n) in hiddenCount.withIndex()) if (n > 0) {
                c.drawText("+$n", gutter + colW * i + colW / 2, top + barH / 2 + ctx.dpf(4f), small)
            }
        }
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            bars.lastOrNull { it.rect.contains(e.x, e.y) }?.let { onOpen?.invoke(it.ev); return true }
            val gutter = context.gutterW()
            if (e.x < gutter || days.isEmpty()) return false
            val i = ((e.x - gutter) / ((width - gutter) / days.size)).toInt().coerceIn(0, days.size - 1)
            // Tapping the "+n" row or a date both open that day
            onDayTap?.invoke(days[i])
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event) || super.onTouchEvent(event)
}

/** The hour grid for one or more days, with drag to move and drag to create. */
class TimelineGrid(ctx: Context) : View(ctx) {
    var days: List<LocalDate> = listOf(LocalDate.now())
        set(v) { field = v; relayout() }
    var events: Map<LocalDate, List<Ev>> = emptyMap()
        set(v) { field = v; relayout() }
    var writable: Set<Long> = emptySet()

    var onOpen: ((Ev) -> Unit)? = null
    var onCreate: ((LocalDateTime, LocalDateTime) -> Unit)? = null
    var onMove: ((Ev, Long, Long) -> Unit)? = null
    var onSwipe: ((Int) -> Unit)? = null

    val hourH = ctx.dpf(58f)
    private val topPad = ctx.dpf(10f)
    private val minH = ctx.dpf(22f)

    private class Block(val ev: Ev, val day: Int, val start: Int, val end: Int, val visEnd: Int) {
        var col = 0
        var cols = 1
        val rect = RectF()
    }
    private var blocks: List<Block> = emptyList()

    // Dragging
    private var drag: Block? = null
    private var grab = 0
    private var dragDay = 0
    private var dragStart = 0
    private var creating = false
    private var createDay = 0
    private var createStart = 0
    private var createEnd = 0
    private var fingerX = 0f
    private var fingerParentY = 0f

    private val linePaint = Paint().apply { strokeWidth = ctx.dpf(1f) }
    private val hourText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.sp(10.5f); textAlign = Paint.Align.RIGHT }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.sp(12f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.sp(11f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2f) }
    private val tmp = RectF()

    private fun timeFmt() = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a")
    private fun hourLabel(h: Int): String = if (DateFormat.is24HourFormat(context)) String.format("%02d:00", h)
    else LocalTime.of(h, 0).format(DateTimeFormatter.ofPattern("h a"))

    fun yFor(minutes: Int) = topPad + minutes / 60f * hourH
    private fun minutesAt(y: Float) = ((y - topPad) / hourH * 60f).toInt()
    private fun colW() = (width - context.gutterW()) / days.size.coerceAtLeast(1)
    private fun dayAt(x: Float) = ((x - context.gutterW()) / colW()).toInt().coerceIn(0, days.size - 1)
    private fun snap(m: Int, step: Int = 15) = ((m + step / 2) / step) * step

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (topPad * 2 + hourH * 24).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        place()
    }

    private fun relayout() {
        val out = ArrayList<Block>()
        val minMinutes = (minH / hourH * 60).toInt()
        for ((i, d) in days.withIndex()) {
            val dayStart = dayStartMs(d)
            val list = events[d].orEmpty().filter { !it.inStrip() }
                .map { ev ->
                    val s = ((max(ev.begin, dayStart) - dayStart) / 60_000).toInt().coerceIn(0, 1440)
                    val e = ((min(ev.end, dayStart + 86_400_000) - dayStart) / 60_000).toInt().coerceIn(s, 1440)
                    Block(ev, i, s, e, max(e, s + minMinutes))
                }
                .sortedWith(compareBy<Block>({ it.start }, { -it.end }))
            // Side-by-side columns for overlapping events
            val cluster = ArrayList<Block>()
            val colEnds = ArrayList<Int>()
            var clusterEnd = -1
            fun finish() {
                for (b in cluster) b.cols = colEnds.size
                cluster.clear(); colEnds.clear(); clusterEnd = -1
            }
            for (b in list) {
                if (cluster.isNotEmpty() && b.start >= clusterEnd) finish()
                var col = colEnds.indexOfFirst { it <= b.start }
                if (col < 0) { col = colEnds.size; colEnds.add(b.visEnd) } else colEnds[col] = b.visEnd
                b.col = col
                cluster += b
                clusterEnd = max(clusterEnd, b.visEnd)
            }
            finish()
            out += list
        }
        blocks = out
        place()
        invalidate()
    }

    private fun place() {
        if (width == 0) return
        val cw = colW()
        val gutter = context.gutterW()
        val pad = context.dpf(1.5f)
        for (b in blocks) {
            val w = cw / b.cols
            val left = gutter + b.day * cw + b.col * w
            b.rect.set(left + pad, yFor(b.start) + pad, left + w - pad, yFor(b.visEnd) - pad)
        }
    }

    /** Where to scroll to first: an hour before now if today is shown, else 8am. */
    fun initialScrollY(): Int {
        val now = LocalTime.now()
        val m = if (LocalDate.now() in days) max(0, now.hour * 60 + now.minute - 60) else 8 * 60
        return yFor(m).toInt()
    }

    override fun onDraw(c: Canvas) {
        val ctx = context
        val gutter = ctx.gutterW()
        val cw = colW()
        val today = LocalDate.now()

        // Today's column
        val ti = days.indexOf(today)
        if (ti >= 0 && days.size > 1) {
            fill.color = if (Pal.dark) 0x0FFFFFFF else 0x4DFFFFFF
            c.drawRect(gutter + ti * cw, 0f, gutter + (ti + 1) * cw, height.toFloat(), fill)
        }

        linePaint.color = Pal.line
        hourText.color = Pal.faint
        for (h in 0..24) {
            val y = yFor(h * 60)
            c.drawLine(gutter, y, width.toFloat(), y, linePaint)
            if (h in 1..23) c.drawText(hourLabel(h), gutter - ctx.dpf(8f), y + hourText.textSize / 3, hourText)
        }
        for (i in 1 until days.size) {
            val x = gutter + i * cw
            c.drawLine(x, topPad, x, yFor(1440), linePaint)
        }

        val r = ctx.dpf(7f)
        for (b in blocks) {
            val dragged = drag === b
            fill.color = b.ev.color
            fill.alpha = if (dragged) 70 else 235
            c.drawRoundRect(b.rect, r, r, fill)
            if (!dragged) drawLabel(c, b.ev.title, b.ev, b.rect, b.start, b.end)
        }

        // Now line
        if (ti >= 0) {
            val now = LocalTime.now()
            val y = yFor(now.hour * 60 + now.minute)
            fill.color = Pal.accent
            fill.alpha = 255
            c.drawRect(gutter + ti * cw, y - ctx.dpf(1f), gutter + (ti + 1) * cw, y + ctx.dpf(1f), fill)
            c.drawCircle(gutter + ti * cw, y, ctx.dpf(4.5f), fill)
        }

        // Drag ghosts
        drag?.let { b ->
            val len = b.end - b.start
            val left = gutter + dragDay * cw + ctx.dpf(1.5f)
            tmp.set(left, yFor(dragStart), left + cw - ctx.dpf(3f), yFor(dragStart + max(len, (minH / hourH * 60).toInt())))
            fill.color = b.ev.color; fill.alpha = 255
            c.drawRoundRect(tmp, r, r, fill)
            stroke.color = Color.WHITE
            c.drawRoundRect(tmp, r, r, stroke)
            drawLabel(c, b.ev.title, b.ev, tmp, dragStart, dragStart + len)
        }
        if (creating) {
            val left = gutter + createDay * cw + ctx.dpf(1.5f)
            tmp.set(left, yFor(createStart), left + cw - ctx.dpf(3f), yFor(createEnd))
            fill.color = Pal.accent; fill.alpha = 90
            c.drawRoundRect(tmp, r, r, fill)
            stroke.color = Pal.accent
            c.drawRoundRect(tmp, r, r, stroke)
            titlePaint.color = Pal.text
            subPaint.color = Pal.text
            c.drawText("New event", tmp.left + ctx.dpf(6f), tmp.top + ctx.dpf(15f), titlePaint)
            if (tmp.height() > ctx.dpf(34f)) {
                c.drawText(timeRange(createStart, createEnd), tmp.left + ctx.dpf(6f), tmp.top + ctx.dpf(30f), subPaint)
            }
        }
    }

    private fun timeRange(s: Int, e: Int): String {
        val f = timeFmt()
        return LocalTime.of(s / 60 % 24, s % 60).format(f) + " – " + LocalTime.of(e / 60 % 24, e % 60).format(f)
    }

    private fun drawLabel(c: Canvas, title: String, ev: Ev, rect: RectF, start: Int, end: Int) {
        val ctx = context
        val padX = ctx.dpf(5f)
        val avail = rect.width() - padX * 2
        if (avail <= 0) return
        titlePaint.color = Color.WHITE
        subPaint.color = 0xE6FFFFFF.toInt()
        c.save()
        c.clipRect(rect)
        var y = rect.top + ctx.dpf(4f) - titlePaint.ascent()
        // Title, wrapping onto a second line when the block is tall enough
        var rest = title
        var lines = 0
        val maxLines = ((rect.height() - ctx.dpf(4f)) / (titlePaint.textSize * 1.2f)).toInt().coerceIn(1, 3)
        while (rest.isNotEmpty() && lines < maxLines) {
            val n = titlePaint.breakText(rest, true, avail, null).coerceAtLeast(1)
            c.drawText(rest, 0, min(n, rest.length), rect.left + padX, y, titlePaint)
            rest = rest.substring(min(n, rest.length)).trimStart()
            y += titlePaint.textSize * 1.2f
            lines++
        }
        if (y + subPaint.textSize * 0.2f < rect.bottom) {
            val sub = if (ev.location.isNotBlank() && rect.height() > ctx.dpf(60f)) timeRange(start, end) + " · " + ev.location
            else timeRange(start, end)
            val n = subPaint.breakText(sub, true, avail, null)
            c.drawText(sub, 0, n, rect.left + padX, y, subPaint)
        }
        c.restore()
    }

    // ---------------------------------------------------------------- touch

    private val scrollParent get() = parent as? ScrollView

    private val autoScroll = object : Runnable {
        override fun run() {
            val sp = scrollParent ?: return
            if (drag == null && !creating) return
            val edge = context.dpf(56f)
            val step = when {
                fingerParentY < edge -> -context.dp(14)
                fingerParentY > sp.height - edge -> context.dp(14)
                else -> 0
            }
            if (step != 0) {
                sp.scrollBy(0, step)
                updateDrag(fingerX, fingerParentY + sp.scrollY)
            }
            postOnAnimation(this)
        }
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val hit = blocks.lastOrNull { it.rect.contains(e.x, e.y) }
            if (hit != null) { onOpen?.invoke(hit.ev); return true }
            if (e.x < context.gutterW() || days.isEmpty()) return false
            val day = days[dayAt(e.x)]
            val m = (minutesAt(e.y) / 30 * 30).coerceIn(0, 1410)
            val start = day.atStartOfDay().plusMinutes(m.toLong())
            onCreate?.invoke(start, start.plusHours(1))
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (days.isEmpty() || e.x < context.gutterW()) return
            val hit = blocks.lastOrNull { it.rect.contains(e.x, e.y) }
            if (hit != null) {
                if (hit.ev.calendarId !in writable) return
                drag = hit
                grab = minutesAt(e.y) - hit.start
                dragDay = hit.day
                dragStart = hit.start
            } else {
                creating = true
                createDay = dayAt(e.x)
                createStart = snap(minutesAt(e.y)).coerceIn(0, 1425)
                createEnd = min(1440, createStart + 60)
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            parent?.requestDisallowInterceptTouchEvent(true)
            fingerX = e.x
            fingerParentY = e.y - (scrollParent?.scrollY ?: 0)
            postOnAnimation(autoScroll)
            invalidate()
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (drag != null || creating) return false
            if (abs(vx) > abs(vy) * 1.5f && abs(vx) > 700) {
                onSwipe?.invoke(if (vx < 0) 1 else -1)
                return true
            }
            return false
        }
    })

    private fun updateDrag(x: Float, y: Float) {
        drag?.let { b ->
            val len = b.end - b.start
            dragDay = dayAt(x)
            dragStart = snap(minutesAt(y) - grab).coerceIn(0, max(0, 1440 - len))
            invalidate()
        }
        if (creating) {
            val m = snap(minutesAt(y))
            createEnd = m.coerceIn(createStart + 15, 1440)
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        if (drag != null || creating) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    fingerX = event.x
                    fingerParentY = event.y - (scrollParent?.scrollY ?: 0)
                    updateDrag(event.x, event.y)
                }
                MotionEvent.ACTION_UP -> finishDrag(commit = true)
                MotionEvent.ACTION_CANCEL -> finishDrag(commit = false)
            }
            return true
        }
        return handled || super.onTouchEvent(event)
    }

    private fun finishDrag(commit: Boolean) {
        removeCallbacks(autoScroll)
        val b = drag
        if (commit && b != null && (dragDay != b.day || dragStart != b.start)) {
            val newBegin = dayStartMs(days[dragDay]) + dragStart * 60_000L
            onMove?.invoke(b.ev, newBegin, newBegin + (b.ev.end - b.ev.begin))
        }
        if (commit && creating) {
            val d = days[createDay].atStartOfDay()
            onCreate?.invoke(d.plusMinutes(createStart.toLong()), d.plusMinutes(createEnd.toLong()))
        }
        drag = null
        creating = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }
}
