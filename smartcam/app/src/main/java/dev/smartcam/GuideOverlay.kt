package dev.smartcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Everything the overlay draws for one frame. */
class OverlayState(
    val detections: List<Detection> = emptyList(),
    val subject: Detection? = null,
    val locked: Boolean = false,
    val guidance: Guidance? = null,
    val hud: String = "",
    val rotation: Int = 0,
    val rollError: Float = 0f,
    val flat: Boolean = false,
    val displayAspect: Float = 3f / 4f,
)

/**
 * Draws over the camera preview: thirds grid, level line, detected subjects, where the subject
 * should go with an arrow to it, and the advice text, turned to stay upright however the phone
 * is held. Reports taps in world-upright coordinates.
 */
class GuideOverlay(context: Context) : View(context) {
    var state = OverlayState()
        set(value) {
            field = value
            invalidate()
        }
    var onTap: ((worldX: Float, worldY: Float) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val image = RectF()
    private var downX = 0f
    private var downY = 0f
    private var focusX = 0f
    private var focusY = 0f
    private var focusAt = 0L
    private var flashAt = 0L

    private val gridPaint = stroke(Color.argb(70, 255, 255, 255), 1f)
    private val boxPaint = stroke(Color.argb(110, 255, 255, 255), 1.5f)
    private val subjectPaint = stroke(Color.WHITE, 3f)
    private val lockedPaint = stroke(GOLD, 3f)
    private val levelPaint = stroke(Color.WHITE, 2f)
    private val targetPaint = stroke(Color.WHITE, 2.5f)
    private val arrowPaint = stroke(GOLD, 3f).apply { pathEffect = DashPathEffect(floatArrayOf(10 * dp, 7 * dp), 0f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private fun stroke(c: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = c
        strokeWidth = width * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    /** Briefly whitens the preview, as feedback that a photo was taken. */
    fun flash() {
        flashAt = SystemClock.uptimeMillis()
        invalidate()
    }

    /** Where world-upright normalized point (x, y) is on screen. */
    fun toView(x: Float, y: Float): Pair<Float, Float> {
        layoutImage()
        val (dx, dy) = Rotation.ccw(x, y, state.rotation)
        return Pair(image.left + dx * image.width(), image.top + dy * image.height())
    }

    /** Fraction of the view's width the camera image takes up. */
    fun imageWidthFraction() = if (width == 0) 1f else image.width() / width

    private fun layoutImage() {
        val aspect = state.displayAspect
        val w = min(width.toFloat(), height * aspect)
        val h = w / aspect
        image.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> if (hypot(event.x - downX, event.y - downY) < 24 * dp && image.contains(event.x, event.y)) {
                focusX = event.x
                focusY = event.y
                focusAt = SystemClock.uptimeMillis()
                val (wx, wy) = Rotation.cw((event.x - image.left) / image.width(), (event.y - image.top) / image.height(), state.rotation)
                onTap?.invoke(wx, wy)
                invalidate()
                performClick()
            }
        }
        return true
    }

    override fun performClick() = super.performClick()

    override fun onDraw(canvas: Canvas) {
        layoutImage()
        val s = state
        val g = s.guidance

        for (i in 1..2) {
            val x = image.left + image.width() * i / 3
            val y = image.top + image.height() * i / 3
            canvas.drawLine(x, image.top, x, image.bottom, gridPaint)
            canvas.drawLine(image.left, y, image.right, y, gridPaint)
        }

        drawLevel(canvas, s)

        for (d in s.detections) {
            if (d === s.subject) continue
            canvas.drawRoundRect(viewRect(d.box), 6 * dp, 6 * dp, boxPaint)
        }
        s.subject?.let { drawSubject(canvas, it, s.locked) }

        if (g != null) drawGuidance(canvas, g)

        if (g != null) {
            pill(canvas, 0.5f, 0.07f, g.message, 18f, if (g.aligned) GREEN else Color.argb(170, 0, 0, 0))
            g.tip?.let { pill(canvas, 0.5f, 0.135f, it, 13f, Color.argb(120, 0, 0, 0)) }
        }
        if (s.hud.isNotEmpty()) pill(canvas, 0.5f, 0.95f, s.hud, 13f, Color.argb(140, 0, 0, 0))

        val now = SystemClock.uptimeMillis()
        val focusAge = now - focusAt
        if (focusAge < 900) {
            val t = focusAge / 900f
            targetPaint.alpha = ((1 - t) * 255).toInt()
            canvas.drawCircle(focusX, focusY, (34 - 10 * t) * dp, targetPaint)
            targetPaint.alpha = 255
        }
        val flashAge = now - flashAt
        if (flashAge < 250) {
            fill.color = Color.argb(((1 - flashAge / 250f) * 200).toInt(), 255, 255, 255)
            canvas.drawRect(image, fill)
        }
        if (focusAge < 900 || flashAge < 250) postInvalidateOnAnimation()
    }

    private fun drawLevel(canvas: Canvas, s: OverlayState) {
        if (s.flat) return
        val level = abs(s.rollError) <= Composer.LEVEL_TOLERANCE
        val cx = image.centerX()
        val cy = image.centerY()
        val half = min(image.width(), image.height()) * 0.18f
        canvas.save()
        canvas.rotate(-s.rotation.toFloat(), cx, cy)
        // Fixed marks show where level is; the line shows the real horizon.
        levelPaint.color = Color.argb(150, 255, 255, 255)
        canvas.drawLine(cx - half * 1.35f, cy, cx - half * 1.1f, cy, levelPaint)
        canvas.drawLine(cx + half * 1.1f, cy, cx + half * 1.35f, cy, levelPaint)
        canvas.rotate(-s.rollError, cx, cy)
        levelPaint.color = if (level) GREEN else AMBER
        canvas.drawLine(cx - half, cy, cx + half, cy, levelPaint)
        canvas.restore()
    }

    private fun drawSubject(canvas: Canvas, d: Detection, locked: Boolean) {
        val r = viewRect(d.box)
        val paint = if (locked) lockedPaint else subjectPaint
        val c = min(r.width(), r.height()) * 0.25f
        val path = Path().apply {
            moveTo(r.left, r.top + c); lineTo(r.left, r.top); lineTo(r.left + c, r.top)
            moveTo(r.right - c, r.top); lineTo(r.right, r.top); lineTo(r.right, r.top + c)
            moveTo(r.right, r.bottom - c); lineTo(r.right, r.bottom); lineTo(r.right - c, r.bottom)
            moveTo(r.left + c, r.bottom); lineTo(r.left, r.bottom); lineTo(r.left, r.bottom - c)
        }
        canvas.drawPath(path, paint)
        val label = if (locked) "${d.label} · locked" else d.label
        val (lx, ly) = toView(d.box.cx, d.box.top)
        pillAt(canvas, lx, ly - 14 * dp, label, 12f, if (locked) Color.argb(200, 120, 90, 0) else Color.argb(130, 0, 0, 0))
    }

    private fun drawGuidance(canvas: Canvas, g: Guidance) {
        val point = g.subjectPoint ?: return
        val target = g.target ?: return
        if (g.horizonOnly) {
            val (x1, y1) = toView(0f, point.y)
            val (x2, y2) = toView(1f, point.y)
            levelPaint.color = Color.argb(170, 255, 255, 255)
            canvas.drawLine(x1, y1, x2, y2, levelPaint)
            val (t1x, t1y) = toView(0f, target.y)
            val (t2x, t2y) = toView(1f, target.y)
            arrowPaint.color = if (g.aligned) GREEN else GOLD
            canvas.drawLine(t1x, t1y, t2x, t2y, arrowPaint)
            return
        }
        val (px, py) = toView(point.x, point.y)
        val (tx, ty) = toView(target.x, target.y)
        val radius = 20 * dp
        fill.color = if (g.aligned) Color.argb(110, 60, 200, 110) else Color.argb(50, 255, 255, 255)
        canvas.drawCircle(tx, ty, radius, fill)
        targetPaint.color = if (g.aligned) GREEN else Color.WHITE
        canvas.drawCircle(tx, ty, radius, targetPaint)
        fill.color = GOLD
        canvas.drawCircle(px, py, 5 * dp, fill)
        val dist = hypot(tx - px, ty - py)
        if (dist > radius * 1.3f) {
            // Arrow from where the subject is to where it should be.
            val angle = atan2(ty - py, tx - px)
            val ex = tx - cos(angle) * radius
            val ey = ty - sin(angle) * radius
            arrowPaint.color = GOLD
            canvas.drawLine(px, py, ex, ey, arrowPaint)
            val head = 12 * dp
            val path = Path().apply {
                moveTo(ex, ey)
                lineTo(ex - head * cos(angle - 0.5f), ey - head * sin(angle - 0.5f))
                moveTo(ex, ey)
                lineTo(ex - head * cos(angle + 0.5f), ey - head * sin(angle + 0.5f))
            }
            canvas.drawPath(path, subjectPaint.apply { color = GOLD })
            subjectPaint.color = Color.WHITE
        }
    }

    private fun viewRect(box: Box): RectF {
        val (x1, y1) = toView(box.left, box.top)
        val (x2, y2) = toView(box.right, box.bottom)
        return RectF(min(x1, x2), min(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }

    /** A text label at world point (x, y), upright to the world. */
    private fun pill(canvas: Canvas, x: Float, y: Float, label: String, size: Float, background: Int) {
        val (vx, vy) = toView(x, y)
        pillAt(canvas, vx, vy, label, size, background)
    }

    private fun pillAt(canvas: Canvas, x: Float, y: Float, label: String, size: Float, background: Int) {
        text.textSize = size * dp
        val w = text.measureText(label) + 20 * dp
        val h = text.textSize * 1.7f
        canvas.save()
        canvas.rotate(-state.rotation.toFloat(), x, y)
        fill.color = background
        canvas.drawRoundRect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, h / 2, h / 2, fill)
        canvas.drawText(label, x, y - (text.ascent() + text.descent()) / 2, text)
        canvas.restore()
    }

    companion object {
        val GOLD = Color.rgb(255, 201, 74)
        val GREEN = Color.rgb(60, 200, 110)
        val AMBER = Color.rgb(255, 150, 60)
    }
}
