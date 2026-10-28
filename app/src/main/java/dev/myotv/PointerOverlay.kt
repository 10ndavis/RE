package dev.myotv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Draws the mouse pointer on top of every app. It never takes touches or focus, so injected
 * taps land on the app underneath. Android TV has no system cursor that an app could drive.
 *
 * Position is tracked here in screen pixels. Safe to call from any thread.
 */
class PointerOverlay(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val screen = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
    }
    private val size = (32 * context.resources.displayMetrics.density).toInt()
    private val view = ArrowView(context)
    private val params = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    @Volatile private var added = false
    @Volatile private var wanted = false
    private var subPixelX = 0f
    private var subPixelY = 0f

    @Volatile var x = screen.widthPixels / 2
        private set
    @Volatile var y = screen.heightPixels / 2
        private set

    /** Shows or hides the pointer; [dimmed] while gestures are locked. */
    fun show(visible: Boolean, dimmed: Boolean = false) {
        wanted = visible
        main.post {
            if (visible && !added) attach()
            if (!visible && added) detach()
            view.alpha = if (dimmed) 0.35f else 1f
        }
    }

    /** Retry showing after the draw-over-apps permission is granted. */
    fun retry() {
        if (wanted) show(true, view.alpha < 1f)
    }

    fun center() = moveTo(screen.widthPixels / 2f, screen.heightPixels / 2f)

    fun moveBy(dx: Float, dy: Float) {
        // Keep fractions of a pixel so slow, careful movement still adds up.
        subPixelX += dx
        subPixelY += dy
        val stepX = subPixelX.toInt()
        val stepY = subPixelY.toInt()
        subPixelX -= stepX
        subPixelY -= stepY
        if (stepX != 0 || stepY != 0) moveTo((x + stepX).toFloat(), (y + stepY).toFloat())
    }

    private fun moveTo(newX: Float, newY: Float) {
        x = newX.toInt().coerceIn(0, screen.widthPixels - 1)
        y = newY.toInt().coerceIn(0, screen.heightPixels - 1)
        main.post {
            if (!added) return@post
            params.x = x
            params.y = y
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun attach() {
        if (!Settings.canDrawOverlays(context)) return
        params.x = x
        params.y = y
        try {
            windowManager.addView(view, params)
            added = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't show pointer yet", e)
        }
    }

    private fun detach() {
        runCatching { windowManager.removeView(view) }
        added = false
    }

    /** A classic arrow cursor with its tip at the view's top-left corner. */
    private class ArrowView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2 * context.resources.displayMetrics.density
            strokeJoin = Paint.Join.ROUND
        }
        private val arrow = Path()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val s = w / 32f
            arrow.reset()
            arrow.moveTo(2 * s, 2 * s)
            arrow.lineTo(2 * s, 26 * s)
            arrow.lineTo(8 * s, 20 * s)
            arrow.lineTo(13 * s, 30 * s)
            arrow.lineTo(17 * s, 28 * s)
            arrow.lineTo(12 * s, 18 * s)
            arrow.lineTo(20 * s, 18 * s)
            arrow.close()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, outline)
        }
    }

    private companion object {
        const val TAG = "PointerOverlay"
    }
}
