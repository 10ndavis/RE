package dev.zunehd

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Zune HD category strip ("artists albums songs playlists ..."): huge headers in a row,
 * the current one white and the next ones dimmed and running off the edge. Swiping the
 * content drags the headers along with it.
 */
class PivotView(ctx: Context) : ViewGroup(ctx) {
    private val u = Zune.u(ctx)
    private val headers = ArrayList<TextView>()
    private val pages = ArrayList<View>()
    private val margin = 12 * u
    private val gap = 16 * u
    private val headerHeight = (58 * u).toInt()

    private var position = 0f
    private var anim: ValueAnimator? = null
    var onPageSelected: ((Int) -> Unit)? = null

    val current: Int get() = position.roundToInt().coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val pageCount: Int get() = pages.size

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startPos = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    fun addPage(title: String, page: View) {
        val index = pages.size
        val h = context.zText(title, 42f).apply {
            ellipsize = null
            setOnClickListener { snapTo(index) }
        }
        headers += h
        pages += page
        addView(page)
        addView(h)
    }

    fun headerViews(): List<View> = headers

    fun setPage(i: Int) {
        anim?.cancel()
        position = i.coerceIn(0, (pages.size - 1).coerceAtLeast(0)).toFloat()
        apply()
    }

    fun snapTo(i: Int) {
        val target = i.coerceIn(0, pages.size - 1)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(position, target.toFloat()).apply {
            duration = (220 + 120 * abs(target - position)).toLong().coerceAtMost(520)
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                position = it.animatedValue as Float
                apply()
            }
            start()
        }
        onPageSelected?.invoke(target)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        headers.forEach { it.measure(unspecified, unspecified) }
        val pw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val ph = MeasureSpec.makeMeasureSpec((h - headerHeight).coerceAtLeast(0), MeasureSpec.EXACTLY)
        pages.forEach { it.measure(pw, ph) }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = margin
        val top = (4 * u).toInt()
        for (h in headers) {
            h.layout(x.toInt(), top, x.toInt() + h.measuredWidth, top + h.measuredHeight)
            x += h.measuredWidth + gap
        }
        for (p in pages) p.layout(0, headerHeight, r - l, b - t)
        apply()
    }

    private fun apply() {
        val w = width.toFloat()
        if (w == 0f || pages.isEmpty()) return
        val starts = FloatArray(headers.size) { headers[it].left - margin }
        // Past the ends the content rubber-bands; let the headers follow a little.
        val over = when {
            position < 0f -> position * w * 0.5f
            position > pages.size - 1 -> (position - (pages.size - 1)) * w * 0.5f
            else -> 0f
        }
        val shift = PivotMath.stripOffset(starts, position) + over
        headers.forEachIndexed { i, h ->
            h.translationX = -shift
            h.alpha = PivotMath.headerAlpha(i, position)
        }
        pages.forEachIndexed { i, p ->
            val d = i - position
            p.translationX = d * w
            p.visibility = if (abs(d) < 1f) View.VISIBLE else View.INVISIBLE
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                startPos = position
                dragging = anim?.isRunning == true
                anim?.cancel()
                tracker?.recycle()
                tracker = VelocityTracker.obtain().apply { addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                startDragIfHorizontal(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) {
                tracker?.recycle()
                tracker = null
            }
        }
        return dragging
    }

    private fun startDragIfHorizontal(e: MotionEvent) {
        if (dragging || pages.size < 2) return
        val dx = e.x - downX
        val dy = e.y - downY
        if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
            dragging = true
            downX = e.x
            startPos = position
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                startPos = position
            }
            MotionEvent.ACTION_MOVE -> {
                startDragIfHorizontal(e)
                if (dragging) {
                    position = PivotMath.drag(startPos, (downX - e.x) / width, pages.size)
                    apply()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val t = tracker
                    t?.computeCurrentVelocity(1000)
                    val v = if (t == null || e.actionMasked == MotionEvent.ACTION_CANCEL) 0f else -t.xVelocity / width
                    snapTo(PivotMath.snapTarget(position, startPos, v, pages.size))
                }
                dragging = false
                tracker?.recycle()
                tracker = null
            }
        }
        return true
    }
}
