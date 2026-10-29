package dev.zunehd

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.View

/** Every Zune screen: backdrop, body, and the media controls the volume keys drop down. */
abstract class ZuneActivity : Activity(), Player.Listener {
    protected val u by lazy { Zune.u(this) }
    protected val prefs by lazy { Prefs(this) }
    protected lateinit var backdrop: BackdropView
    protected lateinit var quick: QuickControlsView

    /** The menu word that led here, shown huge and cropped at the top. Tap it to go back. */
    protected open val section: String? = null

    private var titleView: View? = null
    protected lateinit var bodyView: FrameLayout
    private var flippedOut = false
    private var flipping = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Player.init(this)
        val root = FrameLayout(this)
        backdrop = BackdropView(this)
        root.addView(backdrop, -1, -1)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        section?.let { column.addView(sectionTitle(it).also { t -> titleView = t }) }
        val body = FrameLayout(this)
        bodyView = body
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(column, -1, -1)
        // Content runs under the transparent system bars; only the bottom keeps clear of the
        // navigation bar so the last rows can be reached.
        root.setOnApplyWindowInsetsListener { v, insets ->
            column.setPadding(0, 0, 0, column.systemBarInset(top = false))
            insets
        }
        quick = QuickControlsView(this)
        root.addView(quick, FrameLayout.LayoutParams(-1, quick.heightPx, Gravity.TOP))
        setContentView(root)
        zuneEdgeToEdge()
        buildBody(body)
        body.post { Motion.enter(flipViews(), u) }
    }

    /**
     * What flips when this screen comes and goes, top to bottom: the cropped title, the pivot
     * headers, then the rows of the current page.
     */
    protected open fun flipViews(): List<View> {
        val out = ArrayList<View>()
        titleView?.let { out += it }
        val pivot = find(bodyView, PivotView::class.java)
        if (pivot == null) {
            for (i in 0 until bodyView.childCount) out += bodyView.getChildAt(i)
            return out
        }
        out += pivot.headerViews()
        val list = pivot.currentPage()?.let { find(it, android.widget.ListView::class.java) }
        if (list != null) for (i in 0 until list.childCount) out += list.getChildAt(i)
        return out
    }

    private fun <T> find(v: View, c: Class<T>): T? {
        if (c.isInstance(v)) return c.cast(v)
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), c)?.let { return it }
        return null
    }

    /** Flip this screen's items away, then [then]. */
    protected fun flipOut(then: () -> Unit) {
        if (flipping) return
        flipping = true
        Motion.exit(flipViews(), u) {
            flipping = false
            then()
        }
    }

    override fun onRestart() {
        super.onRestart()
        if (flippedOut) {
            flippedOut = false
            Motion.enter(flipViews(), u)
        }
    }

    private fun sectionTitle(name: String): View {
        val title = zText(name, Section.MENU_SIZE * Section.SCALE).apply {
            ellipsize = null
            setHorizontallyScrolling(true)
            setPadding((12 * u * Section.SCALE).toInt(), (u * Section.SCALE).toInt(), 0, 0)
            translationY = -Section.CROP * u
        }
        return FrameLayout(this).apply {
            clipChildren = true
            val m = title.paint.fontMetrics
            // The title keeps its full height; only this frame crops it.
            addView(title, FrameLayout.LayoutParams(-1, (m.descent - m.ascent + 2 * u * Section.SCALE).toInt()))
            // Show from the crop line down to just under the baseline: only the top is cut off.
            val fm = title.paint.fontMetrics
            layoutParams = LinearLayout.LayoutParams(-1, (-fm.ascent + fm.descent * 0.35f + u * Section.SCALE - Section.CROP * u).toInt())
            setOnClickListener {
                @Suppress("DEPRECATION")
                onBackPressed()
            }
        }
    }

    protected abstract fun buildBody(body: FrameLayout)

    override fun onStart() {
        super.onStart()
        Player.addListener(this)
        onPlayerChanged()
    }

    override fun onResume() {
        super.onResume()
        Player.connect()
    }

    override fun onStop() {
        super.onStop()
        Player.removeListener(this)
    }


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) zuneEdgeToEdge()
    }

    override fun onPlayerChanged() {
        backdrop.update()
        quick.update()
    }

    /** The volume keys bring up the Zune's own controls instead of the system slider. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
            if (!quick.showing) quick.show() else quick.scheduleHide()
            quick.update()
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (quick.showing) {
            quick.hide()
            return
        }
        flipOut {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.zune_none, R.anim.zune_none)
    }

    /** Go forward: flip this screen out (unless it already has), then start [intent]. */
    fun go(intent: Intent, alreadyOut: Boolean = false) {
        val start = {
            flippedOut = true
            startActivity(intent)
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.zune_none, R.anim.zune_none)
        }
        if (alreadyOut) start() else flipOut(start)
    }

    fun openNowPlaying() = go(Intent(this, NowPlayingActivity::class.java))

    fun open(cls: Class<*>, alreadyOut: Boolean = false) = go(Intent(this, cls), alreadyOut)
}
