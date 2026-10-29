package dev.zunehd

import android.animation.TimeInterpolator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** A Zune text label: lowercase, light, tight, no font padding. Size in Zune pixels. */
fun Context.zText(text: CharSequence, size: Float, light: Boolean = true, color: Int = Zune.WHITE): TextView =
    TextView(this).apply {
        this.text = text
        typeface = if (light) Zune.light(context) else Zune.regular(context)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * Zune.u(context))
        setTextColor(color)
        includeFontPadding = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        letterSpacing = -0.02f
    }

/** Zune press feedback: the item dims while your finger is on it. */
@SuppressLint("ClickableViewAccessibility")
fun View.zuneTilt() {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().alpha(0.55f).setDuration(60).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().alpha(1f).setDuration(140).start()
        }
        false
    }
}

/** Pressed art tiles get the Zune's bright white outline. */
fun View.zuneOutline() {
    val u = Zune.u(context)
    val pressed = android.graphics.drawable.GradientDrawable().apply {
        setStroke((3 * u).toInt(), Zune.WHITE)
        cornerRadius = 3 * u
    }
    foreground = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
    }
    isClickable = true
}

/**
 * Zune HD motion, measured from 30 fps footage of the device. Every screen change is a flip
 * hinged on the left edge of the display: the old screen's items swing away (their right side
 * receding), top to bottom about a frame apart, then the new screen's items swing in from the
 * other side (right side starting toward you) and settle, also top to bottom.
 */
object Motion {
    const val STAGGER = 33L
    const val OUT = 200L
    const val IN = 260L
    /** Rotation at the start of an entrance / end of an exit, in degrees (see [Flip]). */
    const val ANGLE = 80f

    private val main = Handler(Looper.getMainLooper())
    private val decel: TimeInterpolator = DecelerateInterpolator(2f)
    private val accel: TimeInterpolator = AccelerateInterpolator(1.5f)

    /** Hinge [v] on the left edge of the screen. */
    private fun hinge(v: View) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        v.cameraDistance = 8000f * v.resources.displayMetrics.density
        v.pivotX = -(loc[0] - v.translationX)
        v.pivotY = v.height / 2f
    }

    private fun reset(v: View) {
        v.animate().cancel()
        v.translationX = 0f
        v.translationY = 0f
        v.scaleX = 1f
        v.scaleY = 1f
    }

    /** Flip items in, top to bottom. */
    fun enter(views: List<View>, u: Float) {
        views.forEachIndexed { i, v ->
            reset(v)
            v.alpha = 0f
            v.rotationY = Flip.IN_SIGN * ANGLE
            v.post {
                hinge(v)
                v.animate().alpha(1f).rotationY(0f)
                    .setStartDelay(i * STAGGER).setDuration(IN).setInterpolator(decel).start()
            }
        }
    }

    /**
     * Flip items away, top to bottom, then run [then]. [hero] instead moves by ([heroDx],
     * [heroDy]) and scales by [heroScale] from its top-left corner.
     */
    fun exit(
        views: List<View>,
        u: Float,
        hero: View? = null,
        heroDx: Float = 0f,
        heroDy: Float = 0f,
        heroScale: Float = 1f,
        then: () -> Unit,
    ) {
        var delay = 0L
        for (v in views) {
            if (v === hero) continue
            hinge(v)
            v.animate().rotationY(Flip.OUT_SIGN * ANGLE).alpha(0f)
                .setStartDelay(delay).setDuration(OUT).setInterpolator(accel).start()
            delay += STAGGER
        }
        if (hero != null) {
            hero.pivotX = 0f
            hero.pivotY = 0f
            hero.animate().translationXBy(heroDx).translationYBy(heroDy).scaleX(heroScale).scaleY(heroScale)
                .setStartDelay(0).setDuration(OUT + 60).setInterpolator(decel).start()
        }
        main.postDelayed(then, OUT + minOf(views.size, 8) * STAGGER)
    }

    /** List rows flip in one after another, as when changing category. */
    fun cascade(page: View, u: Float) {
        val list = findList(page) ?: return
        enter((0 until list.childCount).map { list.getChildAt(it) }, u)
    }

    private fun findList(v: View): ListView? {
        if (v is ListView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findList(v.getChildAt(i))?.let { return it }
        return null
    }
}

/**
 * The same flip for custom-drawn screens: rotates the canvas about a vertical line at the left
 * edge of the screen, turning the same way as View.rotationY.
 */
object Flip {
    /** View.rotationY sign that swings the right side away from the viewer (exits). */
    const val OUT_SIGN = 1f
    const val IN_SIGN = -1f

    private val camera = android.graphics.Camera()
    private val matrix = android.graphics.Matrix()

    /** [degrees] in View.rotationY terms; [pivotX] is usually 0, the screen's left edge. */
    fun apply(c: android.graphics.Canvas, degrees: Float, pivotX: Float, pivotY: Float) {
        if (degrees == 0f) return
        camera.save()
        // Same perspective as a View with cameraDistance = 8000 dp.
        camera.setLocation(0f, 0f, -50f)
        camera.rotateY(degrees)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-pivotX, -pivotY)
        matrix.postTranslate(pivotX, pivotY)
        c.concat(matrix)
    }
}

/** The cropped title at the top of a section, as big as the menu word you tapped, zoomed. */
object Section {
    const val SCALE = 1.9f
    const val MENU_SIZE = 46f
    /** How much of the title hangs above the top of the screen, in Zune pixels. */
    const val CROP = 52f
}

fun Activity.zuneImmersive(hideNav: Boolean = false) {
    if (Build.VERSION.SDK_INT >= 30) {
        window.insetsController?.let {
            it.hide(if (hideNav) WindowInsets.Type.systemBars() else WindowInsets.Type.statusBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            (if (hideNav) View.SYSTEM_UI_FLAG_HIDE_NAVIGATION else 0)
    }
}

/** One row of a Zune list: big light primary text, small gray secondary text, optional art. */
class Row(
    val primary: String,
    val secondary: String? = null,
    val icon: (() -> Drawable?)? = null,
    val highlight: Boolean = false,
    /** A Zune letter tile ("a", "b", ...) in line with the list instead of a normal row. */
    val tile: Boolean = false,
    val longAction: (() -> Unit)? = null,
    val action: () -> Unit = {},
)

/** [compact] is the denser layout for long lists such as apps: small icons, one line per entry. */
class RowAdapter(private val ctx: Context, private val compact: Boolean = false, private val accent: () -> Int) : BaseAdapter() {
    private val u = Zune.u(ctx)

    var rows: List<Row> = emptyList()
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 2
    override fun getItemViewType(position: Int) = if (rows[position].tile) 1 else 0

    private fun tileView(convertView: View?, row: Row): View {
        val tv = (convertView as? android.widget.FrameLayout)?.getChildAt(0) as? TextView
        if (tv != null) {
            tv.text = row.primary
            return convertView
        }
        val frame = android.widget.FrameLayout(ctx).apply {
            setPadding((12 * u).toInt(), ((if (compact) 6 else 8) * u).toInt(), 0, ((if (compact) 3 else 4) * u).toInt())
        }
        val side = ((if (compact) 26 else 36) * u).toInt()
        val letter = ctx.zText(row.primary, if (compact) 18f else 24f).apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { setStroke((1.5f * u).toInt(), Zune.WHITE) }
        }
        frame.addView(letter, android.widget.FrameLayout.LayoutParams(side, side))
        return frame
    }

    private class Holder(val icon: ImageView, val primary: TextView, val secondary: TextView)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        if (rows[position].tile) return tileView(convertView, rows[position])
        val view = convertView ?: build()
        val h = view.tag as Holder
        val row = rows[position]
        h.primary.text = row.primary
        h.primary.setTextColor(if (row.highlight) accent() else Zune.WHITE)
        h.secondary.text = row.secondary ?: ""
        h.secondary.visibility = if (row.secondary.isNullOrEmpty()) View.GONE else View.VISIBLE
        val d = row.icon?.invoke()
        h.icon.setImageDrawable(d)
        h.icon.visibility = if (row.icon == null) View.GONE else View.VISIBLE
        h.icon.setBackgroundColor(if (d == null && !compact) Zune.DARK else 0)
        return view
    }

    private fun build(): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val v = ((if (compact) 3 else 6) * u).toInt()
            // Compact lists leave room on the right for the letter strip.
            setPadding((12 * u).toInt(), v, ((if (compact) 34 else 12) * u).toInt(), v)
        }
        val icon = ImageView(ctx).apply { scaleType = if (compact) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP }
        val side = ((if (compact) 28 else 46) * u).toInt()
        row.addView(icon, LinearLayout.LayoutParams(side, side).apply { marginEnd = ((if (compact) 9 else 10) * u).toInt() })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val primary = ctx.zText("", if (compact) 18f else 23f)
        val secondary = ctx.zText("", 13f, light = false, color = Zune.GRAY)
        texts.addView(primary)
        texts.addView(secondary, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (2 * u).toInt() })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.tag = Holder(icon, primary, secondary)
        row.zuneTilt()
        return row
    }
}

fun Context.zuneList(adapter: RowAdapter, empty: String? = null): ViewGroup {
    val u = Zune.u(this)
    val list = ListView(this).apply {
        divider = null
        dividerHeight = 0
        selector = ColorDrawable(0)
        cacheColorHint = 0
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(0, 0, 0, (24 * u).toInt())
        this.adapter = adapter
        setOnItemClickListener { _, _, pos, _ -> adapter.rows.getOrNull(pos)?.action?.invoke() }
        setOnItemLongClickListener { _, _, pos, _ ->
            val a = adapter.rows.getOrNull(pos)?.longAction ?: return@setOnItemLongClickListener false
            a()
            true
        }
    }
    val frame = android.widget.FrameLayout(this)
    frame.addView(list, -1, -1)
    if (empty != null) {
        val tv = zText(empty, 18f, color = Zune.GRAY).apply {
            isSingleLine = false
            maxLines = 4
            setPadding((12 * u).toInt(), (8 * u).toInt(), (12 * u).toInt(), 0)
            setLineSpacing(0f, 1.15f)
        }
        frame.addView(tv, -1, -2)
        list.emptyView = tv
    }
    return frame
}

/** The default Zune HD backdrop: glowing magenta and orange on black, with soft swooshes. */
object Backdrop {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    fun draw(c: Canvas, w: Float, h: Float, dim: Int = 0x66) {
        c.drawColor(0xFF000000.toInt())
        glow(c, w * 1.05f, h * 0.08f, w * 0.95f, 0xD0D6007F.toInt())
        glow(c, -w * 0.1f, h * 0.72f, w * 0.85f, 0xC0F7941D.toInt())
        glow(c, w * 0.85f, h * 0.95f, w * 0.7f, 0xA05B2C8F.toInt())
        line.strokeWidth = w * 0.004f
        for (i in 0 until 5) {
            line.color = 0x14FFFFFF
            path.reset()
            val y = h * (0.25f + i * 0.09f)
            path.moveTo(-w * 0.1f, y + h * 0.2f)
            path.cubicTo(w * 0.3f, y - h * 0.15f, w * 0.7f, y + h * 0.25f, w * 1.1f, y - h * 0.05f * i)
            c.drawPath(path, line)
        }
        c.drawColor(dim shl 24)
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        paint.shader = RadialGradient(x, y, r, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, paint)
        paint.shader = null
    }
}

/** Outline-circle transport glyphs in the Zune style. */
object Glyphs {
    private val path = Path()

    fun play(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        path.reset()
        path.moveTo(cx - s * 0.32f, cy - s * 0.5f)
        path.lineTo(cx + s * 0.5f, cy)
        path.lineTo(cx - s * 0.32f, cy + s * 0.5f)
        path.close()
        c.drawPath(path, p)
    }

    fun pause(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val w = s * 0.26f
        c.drawRect(cx - s * 0.36f, cy - s * 0.48f, cx - s * 0.36f + w, cy + s * 0.48f, p)
        c.drawRect(cx + s * 0.36f - w, cy - s * 0.48f, cx + s * 0.36f, cy + s * 0.48f, p)
    }

    fun skip(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, forward: Boolean) {
        val d = if (forward) 1f else -1f
        val h = s * 0.42f
        tri(c, cx - d * s * 0.5f, cx - d * s * 0.04f, cy, h, p)
        tri(c, cx - d * s * 0.04f, cx + d * s * 0.42f, cy, h, p)
        val bx = cx + d * s * 0.42f
        c.drawRect(minOf(bx, bx + d * s * 0.1f), cy - h, maxOf(bx, bx + d * s * 0.1f), cy + h, p)
    }

    private fun tri(c: Canvas, baseX: Float, tipX: Float, cy: Float, h: Float, p: Paint) {
        path.reset()
        path.moveTo(baseX, cy - h)
        path.lineTo(tipX, cy)
        path.lineTo(baseX, cy + h)
        path.close()
        c.drawPath(path, p)
    }
}
