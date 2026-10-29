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
import android.graphics.drawable.Drawable
import android.media.session.PlaybackState
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Zune HD now playing screen: the artist in bold, the album, the art, the current song and
 * the next three, over slowly panning imagery with the artist's name drifting behind. Shuffle,
 * repeat and rating (the player's own extras, e.g. Tidal's favourite) sit on the page; tapping
 * the middle brings up the circled previous / play / next and the scrubber, which fade away again
 * on their own. Swipe sideways to change songs and pull down to go back.
 */
@SuppressLint("ClickableViewAccessibility")
class NowPlayingView(ctx: Context) : View(ctx) {
    var onClose: (() -> Unit)? = null

    private val u = Zune.u(ctx)
    private val prefs = Prefs(ctx)
    private var accent = prefs.accent
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val giant = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Zune.light(ctx); textSize = 168 * u; color = Zune.WHITE }
    private val artistP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(Zune.regular(ctx), android.graphics.Typeface.BOLD)
        textSize = 25 * u
        color = Zune.WHITE
    }
    private val albumP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Zune.regular(ctx); textSize = 15 * u; color = Zune.GRAY }
    private val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Zune.light(ctx); textSize = 23 * u; color = Zune.WHITE }
    private val nextP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Zune.light(ctx); textSize = 16 * u; color = Zune.GRAY }
    private val smallP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Zune.regular(ctx); textSize = 12 * u; color = Zune.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * u }
    private val m = Matrix()
    private val src = Rect()

    private var art: Bitmap? = null
    private var oldArt: Bitmap? = null
    private var blur: Bitmap? = null
    private var artChangedAt = 0L
    private var title = ""
    private var artist = ""
    private var album = ""
    private var trackStart = SystemClock.uptimeMillis()
    private var actions: List<Pair<PlaybackState.CustomAction, Drawable?>> = emptyList()
    private var playerLabel = ""
    private var upNext: List<String> = emptyList()

    private var controls = 0f
    private var controlsTarget = 0f
    private var lastTouch = 0L
    private var lastFrame = 0L
    private var swipe = 0f
    private var swipeAnim: ValueAnimator? = null
    private var pull = 0f
    private var seekPreview = -1L

    // Layout, in phone pixels.
    private val margin = 16 * u
    private val artRect = RectF()
    private var scrubY = 0f
    private var buttonsY = 0f
    private var titleY = 0f
    private var actionsY = 0f
    private val actionRects = ArrayList<RectF>()

    fun update() {
        accent = prefs.accent
        val t = Player.title()
        val a = Player.artist()
        if (t != title || a != artist) {
            title = t
            artist = a
            trackStart = SystemClock.uptimeMillis()
        }
        album = Player.album()
        val newArt = Player.art
        if (newArt !== art) {
            oldArt = art
            art = newArt
            artChangedAt = SystemClock.uptimeMillis()
        }
        blur = Player.blurredArt
        actions = Player.customActions().map { it to Player.customIcon(context, it)?.mutate()?.apply { setTint(Zune.WHITE) } }
        playerLabel = Player.appLabel(context, Player.playerPackage())
        upNext = Player.upNext(3)
        if (!Player.hasTrack) controlsTarget = 1f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val top = 76 * u
        val side = min(w - 2 * margin, h - top - 190 * u)
        artRect.set(margin, top, margin + side, top + side)
        titleY = artRect.bottom + 30 * u
        actionsY = h - 34 * u
        scrubY = artRect.bottom + 12 * u
        buttonsY = artRect.centerY()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1000f
        lastFrame = now
        val w = width.toFloat()
        val h = height.toFloat()
        val t = (now - trackStart) / 1000f

        if (controlsTarget == 1f && Player.isPlaying && Player.hasTrack && now - lastTouch > 5000 && seekPreview < 0) controlsTarget = 0f
        controls += (controlsTarget - controls) * min(1f, dt * 9f)
        if (abs(controlsTarget - controls) < 0.01f) controls = controlsTarget

        c.save()
        c.translate(0f, pull)
        drawBackdrop(c, w, h, now / 1000f)

        // The artist's name, huge and drifting right to left behind everything.
        val name = (artist.ifEmpty { title.ifEmpty { "zune" } }).lowercase()
        val tw = giant.measureText(name)
        val cycle = tw + w * 0.6f
        val x = w * 0.35f - ((t * 22 * u) % cycle)
        giant.alpha = 0x30
        val gy = h - 70 * u
        c.drawText(name, x, gy, giant)
        if (x + tw < w) c.drawText(name, x + cycle, gy, giant)

        // Artist (bold) and album above the art.
        val avail = w - 2 * margin
        smallP.alpha = 200
        c.drawText(playerLabel, margin, 22 * u, smallP)
        c.drawText(ellipsize(artist.lowercase().ifEmpty { "nothing playing" }, artistP, avail), margin, 50 * u, artistP)
        c.drawText(ellipsize(album.lowercase(), albumP, avail), margin, 68 * u, albumP)

        drawArt(c, now)

        // A thin progress line along the bottom of the art.
        val d = Player.duration()
        val pos = if (seekPreview >= 0) seekPreview else Player.position()
        if (d > 0) {
            fill.color = accent
            fill.alpha = (255 * (1 - controls)).toInt()
            c.drawRect(artRect.left, artRect.bottom + 3 * u, artRect.left + artRect.width() * pos / d, artRect.bottom + 5 * u, fill)
        }

        // The current song, then the next three.
        c.drawText(ellipsize(title.lowercase(), titleP, avail), margin + swipe * 0.15f, titleY, titleP)
        upNext.forEachIndexed { i, s ->
            c.drawText(ellipsize(s.lowercase(), nextP, avail), margin, titleY + (24 + 21 * i) * u, nextP)
        }

        drawActions(c, w)
        if (controls > 0.01f) drawControls(c, w, h, pos, d)
        c.restore()
        if (isShown) postInvalidateOnAnimation()
    }

    /** The player's own buttons (Tidal: favourite, shuffle, repeat...) along the bottom. */
    private fun drawActions(c: Canvas, w: Float) {
        actionRects.clear()
        val size = 26 * u
        val shown = actions.take(5)
        if (shown.isEmpty()) return
        val step = (w - 2 * margin) / shown.size
        shown.forEachIndexed { i, (action, icon) ->
            val cx = margin + step * (i + 0.5f)
            val rect = RectF(cx - size / 2, actionsY - size / 2, cx + size / 2, actionsY + size / 2)
            actionRects.add(rect)
            if (icon != null) {
                icon.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
                icon.alpha = 230
                icon.draw(c)
            } else {
                smallP.alpha = 230
                val label = action.name.toString().lowercase()
                c.drawText(label, cx - smallP.measureText(label) / 2, actionsY + 4 * u, smallP)
            }
        }
    }

    private fun drawBackdrop(c: Canvas, w: Float, h: Float, seconds: Float) {
        val b = blur
        if (b == null) {
            Backdrop.draw(c, w, h, 0x88)
            return
        }
        // Ken Burns: overscale and drift slowly along a loose ellipse.
        val s = max(w / b.width, h / b.height) * 1.3f
        val ex = b.width * s - w
        val ey = b.height * s - h
        m.setScale(s, s)
        m.postTranslate(-ex / 2 + sin(seconds * 0.11f) * ex / 2, -ey / 2 + cos(seconds * 0.07f) * ey / 2)
        bmp.alpha = 255
        c.drawBitmap(b, m, bmp)
        c.drawColor(0x8C000000.toInt())
    }

    private fun drawArt(c: Canvas, now: Long) {
        val f = ((now - artChangedAt) / 450f).coerceIn(0f, 1f)
        c.save()
        c.translate(swipe, 0f)
        val old = oldArt
        if (old != null && f < 1f) drawSquare(c, old, (255 * (1 - f)).toInt())
        val a = art
        if (a != null) {
            drawSquare(c, a, (255 * f).toInt())
        } else {
            fill.color = Zune.DARK
            fill.alpha = 200
            c.drawRect(artRect, fill)
            fill.color = Zune.WHITE
            fill.alpha = 60
            Glyphs.play(c, artRect.centerX() + 6 * u, artRect.centerY(), 70 * u, fill)
        }
        c.restore()
    }

    private fun drawSquare(c: Canvas, b: Bitmap, alpha: Int) {
        val s = min(b.width, b.height)
        src.set((b.width - s) / 2, (b.height - s) / 2, (b.width + s) / 2, (b.height + s) / 2)
        bmp.alpha = alpha
        c.drawBitmap(b, src, artRect, bmp)
    }

    private fun drawControls(c: Canvas, w: Float, h: Float, pos: Long, d: Long) {
        val a = (255 * controls).toInt()
        fill.color = 0xFF000000.toInt()
        fill.alpha = (140 * controls).toInt()
        c.drawRect(artRect.left, artRect.top, artRect.right, artRect.bottom, fill)

        // Scrubber with elapsed and remaining time.
        val l = margin
        val r = w - margin
        fill.color = 0xFF555555.toInt()
        fill.alpha = a
        c.drawRect(l, scrubY - 1.5f * u, r, scrubY + 1.5f * u, fill)
        if (d > 0) {
            val px = l + (r - l) * pos / d
            fill.color = accent
            fill.alpha = a
            c.drawRect(l, scrubY - 1.5f * u, px, scrubY + 1.5f * u, fill)
            fill.color = Zune.WHITE
            fill.alpha = a
            c.drawCircle(px, scrubY, (if (seekPreview >= 0) 9 else 6) * u, fill)
            smallP.alpha = a
            c.drawText(Format.time(pos), l, scrubY + 16 * u, smallP)
            val rem = "-" + Format.time(d - pos)
            c.drawText(rem, r - smallP.measureText(rem), scrubY + 16 * u, smallP)
        }

        // previous, play/pause, next.
        val cx = w / 2
        ring.color = Zune.WHITE
        ring.alpha = a
        fill.color = Zune.WHITE
        fill.alpha = a
        c.drawCircle(cx - 74 * u, buttonsY, 23 * u, ring)
        Glyphs.skip(c, cx - 74 * u, buttonsY, 17 * u, fill, forward = false)
        c.drawCircle(cx, buttonsY, 32 * u, ring)
        if (Player.isPlaying) Glyphs.pause(c, cx, buttonsY, 24 * u, fill) else Glyphs.play(c, cx + 2 * u, buttonsY, 24 * u, fill)
        c.drawCircle(cx + 74 * u, buttonsY, 23 * u, ring)
        Glyphs.skip(c, cx + 74 * u, buttonsY, 17 * u, fill, forward = true)

    }

    private fun ellipsize(s: String, p: TextPaint, avail: Float) = TextUtils.ellipsize(s, p, avail, TextUtils.TruncateAt.END).toString()

    // ---- touch ----

    private enum class Mode { NONE, SEEK, SWIPE, PULL }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f

    private fun seekAt(x: Float): Long {
        val d = Player.duration()
        return (((x - margin) / (width - 2 * margin)).coerceIn(0f, 1f) * d).toLong()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        lastTouch = SystemClock.uptimeMillis()
        val dx = e.x - downX
        val dy = e.y - downY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                swipeAnim?.cancel()
                mode = if (controls > 0.5f && Player.duration() > 0 && abs(e.y - scrubY) < 22 * u) Mode.SEEK else Mode.NONE
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
        swipeAnim?.cancel()
        val fromSwipe = swipe
        val fromPull = pull
        swipeAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 320
            interpolator = OvershootInterpolator(1.2f)
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
        val i = actionRects.indexOfFirst { RectF(it).apply { inset(-12 * u, -12 * u) }.contains(x, y) }
        if (i >= 0 && i < actions.size) {
            Player.custom(actions[i].first)
            return
        }
        if (controls < 0.5f) {
            controlsTarget = 1f
            return
        }
        val cx = width / 2f
        fun hit(px: Float, py: Float, r: Float) = abs(x - px) < r && abs(y - py) < r
        when {
            hit(cx, buttonsY, 40 * u) -> Player.toggle(context)
            hit(cx - 74 * u, buttonsY, 32 * u) -> Player.previous()
            hit(cx + 74 * u, buttonsY, 32 * u) -> Player.next()
            Player.hasTrack -> controlsTarget = 0f
        }
    }
}
