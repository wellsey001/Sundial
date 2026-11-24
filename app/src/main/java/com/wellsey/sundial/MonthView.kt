package com.wellsey.sundial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/** The month grid: six weeks of days, each with coloured event pills. */
class MonthView(ctx: Context) : View(ctx) {
    var month: YearMonth = YearMonth.now()
        set(v) { field = v; invalidate() }
    var weekStart: DayOfWeek = DayOfWeek.MONDAY
        set(v) { field = v; invalidate() }
    var selected: LocalDate = LocalDate.now()
        set(v) { field = v; invalidate() }
    var events: Map<LocalDate, List<Ev>> = emptyMap()
        set(v) { field = v; invalidate() }

    var onSelect: ((LocalDate) -> Unit)? = null
    var onLongPress: ((LocalDate) -> Unit)? = null
    /** +1 for next month, -1 for previous. */
    var onSwipe: ((Int) -> Unit)? = null

    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = ctx.sp(13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.sp(10f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint().apply { strokeWidth = ctx.dpf(1f) }
    private val rect = RectF()

    fun firstCell(): LocalDate {
        val first = month.atDay(1)
        val off = (first.dayOfWeek.value - weekStart.value + 7) % 7
        return first.minusDays(off.toLong())
    }

    private fun dateAt(x: Float, y: Float): LocalDate? {
        if (width == 0 || height == 0) return null
        val c = (x / (width / 7f)).toInt().coerceIn(0, 6)
        val r = (y / (height / 6f)).toInt().coerceIn(0, 5)
        return firstCell().plusDays((r * 7 + c).toLong())
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            dateAt(e.x, e.y)?.let { onSelect?.invoke(it) }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            dateAt(e.x, e.y)?.let {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongPress?.invoke(it)
            }
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) > abs(vy) && abs(vx) > 600) {
                onSwipe?.invoke(if (vx < 0) 1 else -1)
                return true
            }
            if (abs(vy) > abs(vx) && abs(vy) > 900) {
                onSwipe?.invoke(if (vy < 0) 1 else -1)
                return true
            }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val ctx = context
        val cw = width / 7f
        val ch = height / 6f
        val today = LocalDate.now()
        val start = firstCell()
        val numTop = ctx.dpf(6f)
        val circleR = ctx.dpf(13f)
        val pillTop = numTop + circleR * 2 + ctx.dpf(3f)
        val pillH = ctx.dpf(15f)
        val pillGap = ctx.dpf(2f)
        val pad = ctx.dpf(2f)

        linePaint.color = Pal.line
        for (r in 1..5) canvas.drawLine(0f, r * ch, width.toFloat(), r * ch, linePaint)

        for (r in 0 until 6) for (c in 0 until 7) {
            val date = start.plusDays((r * 7 + c).toLong())
            val x = c * cw
            val y = r * ch
            val inMonth = date.month == month.month && date.year == month.year

            if (date == selected) {
                fill.color = Pal.accentSoft
                rect.set(x + pad, y + pad, x + cw - pad, y + ch - pad)
                canvas.drawRoundRect(rect, ctx.dpf(10f), ctx.dpf(10f), fill)
            }

            val cx = x + cw / 2
            val cy = y + numTop + circleR
            if (date == today) {
                fill.color = Pal.accent
                canvas.drawCircle(cx, cy, circleR, fill)
                numPaint.color = Color.WHITE
            } else {
                numPaint.color = if (inMonth) Pal.text else Pal.faint
            }
            canvas.drawText(date.dayOfMonth.toString(), cx, cy - (numPaint.descent() + numPaint.ascent()) / 2, numPaint)

            val list = events[date] ?: continue
            val room = ((ch - pillTop - pad) / (pillH + pillGap)).toInt().coerceAtLeast(1)
            // When there isn't room for every event, the last line shares its space with a "+n" count
            val shown = if (list.size > room) room else list.size
            val hidden = list.size - shown
            pillText.alpha = 255
            val moreText = if (hidden > 0) "+$hidden" else ""
            val moreW = if (hidden > 0) pillText.measureText(moreText) + ctx.dpf(5f) else 0f
            for (i in 0 until shown) {
                val ev = list[i]
                val top = y + pillTop + i * (pillH + pillGap)
                val lastWithMore = hidden > 0 && i == shown - 1
                rect.set(x + pad, top, x + cw - pad - if (lastWithMore) moreW else 0f, top + pillH)
                val multi = ev.allDay || ev.startDate != ev.endDate
                fill.color = if (multi) ev.color else (ev.color and 0x00FFFFFF) or (if (Pal.dark) 0x55000000 else 0x33000000)
                if (!inMonth) fill.alpha = fill.alpha / 2
                canvas.drawRoundRect(rect, ctx.dpf(4f), ctx.dpf(4f), fill)
                pillText.color = if (multi) Color.WHITE else Pal.text
                if (!inMonth) pillText.alpha = 140
                val avail = rect.width() - ctx.dpf(6f)
                val n = pillText.breakText(ev.title, true, maxOf(0f, avail), null)
                canvas.drawText(
                    ev.title, 0, n, rect.left + ctx.dpf(3f),
                    rect.centerY() - (pillText.descent() + pillText.ascent()) / 2, pillText,
                )
                if (lastWithMore) {
                    pillText.color = Pal.sub
                    pillText.alpha = 255
                    canvas.drawText(
                        moreText, rect.right + ctx.dpf(3f),
                        rect.centerY() - (pillText.descent() + pillText.ascent()) / 2, pillText,
                    )
                }
            }
        }
    }
}
