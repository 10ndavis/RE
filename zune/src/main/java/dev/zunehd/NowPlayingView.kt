package dev.zunehd

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.SystemClock
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The Zune HD now playing screen, laid out from footage of the device.
 *
 * Full mode: a circled back arrow, the artist and album in bold capitals (cut off at the edge,
 * not ellipsized), the art with the album's name running up its spine, elapsed and remaining
 * time, the song, the next three songs, and the player's extras (Tidal's shuffle, repeat,
 * favourite...) as small circled buttons. Tap the art for previous / play / next.
 *
 * Left alone while playing, it settles into the ambient view: just the art, larger, with the
 * album and the artist in enormous bold capitals scrolling slowly to the right across it. Any
 * tap brings the full view back, sliding in from the left.
 */
@SuppressLint("ClickableViewAccessibility")
class NowPlayingView(ctx: Context) : View(ctx) {
    var onClose: (() -> Unit)? = null

    private val u = Zune.u(ctx)
    private val prefs = Prefs(ctx)
    private var accent = prefs.accent
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val bold = Typeface.create(Zune.regular(ctx), Typeface.BOLD)

    private fun text(size: Float, face: Typeface = bold, color: Int = Zune.WHITE) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size * u; this.color = color }

    private val headP = text(17f)
    private val titleP = text(14.5f)
    private val nextP = text(7.5f, Zune.regular(ctx), 0xFFB0B0B0.toInt())
    private val timeP = text(7.5f)
    private val spineP = text(6f, Zune.regular(ctx), 0xFF9A9A9A.toInt())
    private val giant1 = text(32f)
    private val giant2 = text(116f)

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val m = Matrix()
    private val src = Rect()

    private var art: Bitmap? = null
    private var oldArt: Bitmap? = null
    private var blur: Bitmap? = null
    private var artChangedAt = 0L
    private var title = ""
    private var artist = ""
    private var album = ""
    private var upNext: List<String> = emptyList()
    private var actions: List<Pair<PlaybackState.CustomAction, Drawable?>> = emptyList()
    private var battery = -1

    private val born = SystemClock.uptimeMillis()
    private var enterStart = born
    private var lastTouch = born
    private var lastFrame = 0L
    private var ambient = 0f
    private var ambientTarget = 0f
    private var controls = 0f
    private var controlsTarget = 0f
    private var swipe = 0f
    private var pull = 0f
    private var settleAnim: ValueAnimator? = null
    private var seekPreview = -1L

    // Layout in phone pixels.
    private val margin = 30 * u
    private val fullArt = RectF()
    private val ambArt = RectF()
    private val artRect = RectF()
    private val backC = floatArrayOf(36 * u, 38 * u)
    private val actionCenters = ArrayList<FloatArray>()

    fun update() {
        accent = prefs.accent
        title = Player.title()
        artist = Player.artist()
        album = Player.album()
        val newArt = Player.art
        if (newArt !== art) {
            oldArt = art
            art = newArt
            artChangedAt = SystemClock.uptimeMillis()
        }
        blur = Player.blurredArt
        upNext = Player.upNext(3)
        actions = Player.customActions().take(4).map { it to Player.customIcon(context, it)?.mutate()?.apply { setTint(Zune.WHITE) } }
        battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
            it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / max(1, it.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
        } ?: -1
        if (!Player.isPlaying) ambientTarget = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fullArt.set(margin, 114 * u, margin + 128 * u, 242 * u)
        ambArt.set(60 * u, 44 * u, 60 * u + 130 * u, 174 * u)
    }

    private fun ease(t: Float) = 1 - (1 - t.coerceIn(0f, 1f)).let { it * it * it }

    /** Entrance progress of item group [i]: groups slide in from the left one after another. */
    private fun enter(now: Long, i: Int) = ease((now - enterStart - i * 45L) / 320f)

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1000f
        lastFrame = now
        val w = width.toFloat()
        val h = height.toFloat()

        val idle = now - lastTouch
        if (controlsTarget == 1f && idle > 4000 && seekPreview < 0) controlsTarget = 0f
        if (Player.isPlaying && Player.hasTrack && idle > 9000 && controls < 0.01f && seekPreview < 0) ambientTarget = 1f
        ambient += (ambientTarget - ambient) * min(1f, dt * 4f)
        if (abs(ambientTarget - ambient) < 0.005f) ambient = ambientTarget
        controls += (controlsTarget - controls) * min(1f, dt * 10f)
        if (abs(controlsTarget - controls) < 0.01f) controls = controlsTarget

        c.save()
        c.translate(0f, pull)
        drawBackground(c, w, h, now / 1000f)

        val a = ease(ambient)
        artRect.set(
            fullArt.left + (ambArt.left - fullArt.left) * a,
            fullArt.top + (ambArt.top - fullArt.top) * a,
            fullArt.right + (ambArt.right - fullArt.right) * a,
            fullArt.bottom + (ambArt.bottom - fullArt.bottom) * a,
        )
        val full = 1 - ambient
        val e2 = enter(now, 2)
        c.save()
        c.translate(swipe - (1 - e2) * 50 * u * full, 0f)
        drawArt(c, now, (255 * (e2 * full + ambient).coerceAtMost(1f)).toInt())
        c.restore()

        if (ambient > 0.01f) drawAmbient(c, w, (now - born) / 1000f)
        if (full > 0.01f) drawFull(c, w, now, full)
        c.restore()
        if (isShown) postInvalidateOnAnimation()
    }

    private fun drawBackground(c: Canvas, w: Float, h: Float, seconds: Float) {
        val b = blur
        if (prefs.background != "art" || b == null) {
            c.drawColor(0xFF000000.toInt())
            return
        }
        val s = max(w / b.width, h / b.height) * 1.3f
        val ex = b.width * s - w
        val ey = b.height * s - h
        m.setScale(s, s)
        m.postTranslate(-ex / 2 + kotlin.math.sin(seconds * 0.11f) * ex / 2, -ey / 2 + kotlin.math.cos(seconds * 0.07f) * ey / 2)
        bmp.alpha = 255
        c.drawBitmap(b, m, bmp)
        c.drawColor(0xA0000000.toInt())
    }

    private fun drawArt(c: Canvas, now: Long, alpha: Int) {
        val f = ((now - artChangedAt) / 400f).coerceIn(0f, 1f)
        val old = oldArt
        if (old != null && f < 1f) drawSquare(c, old, (alpha * (1 - f)).toInt())
        val b = art
        if (b != null) {
            drawSquare(c, b, (alpha * f).toInt())
        } else {
            fill.color = Zune.DARK
            fill.alpha = alpha
            c.drawRect(artRect, fill)
        }
        // The album's name running up the spine, along the art's left edge.
        if (album.isNotEmpty()) {
            c.save()
            c.rotate(-90f, artRect.left - 3 * u, artRect.bottom)
            spineP.alpha = alpha
            c.drawText(clip(album.uppercase(), spineP, artRect.height()), artRect.left - 3 * u, artRect.bottom, spineP)
            c.restore()
        }
    }

    private fun drawSquare(c: Canvas, b: Bitmap, alpha: Int) {
        val s = min(b.width, b.height)
        src.set((b.width - s) / 2, (b.height - s) / 2, (b.width + s) / 2, (b.height + s) / 2)
        bmp.alpha = alpha
        c.drawBitmap(b, src, artRect, bmp)
    }

    /** Album and artist in giant capitals, scrolling slowly rightwards over the art. */
    private fun drawAmbient(c: Canvas, w: Float, seconds: Float) {
        val alpha = (255 * ambient).toInt()
        val y1 = ambArt.bottom + 6 * u
        scroll(c, album.ifEmpty { title }.uppercase(), giant1, y1, 16 * u, seconds, w, alpha)
        scroll(c, artist.ifEmpty { "ZUNE" }.uppercase(), giant2, y1 + 92 * u, 11 * u, seconds, w, alpha)
    }

    private fun scroll(c: Canvas, s: String, p: TextPaint, y: Float, speed: Float, seconds: Float, w: Float, alpha: Int) {
        if (s.isEmpty()) return
        val cycle = p.measureText(s) + 40 * u
        var x = (seconds * speed) % cycle - cycle + w * 0.15f
        p.alpha = alpha
        while (x < w) {
            c.drawText(s, x, y, p)
            x += cycle
        }
    }

    private fun drawFull(c: Canvas, w: Float, now: Long, full: Float) {
        fun group(i: Int, draw: () -> Unit) {
            val e = enter(now, i)
            val alpha = e * full
            if (alpha <= 0.01f) return
            c.save()
            c.translate(-(1 - e) * 50 * u, 0f)
            c.saveLayerAlpha(0f, 0f, w, height.toFloat(), (255 * alpha).toInt())
            draw()
            c.restore()
            c.restore()
        }

        group(0) {
            drawBack(c)
            drawStatus(c, w)
        }
        group(1) {
            c.save()
            c.clipRect(0f, 0f, w - 8 * u, height.toFloat())
            c.drawText(artist.uppercase().ifEmpty { "NOTHING PLAYING" }, margin, 90 * u, headP)
            c.drawText(album.uppercase(), margin, 107 * u, headP)
            c.restore()
        }
        val d = Player.duration()
        val pos = if (seekPreview >= 0) seekPreview else Player.position()
        group(3) {
            if (d > 0) {
                fill.color = 0xFF555555.toInt()
                fill.alpha = 255
                val y = artRect.bottom + 4 * u
                c.drawRect(artRect.left, y, artRect.right, y + 1.6f * u, fill)
                fill.color = accent
                c.drawRect(artRect.left, y, artRect.left + artRect.width() * pos / d, y + 1.6f * u, fill)
                c.drawText(Format.time(pos), artRect.left, artRect.bottom + 14 * u, timeP)
                val rem = "-" + Format.time(d - pos)
                c.drawText(rem, artRect.right - timeP.measureText(rem), artRect.bottom + 14 * u, timeP)
            }
            c.drawText(clip(title, titleP, w - margin - 8 * u), margin, fullArt.bottom + 32 * u, titleP)
        }
        group(4) {
            upNext.forEachIndexed { i, s ->
                c.drawText(clip(s, nextP, w - margin - 8 * u), margin, fullArt.bottom + (44 + 10 * i) * u, nextP)
            }
        }
        group(5) { drawActions(c, w) }
        if (controls > 0.01f) drawTransport(c)
    }

    private fun drawBack(c: Canvas) {
        val (cx, cy) = backC.let { it[0] to it[1] }
        ring.color = Zune.WHITE
        ring.strokeWidth = 3.2f * u
        ring.strokeCap = Paint.Cap.ROUND
        c.drawCircle(cx, cy, 20 * u, ring)
        c.drawLine(cx + 10 * u, cy, cx - 9 * u, cy, ring)
        c.drawLine(cx - 9.5f * u, cy, cx - 2 * u, cy - 7.5f * u, ring)
        c.drawLine(cx - 9.5f * u, cy, cx - 2 * u, cy + 7.5f * u, ring)
    }

    private fun drawStatus(c: Canvas, w: Float) {
        val cy = 22 * u
        val right = w - 14 * u
        ring.strokeWidth = 1.1f * u
        c.drawRect(right - 14 * u, cy - 3.5f * u, right - 1.5f * u, cy + 3.5f * u, ring)
        fill.color = Zune.WHITE
        fill.alpha = 255
        c.drawRect(right - 1.5f * u, cy - 1.8f * u, right, cy + 1.8f * u, fill)
        if (battery >= 0) c.drawRect(right - 12.5f * u, cy - 2f * u, right - 12.5f * u + 9.5f * u * battery / 100f, cy + 2f * u, fill)
        if (Player.isPlaying) Glyphs.play(c, right - 22 * u, cy, 6 * u, fill)
    }

    /** The player's extras as small circled buttons along the bottom. */
    private fun drawActions(c: Canvas, w: Float) {
        actionCenters.clear()
        if (actions.isEmpty()) return
        val y = fullArt.bottom + 104 * u
        val n = actions.size
        actions.forEachIndexed { i, (action, icon) ->
            val x = if (n == 1) w / 2 else w * (0.2f + 0.62f * i / (n - 1))
            actionCenters += floatArrayOf(x, y)
            ring.color = Zune.WHITE
            ring.strokeWidth = 1.8f * u
            c.drawCircle(x, y, 11 * u, ring)
            if (icon != null) {
                val r = (6.5f * u).toInt()
                icon.setBounds((x - r).toInt(), (y - r).toInt(), (x + r).toInt(), (y + r).toInt())
                icon.alpha = 255
                icon.draw(c)
            } else {
                val label = action.name.toString().take(1).uppercase()
                c.drawText(label, x - timeP.measureText(label) / 2, y + 3 * u, timeP)
            }
        }
    }

    /** Previous / play / next over the art, after tapping it. */
    private fun drawTransport(c: Canvas) {
        val a = (255 * controls).toInt()
        fill.color = 0xFF000000.toInt()
        fill.alpha = (160 * controls).toInt()
        c.drawRect(artRect, fill)
        val cx = artRect.centerX()
        val cy = artRect.centerY()
        ring.color = Zune.WHITE
        ring.alpha = a
        ring.strokeWidth = 2 * u
        fill.color = Zune.WHITE
        fill.alpha = a
        c.drawCircle(cx - 40 * u, cy, 13 * u, ring)
        Glyphs.skip(c, cx - 40 * u, cy, 10 * u, fill, forward = false)
        c.drawCircle(cx, cy, 17 * u, ring)
        if (Player.isPlaying) Glyphs.pause(c, cx, cy, 13 * u, fill) else Glyphs.play(c, cx + 1.5f * u, cy, 13 * u, fill)
        c.drawCircle(cx + 40 * u, cy, 13 * u, ring)
        Glyphs.skip(c, cx + 40 * u, cy, 10 * u, fill, forward = true)
        ring.alpha = 255
    }

    /** Zune text is cut off at the edge rather than given an ellipsis. */
    private fun clip(s: String, p: TextPaint, avail: Float): String {
        val n = p.breakText(s, true, avail, null)
        return s.substring(0, n)
    }

    // ---- touch ----

    private enum class Mode { NONE, SEEK, SWIPE, PULL }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var wasAmbient = false

    private fun seekAt(x: Float): Long =
        (((x - fullArt.left) / fullArt.width()).coerceIn(0f, 1f) * Player.duration()).toLong()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        lastTouch = SystemClock.uptimeMillis()
        val dx = e.x - downX
        val dy = e.y - downY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                settleAnim?.cancel()
                wasAmbient = ambient > 0.5f
                mode = if (!wasAmbient && Player.duration() > 0 && abs(e.y - (fullArt.bottom + 6 * u)) < 12 * u &&
                    e.x in fullArt.left - 10 * u..fullArt.right + 10 * u
                ) Mode.SEEK else Mode.NONE
                if (mode == Mode.SEEK) seekPreview = seekAt(e.x)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE && (abs(dx) > slop || abs(dy) > slop)) {
                    mode = if (abs(dx) > abs(dy)) Mode.SWIPE else if (dy > 0) Mode.PULL else Mode.NONE
                }
                when (mode) {
                    Mode.SEEK -> seekPreview = seekAt(e.x)
                    Mode.SWIPE -> swipe = dx
                    Mode.PULL -> pull = max(0f, dy) * 0.5f
                    Mode.NONE -> {}
                }
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.SEEK -> {
                        Player.seekTo(seekPreview)
                        seekPreview = -1
                    }
                    Mode.SWIPE -> {
                        if (dx < -70 * u) Player.next() else if (dx > 70 * u) Player.previous()
                        settle()
                    }
                    Mode.PULL -> if (dy > 120 * u) onClose?.invoke() else settle()
                    Mode.NONE -> tap(e.x, e.y)
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                seekPreview = -1
                mode = Mode.NONE
                settle()
            }
        }
        invalidate()
        return true
    }

    private fun settle() {
        settleAnim?.cancel()
        val fromSwipe = swipe
        val fromPull = pull
        settleAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 300
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                swipe = fromSwipe * f
                pull = fromPull * f
                invalidate()
            }
            start()
        }
    }

    private fun tap(x: Float, y: Float) {
        if (wasAmbient) {
            // Back to the full view, which slides in again.
            ambientTarget = 0f
            enterStart = SystemClock.uptimeMillis()
            return
        }
        if (hypot(x - backC[0], y - backC[1]) < 30 * u) {
            onClose?.invoke()
            return
        }
        actionCenters.forEachIndexed { i, (cx, cy) ->
            if (hypot(x - cx, y - cy) < 20 * u) {
                Player.custom(actions[i].first)
                return
            }
        }
        if (fullArt.contains(x, y)) {
            if (controls < 0.5f) {
                controlsTarget = 1f
                return
            }
            val cx = fullArt.centerX()
            when {
                abs(x - cx) < 20 * u -> Player.toggle(context)
                abs(x - (cx - 40 * u)) < 20 * u -> Player.previous()
                abs(x - (cx + 40 * u)) < 20 * u -> Player.next()
                else -> controlsTarget = 0f
            }
            return
        }
        controlsTarget = 0f
    }
}
