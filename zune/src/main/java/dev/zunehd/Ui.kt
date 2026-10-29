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

/** The press "tilt" the Zune introduced (and Windows Phone kept): the item leans toward the finger. */
@SuppressLint("ClickableViewAccessibility")
fun View.zuneTilt() {
    cameraDistance = 8000f * resources.displayMetrics.density
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val nx = (e.x / v.width.coerceAtLeast(1) - 0.5f).coerceIn(-0.5f, 0.5f)
                val ny = (e.y / v.height.coerceAtLeast(1) - 0.5f).coerceIn(-0.5f, 0.5f)
                v.pivotX = v.width / 2f
                v.pivotY = v.height / 2f
                v.animate().rotationY(nx * 14f).rotationX(-ny * 14f).scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().rotationY(0f).rotationX(0f).scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        false
    }
}

/**
 * Zune motion. Lists swing in and out like a turnstile hinged on the left edge of the screen,
 * one item after another, and the item you picked flies up to become the next screen's title.
 */
object Motion {
    private val main = Handler(Looper.getMainLooper())
    private val decel: TimeInterpolator = DecelerateInterpolator(2.2f)
    private val accel: TimeInterpolator = AccelerateInterpolator(1.6f)

    private fun hinge(v: View) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        v.cameraDistance = 9000f * v.resources.displayMetrics.density
        v.pivotX = -(loc[0] - v.translationX)
        v.pivotY = v.height / 2f
    }

    private fun reset(v: View) {
        v.animate().cancel()
        v.translationY = 0f
        v.scaleX = 1f
        v.scaleY = 1f
    }

    /** Swing items in from the right edge, top to bottom. */
    fun enter(views: List<View>, u: Float, fromRight: Boolean = true) {
        views.forEachIndexed { i, v ->
            reset(v)
            v.alpha = 0f
            v.translationX = (if (fromRight) 30f else -30f) * u
            v.rotationY = if (fromRight) -70f else 70f
            v.post {
                hinge(v)
                v.animate().alpha(1f).translationX(0f).rotationY(0f)
                    .setStartDelay(i * 40L).setDuration(360).setInterpolator(decel).start()
            }
        }
    }

    /** Swing items out, top to bottom; [hero] instead flies up to the title position. */
    fun exit(views: List<View>, u: Float, hero: View? = null, heroTop: Float = 0f, heroScale: Float = Section.SCALE, then: () -> Unit) {
        var delay = 0L
        for (v in views) {
            if (v === hero) continue
            hinge(v)
            v.animate().alpha(0f).rotationY(80f).translationX(-20f * u)
                .setStartDelay(delay).setDuration(220).setInterpolator(accel).start()
            delay += 28
        }
        if (hero != null) {
            val loc = IntArray(2)
            hero.getLocationOnScreen(loc)
            hero.pivotX = 0f
            hero.pivotY = 0f
            hero.animate().translationY(heroTop - loc[1]).scaleX(heroScale).scaleY(heroScale)
                .setStartDelay(60).setDuration(300).setInterpolator(decel).start()
        }
        main.postDelayed(then, 340L)
    }

    /** List rows sweep in one after another, as Zune lists did when you changed category. */
    fun cascade(page: View, u: Float) {
        val list = findList(page) ?: return
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            row.animate().cancel()
            row.alpha = 0f
            row.translationX = 60f * u
            row.animate().alpha(1f).translationX(0f).setStartDelay(i * 30L).setDuration(320).setInterpolator(decel).start()
        }
    }

    private fun findList(v: View): ListView? {
        if (v is ListView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findList(v.getChildAt(i))?.let { return it }
        return null
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
