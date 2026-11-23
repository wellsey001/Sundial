package com.wellsey.sundial

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Colours for light and dark mode. */
object Pal {
    var dark = false
        private set

    fun init(ctx: Context) {
        dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    val bg get() = if (dark) 0xFF0F1115.toInt() else 0xFFF7F8FA.toInt()
    val surface get() = if (dark) 0xFF1A1D23.toInt() else 0xFFFFFFFF.toInt()
    val surface2 get() = if (dark) 0xFF242832.toInt() else 0xFFEEF1F6.toInt()
    val text get() = if (dark) 0xFFECEFF4.toInt() else 0xFF15181E.toInt()
    val sub get() = if (dark) 0xFF9AA3B2.toInt() else 0xFF5F6878.toInt()
    val faint get() = if (dark) 0xFF4C5262.toInt() else 0xFFB4BBC7.toInt()
    val line get() = if (dark) 0x1FFFFFFF else 0x14000000
    val accent get() = if (dark) 0xFF5B8FFF.toInt() else 0xFF3D7BFF.toInt()
    val accentSoft get() = if (dark) 0x335B8FFF else 0x223D7BFF
    val danger get() = if (dark) 0xFFFF6B6B.toInt() else 0xFFD93636.toInt()
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.sp(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

fun rounded(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

/** Adds a touch ripple clipped to a rounded shape. */
fun View.pressable(radius: Float) {
    val mask = rounded(Color.WHITE, radius)
    foreground = RippleDrawable(ColorStateList.valueOf(if (Pal.dark) 0x33FFFFFF else 0x22000000), null, mask)
    isClickable = true
    isFocusable = true
}

fun Context.label(
    s: CharSequence,
    size: Float,
    color: Int = Pal.text,
    bold: Boolean = false,
    lines: Int = 1,
): TextView = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
    includeFontPadding = false
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.hbox(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    this.gravity = gravity
}

fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.spacer(weight: Float = 1f) = View(this).apply { layoutParams = lp(0, 1, weight) }

/** Pill-shaped text button. */
fun Context.pill(s: String, filled: Boolean, onClick: () -> Unit): TextView =
    label(s, 14f, if (filled) Color.WHITE else Pal.text, bold = true).apply {
        val r = dpf(18f)
        background = if (filled) rounded(Pal.accent, r) else rounded(Pal.surface2, r)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        gravity = Gravity.CENTER
        pressable(r)
        setOnClickListener { onClick() }
    }

/** Uses the theme's light or dark bars. */
fun Activity.styleWindow() {
    Pal.init(this)
    window.statusBarColor = Pal.bg
    window.navigationBarColor = Pal.bg
}

/** Small line icons, drawn on a 24-unit grid. */
class Icon(ctx: Context, var kind: Kind, var color: Int = Pal.text) : View(ctx) {
    enum class Kind { PLUS, CLOSE, MORE, GRID, LIST, BACK, CLOCK, PIN, NOTES, BELL, REPEAT, CALENDAR }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        val s = minOf(w, h) / 24f
        canvas.save()
        canvas.translate(paddingLeft + (w - 24 * s) / 2f, paddingTop + (h - 24 * s) / 2f)
        canvas.scale(s, s)
        paint.color = color
        paint.strokeWidth = 2f
        paint.style = Paint.Style.STROKE
        when (kind) {
            Kind.PLUS -> {
                canvas.drawLine(12f, 5f, 12f, 19f, paint); canvas.drawLine(5f, 12f, 19f, 12f, paint)
            }
            Kind.CLOSE -> {
                canvas.drawLine(6f, 6f, 18f, 18f, paint); canvas.drawLine(18f, 6f, 6f, 18f, paint)
            }
            Kind.MORE -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(12f, 5f, 1.9f, paint)
                canvas.drawCircle(12f, 12f, 1.9f, paint)
                canvas.drawCircle(12f, 19f, 1.9f, paint)
            }
            Kind.GRID -> {
                for ((x, y) in listOf(4f to 4f, 13.5f to 4f, 4f to 13.5f, 13.5f to 13.5f)) {
                    rect.set(x, y, x + 6.5f, y + 6.5f); canvas.drawRoundRect(rect, 1.6f, 1.6f, paint)
                }
            }
            Kind.LIST -> {
                for (y in listOf(6f, 12f, 18f)) canvas.drawLine(9f, y, 20f, y, paint)
                paint.style = Paint.Style.FILL
                for (y in listOf(6f, 12f, 18f)) canvas.drawCircle(4.5f, y, 1.4f, paint)
            }
            Kind.BACK -> {
                path.reset(); path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f)
                canvas.drawPath(path, paint)
            }
            Kind.CLOCK -> {
                canvas.drawCircle(12f, 12f, 8.5f, paint)
                path.reset(); path.moveTo(12f, 7.5f); path.lineTo(12f, 12f); path.lineTo(15f, 14f)
                canvas.drawPath(path, paint)
            }
            Kind.PIN -> {
                path.reset()
                path.moveTo(12f, 21f)
                path.cubicTo(7f, 15.5f, 5f, 12.5f, 5f, 9.5f)
                path.cubicTo(5f, 5.5f, 8.2f, 3f, 12f, 3f)
                path.cubicTo(15.8f, 3f, 19f, 5.5f, 19f, 9.5f)
                path.cubicTo(19f, 12.5f, 17f, 15.5f, 12f, 21f)
                canvas.drawPath(path, paint)
                canvas.drawCircle(12f, 9.5f, 2.4f, paint)
            }
            Kind.NOTES -> {
                for ((y, end) in listOf(6f to 20f, 11f to 20f, 16f to 14f)) canvas.drawLine(4f, y, end, y, paint)
            }
            Kind.BELL -> {
                path.reset()
                path.moveTo(5f, 17f); path.lineTo(19f, 17f)
                path.moveTo(6.5f, 17f); path.lineTo(6.5f, 11f)
                path.cubicTo(6.5f, 7.5f, 9f, 5f, 12f, 5f)
                path.cubicTo(15f, 5f, 17.5f, 7.5f, 17.5f, 11f)
                path.lineTo(17.5f, 17f)
                canvas.drawPath(path, paint)
                canvas.drawLine(10.5f, 20.5f, 13.5f, 20.5f, paint)
            }
            Kind.REPEAT -> {
                path.reset()
                path.moveTo(4f, 11f); path.lineTo(4f, 9f)
                path.cubicTo(4f, 7.5f, 5f, 6.5f, 6.5f, 6.5f); path.lineTo(19f, 6.5f)
                path.moveTo(16f, 3.5f); path.lineTo(19f, 6.5f); path.lineTo(16f, 9.5f)
                path.moveTo(20f, 13f); path.lineTo(20f, 15f)
                path.cubicTo(20f, 16.5f, 19f, 17.5f, 17.5f, 17.5f); path.lineTo(5f, 17.5f)
                path.moveTo(8f, 14.5f); path.lineTo(5f, 17.5f); path.lineTo(8f, 20.5f)
                canvas.drawPath(path, paint)
            }
            Kind.CALENDAR -> {
                rect.set(4f, 5.5f, 20f, 20f); canvas.drawRoundRect(rect, 2.5f, 2.5f, paint)
                canvas.drawLine(4f, 10f, 20f, 10f, paint)
                canvas.drawLine(8.5f, 3.5f, 8.5f, 7f, paint); canvas.drawLine(15.5f, 3.5f, 15.5f, 7f, paint)
            }
        }
        canvas.restore()
    }
}

/** A round icon button. */
fun Context.iconButton(kind: Icon.Kind, onClick: () -> Unit): Icon =
    Icon(this, kind).apply {
        val size = dp(40)
        layoutParams = LinearLayout.LayoutParams(size, size)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        pressable(dpf(20f))
        setOnClickListener { onClick() }
    }
