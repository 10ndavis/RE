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

/** Zune screens don't cut, they sweep: items slide in one after another. */
object Motion {
    private val main = Handler(Looper.getMainLooper())
    private val decel: TimeInterpolator = DecelerateInterpolator(2.5f)
    private val accel: TimeInterpolator = AccelerateInterpolator(1.5f)

    fun enter(views: List<View>, u: Float, fromRight: Boolean = true) {
        views.forEachIndexed { i, v ->
            v.animate().cancel()
            v.alpha = 0f
            v.translationX = (if (fromRight) 90f else -90f) * u
            v.animate().alpha(1f).translationX(0f).setStartDelay(i * 35L).setDuration(420).setInterpolator(decel).start()
        }
    }

    fun exit(views: List<View>, u: Float, then: () -> Unit) {
        views.forEachIndexed { i, v ->
            v.animate().alpha(0f).translationX(-140f * u).setStartDelay(i * 22L).setDuration(200).setInterpolator(accel).start()
        }
        main.postDelayed(then, 200L + minOf(views.size, 6) * 22L)
    }
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
    val longAction: (() -> Unit)? = null,
    val action: () -> Unit = {},
)

class RowAdapter(private val ctx: Context, private val accent: () -> Int) : BaseAdapter() {
    private val u = Zune.u(ctx)

    var rows: List<Row> = emptyList()
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()

    private class Holder(val icon: ImageView, val primary: TextView, val secondary: TextView)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
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
        h.icon.setBackgroundColor(if (d == null) Zune.DARK else 0)
        return view
    }

    private fun build(): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * u).toInt(), (6 * u).toInt(), (12 * u).toInt(), (6 * u).toInt())
        }
        val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        row.addView(icon, LinearLayout.LayoutParams((46 * u).toInt(), (46 * u).toInt()).apply { marginEnd = (10 * u).toInt() })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val primary = ctx.zText("", 23f)
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
