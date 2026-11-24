package com.wellsey.sundial

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import java.time.LocalTime
import kotlin.math.max

/**
 * Sundial's sky: a soft gradient with glowing blobs. The sun glow drifts across the top through
 * the day. Light mode is a warm daytime sky, dark mode a dusky night one.
 */
object Backdrop {
    var screenW = 1080
        private set
    var screenH = 2400
        private set

    /** The sky, at a fraction of screen size (it's soft, so it scales up cleanly). */
    var sky: Bitmap? = null
        private set
    /** A blurrier copy for the glass panels to frost. */
    var frost: Bitmap? = null
        private set
    private var key = ""

    /** Makes sure the sky matches the hour and theme. Returns true if it was redrawn. */
    fun ensure(activity: Activity): Boolean {
        val b = activity.windowManager.currentWindowMetrics.bounds
        screenW = b.width()
        screenH = b.height()
        val hour = LocalTime.now().hour
        val k = "${Pal.dark}-$hour-$screenW-$screenH"
        if (k == key && sky != null) return false
        key = k
        val w = 270
        val h = (w * screenH.toFloat() / screenW).toInt().coerceAtLeast(300)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        paintSky(Canvas(bmp), w.toFloat(), h.toFloat(), hour)
        sky = bmp
        // Downscale then let bilinear filtering spread it back out: a cheap, smooth blur.
        val small = Bitmap.createScaledBitmap(bmp, w / 6, h / 6, true)
        frost = Bitmap.createScaledBitmap(small, w / 2, h / 2, true)
        return true
    }

    private fun paintSky(c: Canvas, w: Float, h: Float, hour: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val dark = Pal.dark
        val top = if (dark) 0xFF1B1636.toInt() else 0xFFFFE6CC.toInt()
        val mid = if (dark) 0xFF261B3D.toInt() else 0xFFFFD9E2.toInt()
        val bottom = if (dark) 0xFF0D1426.toInt() else 0xFFD3E5FF.toInt()
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(top, mid, bottom), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)

        // Sun from left (6am) to right (6pm)
        val sunX = ((hour - 6) / 12f).coerceIn(0.08f, 0.92f) * w
        blob(c, p, sunX, h * 0.06f, w * 0.75f, if (dark) 0xFF8A4A2E.toInt() else 0xFFFFC07A.toInt(), if (dark) 0.75f else 0.9f)
        blob(c, p, w * 0.05f, h * 0.62f, w * 0.85f, if (dark) 0xFF3D2C78.toInt() else 0xFFB7D4FF.toInt(), 0.8f)
        blob(c, p, w * 0.98f, h * 0.48f, w * 0.7f, if (dark) 0xFF5A2550.toInt() else 0xFFFFB5CA.toInt(), 0.7f)
        blob(c, p, w * 0.7f, h * 0.95f, w * 0.8f, if (dark) 0xFF12435A.toInt() else 0xFFC9F0E4.toInt(), 0.6f)
    }

    private fun blob(c: Canvas, p: Paint, x: Float, y: Float, r: Float, color: Int, strength: Float) {
        val a = (255 * strength).toInt()
        val center = (color and 0x00FFFFFF) or (a shl 24)
        p.shader = RadialGradient(x, y, r, intArrayOf(center, center and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
    }
}

/** Draws the sky full-screen. Used as the background of each screen's root. */
class SkyDrawable : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = RectF()

    override fun draw(c: Canvas) {
        val sky = Backdrop.sky ?: run { c.drawColor(Pal.bgSolid); return }
        dst.set(0f, 0f, Backdrop.screenW.toFloat(), Backdrop.screenH.toFloat())
        c.drawBitmap(sky, null, dst, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/**
 * The glass panel: the frosted sky behind the view, a translucent tint, a hairline border and a
 * bright top edge — the same recipe as Perch.
 */
class Glass(
    private val host: View,
    var radius: Float,
    var tint: Int = Pal.glass,
    var border: Int = Pal.glassBorder,
    var highlight: Int = Pal.glassHighlight,
) : Drawable() {
    private val loc = IntArray(2)
    private val last = IntArray(2)
    private val rect = RectF()
    private val path = Path()
    private val matrix = Matrix()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // When the panel moves (scrolling, animations), redraw so the frosted sky stays put.
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        host.getLocationOnScreen(loc)
        if (loc[0] != last[0] || loc[1] != last[1]) invalidateSelf()
        true
    }

    init {
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = v.viewTreeObserver.addOnPreDrawListener(preDraw)
            override fun onViewDetachedFromWindow(v: View) = v.viewTreeObserver.removeOnPreDrawListener(preDraw)
        })
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        hi.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.top + max(radius, bounds.height() * 0.3f),
            highlight, highlight and 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(c: Canvas) {
        rect.set(bounds)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.save()
        c.clipPath(path)
        val frost = Backdrop.frost
        if (frost != null) {
            host.getLocationOnScreen(loc)
            last[0] = loc[0]; last[1] = loc[1]
            matrix.setScale(Backdrop.screenW.toFloat() / frost.width, Backdrop.screenH.toFloat() / frost.height)
            // The location includes animated offsets, so the sky stays still while panels move.
            matrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            c.drawBitmap(frost, matrix, bmpPaint)
        }
        fill.color = if (frost != null) tint else Pal.bgSolid
        c.drawRect(rect, fill)
        c.restore()

        val sw = host.resources.displayMetrics.density
        stroke.strokeWidth = sw
        stroke.color = border
        rect.inset(sw / 2, sw / 2)
        c.drawRoundRect(rect, radius, radius, stroke)
        if (highlight != 0) {
            hi.strokeWidth = sw * 1.2f
            c.drawRoundRect(rect, radius, radius, hi)
        }
    }

    override fun getOutline(outline: android.graphics.Outline) {
        outline.setRoundRect(bounds, radius)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun View.glass(radius: Float, tint: Int = Pal.glass) {
    background = Glass(this, radius, tint)
}

/**
 * Sets up a screen: the sky behind everything, see-through system bars, and [content] padded
 * clear of the status bar, navigation bar and keyboard.
 */
fun Activity.setSkyContent(content: View) {
    Pal.init(this)
    Backdrop.ensure(this)
    window.setDecorFitsSystemWindows(false)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    window.isNavigationBarContrastEnforced = false
    val light = if (Pal.dark) 0 else
        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    window.insetsController?.setSystemBarsAppearance(
        light,
        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
    )
    val root = FrameLayout(this).apply { background = SkyDrawable() }
    root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    root.setOnApplyWindowInsetsListener { _, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        val ime = insets.getInsets(WindowInsets.Type.ime())
        content.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
        WindowInsets.CONSUMED
    }
    setContentView(root)
}

/** Fades and lifts children in, one after another. */
fun ViewGroup.staggerIn(ctx: Context, from: Int = 0) {
    val lift = ctx.dpf(10f)
    for (i in from until childCount) {
        val v = getChildAt(i)
        v.alpha = 0f
        v.translationY = lift
        v.animate().alpha(1f).translationY(0f)
            .setStartDelay(((i - from).coerceAtMost(10) * 22).toLong())
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .start()
    }
}

/** Redraws a whole view tree (after the sky changes). */
fun View.invalidateTree() {
    invalidate()
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).invalidateTree()
}
