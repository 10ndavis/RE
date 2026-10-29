package dev.zunehd

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * The Zune HD home screen's two panes, quickplay and the menu. The pane in front fills the
 * screen; the other shrinks to about a third and parks at the side (quickplay on the left,
 * the menu on the right). Swiping zooms between them; tapping the small pane brings it forward.
 *
 * Add the quickplay pane first, then the menu pane.
 */
class HomePager(ctx: Context) : ViewGroup(ctx) {
    companion object {
        const val BACK_SCALE = 0.36f
        /** Where the menu's words start, leaving room for shrunken quickplay. In Zune pixels. */
        const val MENU_LEFT = 76f
        const val MENU_TOP = 60f
        const val QUICK_LEFT = 44f
        const val QUICK_TOP = 26f
    }

    private val u = Zune.u(ctx)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    /** 1 = menu in front, 0 = quickplay in front. */
    var progress = 1f
        private set
    var onProgress: ((Float) -> Unit)? = null

    private var anim: ValueAnimator? = null
    private var downX = 0f
    private var downY = 0f
    private var start = 1f
    private var dragging = false
    private var tapBack = false
    private var tracker: VelocityTracker? = null

    init {
        isChildrenDrawingOrderEnabled = true
    }

    private val quick get() = getChildAt(0)
    private val menu get() = getChildAt(1)

    val onMenu get() = progress > 0.5f

    fun showMenu() = animateTo(1f)

    fun showQuickplay() = animateTo(0f)

    fun toggle() = animateTo(if (onMenu) 0f else 1f)

    fun animateTo(target: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = (360 * abs(target - progress)).toLong().coerceAtLeast(120)
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                progress = it.animatedValue as Float
                apply()
            }
            start()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        val ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(ws, hs)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) getChildAt(i).layout(0, 0, r - l, b - t)
        apply()
    }

    /** The front pane is drawn (and touched) last. */
    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int =
        if (onMenu) drawingPosition else childCount - 1 - drawingPosition

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun apply() {
        if (childCount < 2 || width == 0) return
        val w = width.toFloat()
        val s = BACK_SCALE
        val p = progress.coerceIn(-0.2f, 1.2f)
        // Menu: full size in front; in back its words start 60% across, a little lower.
        menu.pivotX = 0f
        menu.pivotY = 0f
        val ms = lerp(s, 1f, p)
        menu.scaleX = ms
        menu.scaleY = ms
        menu.translationX = lerp(w * 0.6f - MENU_LEFT * u * s, 0f, p)
        menu.translationY = lerp(96 * u - MENU_TOP * u * s, 0f, p)
        menu.alpha = lerp(0.8f, 1f, p.coerceIn(0f, 1f))
        // Quickplay: full size in front; in back a narrow column at the left edge.
        quick.pivotX = 0f
        quick.pivotY = 0f
        val qs = lerp(1f, s, p)
        quick.scaleX = qs
        quick.scaleY = qs
        quick.translationX = lerp(0f, 18 * u - QUICK_LEFT * u * s, p)
        quick.translationY = lerp(0f, 140 * u - QUICK_TOP * u * s, p)
        quick.alpha = lerp(1f, 0.8f, p.coerceIn(0f, 1f))
        invalidate()
        onProgress?.invoke(progress)
    }

    /** The strip where the small pane sits, which a tap brings forward. */
    private fun inBackPane(x: Float): Boolean =
        if (onMenu) x < (MENU_LEFT - 6) * u else x > width * 0.58f

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                start = progress
                dragging = anim?.isRunning == true
                anim?.cancel()
                tapBack = inBackPane(e.x)
                tracker?.recycle()
                tracker = VelocityTracker.obtain().apply { addMovement(e) }
                if (tapBack) return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                startDrag(e)
            }
        }
        return dragging
    }

    private fun startDrag(e: MotionEvent) {
        if (dragging) return
        val dx = e.x - downX
        val dy = e.y - downY
        if (abs(dx) > slop && abs(dx) > abs(dy)) {
            dragging = true
            downX = e.x
            start = progress
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                startDrag(e)
                if (dragging) {
                    // Finger right pulls quickplay in from the left.
                    progress = (start - (e.x - downX) / (width * 0.55f)).let {
                        when {
                            it < 0f -> it * 0.25f
                            it > 1f -> 1f + (it - 1f) * 0.25f
                            else -> it
                        }
                    }
                    apply()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    tracker?.computeCurrentVelocity(1000)
                    val v = -(tracker?.xVelocity ?: 0f) / (width * 0.55f)
                    animateTo(
                        when {
                            v > 0.6f -> 1f
                            v < -0.6f -> 0f
                            else -> if (progress > 0.5f) 1f else 0f
                        },
                    )
                } else if (tapBack && e.actionMasked == MotionEvent.ACTION_UP) {
                    toggle()
                }
                dragging = false
                tapBack = false
                tracker?.recycle()
                tracker = null
            }
        }
        return true
    }
}
