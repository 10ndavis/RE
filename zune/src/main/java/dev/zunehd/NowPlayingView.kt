package dev.zunehd

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.session.PlaybackState
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

    // Sized and spaced to match the widths measured on the device (Zegoe Bold runs wide).
    private val artistP = text(24f, Typeface.create("sans-serif-black", Typeface.NORMAL)).apply { letterSpacing = 0.085f }
    private val albumP = text(22f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply { letterSpacing = 0.04f }
    private val titleP = text(20.2f, Typeface.create("sans-serif-medium", Typeface.NORMAL))
    private val nextP = text(5.3f, Zune.regular(ctx), 0xFFD8D8D8.toInt())
    private val timeP = text(11f)
    private val giant1 = text(32f)
    private val giant2 = text(116f)

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val m = Matrix()
    private val arrow = android.graphics.Path()
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

    // Layout in phone pixels, measured from the device's screen in Zune pixels (u).
    private val margin = 24 * u
    private val fullArt = RectF()
    private val ambArt = RectF()
    private val artRect = RectF()
    // The back ring is big and hangs off the top-left corner: the screen's top and left edges
    // cut it off, while the arrow inside stays whole.
    private val backC = floatArrayOf(30f * u, 20f * u)
    private val backR = 37 * u
    private var exitStart = 0L
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
        if (!Player.isPlaying) ambientTarget = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fullArt.set(27 * u, 154 * u, 192 * u, 319 * u)
        ambArt.set(60 * u, 44 * u, 60 * u + 130 * u, 174 * u)
    }

    private fun ease(t: Float) = 1 - (1 - t.coerceIn(0f, 1f)).let { it * it * it }

    /**
     * Flip state of item group [i], as (rotation in degrees, opacity): groups swing in on a hinge
     * at the screen's left edge one after another, and swing away the same way on exit.
     */
    private fun flip(now: Long, i: Int): Pair<Float, Float> {
        if (exitStart > 0) {
            val t = ((now - exitStart - i * Motion.STAGGER) / Motion.OUT.toFloat()).coerceIn(0f, 1f)
            val e = t * t
            return Flip.OUT_SIGN * Motion.ANGLE * e to 1 - e
        }
        val t = ((now - enterStart - i * Motion.STAGGER) / Motion.IN.toFloat()).coerceIn(0f, 1f)
        val e = 1 - (1 - t) * (1 - t)
        return Flip.IN_SIGN * Motion.ANGLE * (1 - e) to (t * 2f).coerceAtMost(1f)
    }

    /** Swing everything away, then [then]. */
    fun close(then: () -> Unit) {
        if (exitStart > 0) return
        exitStart = SystemClock.uptimeMillis()
        controlsTarget = 0f
        invalidate()
        postDelayed(then, Motion.OUT + 6 * Motion.STAGGER)
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1000f
        lastFrame = now
        val w = width.toFloat()
        val h = height.toFloat()

        val idle = now - lastTouch
        if (controlsTarget == 1f && idle > 4000 && seekPreview < 0) controlsTarget = 0f
        if (Player.isPlaying && Player.hasTrack && idle > 9000 && controls < 0.01f && seekPreview < 0) ambientTarget = 1f
        // Settling into ambient is slow; tapping back to the full view is near instant.
        ambient += (ambientTarget - ambient) * min(1f, dt * (if (ambientTarget > ambient) 3f else 14f))
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
        val (angle, alpha) = flip(now, 2)
        c.save()
        c.translate(swipe, 0f)
        Flip.apply(c, angle * full, 0f, artRect.centerY())
        drawArt(c, now, (255 * (alpha * full + ambient).coerceAtMost(1f)).toInt())
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
        fun group(i: Int, centerY: Float, draw: () -> Unit) {
            val (angle, a) = flip(now, i)
            val alpha = a * full
            if (alpha <= 0.01f) return
            c.save()
            Flip.apply(c, angle, 0f, centerY)
            c.saveLayerAlpha(0f, 0f, w, height.toFloat(), (255 * alpha).toInt())
            draw()
            c.restore()
            c.restore()
        }

        group(0, backC[1]) { drawBack(c) }
        group(1, 140 * u) {
            // Runs straight off the right edge of the screen, never ellipsized.
            c.drawText(artist.uppercase().ifEmpty { "NOTHING PLAYING" }, margin, 125 * u, artistP)
            c.drawText(album.uppercase(), margin, 151 * u, albumP)
        }
        val d = Player.duration()
        val pos = if (seekPreview >= 0) seekPreview else Player.position()
        group(3, fullArt.bottom + 20 * u) {
            val y = fullArt.bottom
            fill.alpha = 255
            fill.color = 0xFF3A3A3A.toInt()
            c.drawRect(fullArt.left, y, fullArt.right, y + 2 * u, fill)
            if (d > 0) {
                fill.color = Zune.WHITE
                c.drawRect(fullArt.left, y, fullArt.left + fullArt.width() * pos / d, y + 2 * u, fill)
                c.drawText(Format.time(pos), fullArt.left, 338 * u, timeP)
                val rem = "-" + Format.time(d - pos)
                c.drawText(rem, fullArt.right - timeP.measureText(rem), 338 * u, timeP)
            }
            c.drawText(title, 32 * u, 360.6f * u, titleP)
        }
        group(4, 390 * u) {
            upNext.forEachIndexed { i, s ->
                c.drawText(s, 73 * u, (377 + 14 * i) * u, nextP)
            }
        }
        group(5, 432 * u) { drawActions(c, w) }
        if (controls > 0.01f && exitStart == 0L) drawTransport(c)
    }

    /**
     * The Zune back button: a heavy left arrow inside a 74 px ring (6.5 px stroke) that hangs off
     * the top-left corner, so the screen's top and left edges cut the ring while the arrow,
     * nudged toward the visible part, stays whole.
     */
    private fun drawBack(c: Canvas) {
        val cx = backC[0]
        val cy = backC[1]
        ring.color = Zune.WHITE
        ring.strokeWidth = 6.5f * u
        ring.strokeCap = Paint.Cap.BUTT
        c.drawCircle(cx, cy, backR - 3.25f * u, ring)
        // Arrow: a filled head and a thick shaft, centred a little right of and below the ring's centre.
        val ax = cx + 5 * u
        val ay = cy + 4 * u
        val tip = ax - 19 * u
        val headBack = tip + 17 * u
        fill.color = Zune.WHITE
        fill.alpha = 255
        arrow.reset()
        arrow.moveTo(tip, ay)
        arrow.lineTo(headBack, ay - 15 * u)
        arrow.lineTo(headBack, ay + 15 * u)
        arrow.close()
        c.drawPath(arrow, fill)
        c.drawRect(headBack - 1 * u, ay - 4.5f * u, ax + 19 * u, ay + 4.5f * u, fill)
    }

    /** The player's extras as small circled buttons along the bottom. */
    private fun drawActions(c: Canvas, w: Float) {
        actionCenters.clear()
        if (actions.isEmpty()) return
        // Measured: three 34 px circles at x = 55, 150 and 245, 432 px down.
        val y = 432 * u
        val n = actions.size
        actions.forEachIndexed { i, (action, icon) ->
            val x = if (n == 1) 150 * u else (55 + 190f * i / (n - 1)) * u
            actionCenters += floatArrayOf(x, y)
            ring.color = Zune.WHITE
            ring.strokeWidth = 2.6f * u
            c.drawCircle(x, y, 15.7f * u, ring)
            if (icon != null) {
                val r = (8 * u).toInt()
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
                mode = if (!wasAmbient && Player.duration() > 0 && abs(e.y - (fullArt.bottom + 4 * u)) < 14 * u &&
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
            // Back to the full view at once, as on the device.
            ambientTarget = 0f
            enterStart = SystemClock.uptimeMillis() - 10_000
            return
        }
        if (hypot(x - backC[0], y - backC[1]) < backR + 6 * u) {
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
