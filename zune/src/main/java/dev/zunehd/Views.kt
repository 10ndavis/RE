package dev.zunehd

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.os.BatteryManager
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import java.util.Date
import kotlin.math.abs
import kotlin.math.max

/** Full-screen backdrop: the Zune glow, the blurred album art, or plain black. */
class BackdropView(ctx: Context) : View(ctx) {
    private val prefs = Prefs(ctx)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()

    fun update() = invalidate()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (prefs.background) {
            "black" -> c.drawColor(0xFF000000.toInt())
            "art" -> {
                val b = Player.blurredArt
                if (b == null) {
                    Backdrop.draw(c, w, h)
                } else {
                    val s = max(w / b.width, h / b.height)
                    m.setScale(s, s)
                    m.postTranslate((w - b.width * s) / 2, (h - b.height * s) / 2)
                    c.drawBitmap(b, m, paint)
                    c.drawColor(0x99000000.toInt())
                }
            }
            else -> Backdrop.draw(c, w, h)
        }
    }
}

/** The thin Zune status strip: clock on the left, playing mark and battery on the right. */
@SuppressLint("ClickableViewAccessibility")
class StatusBarView(ctx: Context) : View(ctx) {
    private val u = Zune.u(ctx)
    var onPull: (() -> Unit)? = null

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Zune.WHITE
        textSize = 13 * u
        typeface = Zune.regular(ctx)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * u
        color = Zune.WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Zune.WHITE }
    private val body = RectF()
    private val date = Date()
    private var level = -1
    private var charging = false
    private var downY = 0f
    private var pulled = false

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / max(1, i.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
            val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
            charging = s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
            invalidate()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        tick.run()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.unregisterReceiver(battery)
        removeCallbacks(tick)
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val pattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
        c.drawText(DateFormat.format(pattern, date.apply { time = System.currentTimeMillis() }).toString(), 12 * u, cy + text.textSize * 0.36f, text)

        val bw = 20 * u
        val bh = 10 * u
        val right = width - 12 * u
        val r = body.apply { set(right - bw, cy - bh / 2, right - 2 * u, cy + bh / 2) }
        c.drawRect(r, stroke)
        c.drawRect(right - 2 * u, cy - bh / 4, right, cy + bh / 4, fill)
        if (level >= 0) {
            val inset = 2 * u
            c.drawRect(r.left + inset, r.top + inset, r.left + inset + (r.width() - 2 * inset) * level / 100f, r.bottom - inset, fill)
        }
        var x = r.left - 10 * u
        if (charging) {
            c.drawText("+", x - text.measureText("+"), cy + text.textSize * 0.36f, text)
            x -= 14 * u
        }
        if (Player.isPlaying) Glyphs.play(c, x - 4 * u, cy, 9 * u, fill)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                pulled = false
            }
            MotionEvent.ACTION_MOVE -> if (!pulled && e.y - downY > 10 * u) {
                pulled = true
                onPull?.invoke()
            }
            MotionEvent.ACTION_UP -> if (!pulled) onPull?.invoke()
        }
        return true
    }
}

/**
 * What the Zune HD showed when you pressed its media button: the song, transport controls
 * and volume, dropped over whatever screen you were on.
 */
@SuppressLint("ClickableViewAccessibility")
class QuickControlsView(ctx: Context) : View(ctx) {
    private val u = Zune.u(ctx)
    private val prefs = Prefs(ctx)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    val heightPx = (136 * u).toInt()

    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Zune.light(ctx); textSize = 20 * u; color = Zune.WHITE }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Zune.regular(ctx); textSize = 13 * u; color = Zune.GRAY }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Zune.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * u; color = Zune.WHITE }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragVolume = false
    private var downY = 0f
    private val hide = Runnable { hide() }

    val showing get() = visibility == View.VISIBLE && translationY > -heightPx / 2f

    init {
        visibility = INVISIBLE
        translationY = -heightPx.toFloat()
    }

    fun show() {
        visibility = VISIBLE
        animate().translationY(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        scheduleHide()
    }

    fun hide() {
        removeCallbacks(hide)
        animate().translationY(-heightPx.toFloat()).setDuration(180).withEndAction { visibility = INVISIBLE }.start()
    }

    fun toggle() = if (showing) hide() else show()

    fun scheduleHide() {
        removeCallbacks(hide)
        postDelayed(hide, 4000)
    }

    fun update() = invalidate()

    private val playX get() = width / 2f
    private val buttonsY get() = 80 * u
    private val volumeY get() = 118 * u

    override fun onDraw(c: Canvas) {
        c.drawColor(0xF0101010.toInt())
        val m = 14 * u
        val avail = width - 2 * m
        val t = if (Player.hasTrack) Player.title().lowercase() else if (Player.hasAccess(context)) "nothing playing" else "music access is off"
        val s = if (Player.hasTrack) Player.artist().lowercase() else Player.appLabel(context, prefs.musicPackage)
        c.drawText(TextUtils.ellipsize(t, title, avail, TextUtils.TruncateAt.END).toString(), m, 26 * u, title)
        c.drawText(TextUtils.ellipsize(s, sub, avail, TextUtils.TruncateAt.END).toString(), m, 44 * u, sub)

        val y = buttonsY
        c.drawCircle(playX - 72 * u, y, 18 * u, ring)
        Glyphs.skip(c, playX - 72 * u, y, 14 * u, fill, forward = false)
        c.drawCircle(playX, y, 23 * u, ring)
        if (Player.isPlaying) Glyphs.pause(c, playX, y, 17 * u, fill) else Glyphs.play(c, playX + 1.5f * u, y, 17 * u, fill)
        c.drawCircle(playX + 72 * u, y, 18 * u, ring)
        Glyphs.skip(c, playX + 72 * u, y, 14 * u, fill, forward = true)

        // Volume as a row of steps, like the device's volume overlay.
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = avail / max
        for (i in 0 until max) {
            bar.color = if (i < vol) prefs.accent else 0x40FFFFFF
            val h = (4 + 8 * (i + 1f) / max) * u
            c.drawRect(m + i * step, volumeY - h, m + (i + 1) * step - 1.5f * u, volumeY, bar)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scheduleHide()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                dragVolume = e.y > volumeY - 22 * u
                if (dragVolume) setVolume(e.x)
            }
            MotionEvent.ACTION_MOVE -> if (dragVolume) setVolume(e.x)
            MotionEvent.ACTION_UP -> {
                if (!dragVolume) {
                    when {
                        downY - e.y > 30 * u -> hide()
                        near(e, playX, buttonsY) -> Player.toggle(context)
                        near(e, playX - 72 * u, buttonsY) -> Player.previous()
                        near(e, playX + 72 * u, buttonsY) -> Player.next()
                    }
                }
                invalidate()
            }
        }
        return true
    }

    private fun near(e: MotionEvent, x: Float, y: Float) = abs(e.x - x) < 32 * u && abs(e.y - y) < 32 * u

    private fun setVolume(x: Float) {
        val m = 14 * u
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (((x - m) / (width - 2 * m)) * max + 0.5f).toInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        invalidate()
    }
}

/** A compact embedded player: art, song, artist, a play button and a thin progress line. */
@SuppressLint("ClickableViewAccessibility")
class MiniPlayerView(ctx: Context) : View(ctx) {
    private val u = Zune.u(ctx)
    private val prefs = Prefs(ctx)
    var onOpen: (() -> Unit)? = null

    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Zune.light(ctx); textSize = 19 * u; color = Zune.WHITE }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Zune.regular(ctx); textSize = 12 * u; color = Zune.GRAY }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Zune.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * u; color = Zune.WHITE }
    private val art = Paint(Paint.FILTER_BITMAP_FLAG)
    private val box = Paint().apply { color = Zune.DARK }
    private val progress = Paint()
    private val src = android.graphics.Rect()
    private val dst = RectF()

    fun update() = invalidate()

    private val buttonX get() = width - 30 * u

    override fun onDraw(c: Canvas) {
        val access = Player.hasAccess(context)
        val pad = 8 * u
        val side = height - 2 * pad
        val left = 12 * u
        dst.set(left, pad, left + side, pad + side)
        val b = Player.art
        if (b != null && access) {
            val s = minOf(b.width, b.height)
            src.set((b.width - s) / 2, (b.height - s) / 2, (b.width + s) / 2, (b.height + s) / 2)
            c.drawBitmap(b, src, dst, art)
        } else {
            c.drawRect(dst, box)
            Glyphs.play(c, dst.centerX() + u, dst.centerY(), side * 0.3f, fill)
        }

        val music = Player.appLabel(context, prefs.musicPackage)
        val (t, s) = when {
            !access -> "connect $music" to "tap to let the launcher control $music"
            !Player.hasTrack -> music to "tap play to resume"
            else -> Player.title().lowercase() to Player.artist().lowercase()
        }
        val tx = dst.right + 10 * u
        val avail = buttonX - 24 * u - tx
        c.drawText(TextUtils.ellipsize(t, title, avail, TextUtils.TruncateAt.END).toString(), tx, height / 2f - 1 * u, title)
        c.drawText(TextUtils.ellipsize(s, sub, avail, TextUtils.TruncateAt.END).toString(), tx, height / 2f + 15 * u, sub)

        if (access) {
            c.drawCircle(buttonX, height / 2f, 16 * u, ring)
            if (Player.isPlaying) Glyphs.pause(c, buttonX, height / 2f, 12 * u, fill)
            else Glyphs.play(c, buttonX + u, height / 2f, 12 * u, fill)
        }

        val d = Player.duration()
        if (d > 0 && access) {
            progress.color = prefs.accent
            c.drawRect(tx, height - 2 * u, tx + (width - tx - 12 * u) * Player.position() / d, height.toFloat(), progress)
        }
        if (Player.isPlaying) postInvalidateDelayed(1000)
    }

    private var downX = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downX = e.x
            MotionEvent.ACTION_UP -> when {
                !Player.hasAccess(context) -> Player.requestAccess(context)
                downX > buttonX - 30 * u -> Player.toggle(context)
                !Player.hasTrack -> Player.toggle(context)
                else -> onOpen?.invoke()
            }
        }
        return true
    }
}

/** The home screen pans between the menu and quickplay and always settles on one of them. */
class SnapScroller(ctx: Context) : HorizontalScrollView(ctx) {
    private var flung = false

    /** Dragging right on the menu pulls in the Google feed, like on the Pixel Launcher. */
    var feed: FeedClient? = null
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private var feedDrag = false
    private var fx = 0f
    private var fy = 0f

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
    }

    private fun maxScroll() = max(0, (getChildAt(0)?.width ?: 0) - width)

    override fun fling(velocityX: Int) {
        flung = true
        smoothScrollTo(if (velocityX > 0) maxScroll() else 0, 0)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fx = ev.x
                fy = ev.y
                feedDrag = false
            }
            MotionEvent.ACTION_MOVE -> if (startFeedDrag(ev)) return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun startFeedDrag(ev: MotionEvent): Boolean {
        if (feedDrag) return true
        val f = feed ?: return false
        val dx = ev.x - fx
        val dy = ev.y - fy
        if (scrollX == 0 && f.available && dx > slop && dx > abs(dy) * 1.5f) {
            feedDrag = true
            fx = ev.x
            f.startScroll()
            return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) flung = false
        if (feedDrag || (ev.actionMasked == MotionEvent.ACTION_MOVE && startFeedDrag(ev))) {
            val f = feed
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> f?.scroll(((ev.x - fx) / width).coerceIn(0f, 1f))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    f?.endScroll()
                    feedDrag = false
                }
            }
            return true
        }
        val r = super.onTouchEvent(ev)
        if ((ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) && !flung) {
            smoothScrollTo(if (scrollX > maxScroll() / 2) maxScroll() else 0, 0)
        }
        return r
    }

    val onMenu get() = scrollX < maxScroll() / 2

    fun showMenu() = smoothScrollTo(0, 0)
}
