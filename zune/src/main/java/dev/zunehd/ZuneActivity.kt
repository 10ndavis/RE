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

/** Every Zune screen: backdrop, status strip, body, and the drop-down media controls. */
abstract class ZuneActivity : Activity(), Player.Listener {
    protected val u by lazy { Zune.u(this) }
    protected val prefs by lazy { Prefs(this) }
    protected lateinit var backdrop: BackdropView
    protected lateinit var status: StatusBarView
    protected lateinit var quick: QuickControlsView

    /** The menu word that led here, shown huge and cropped at the top. Tap it to go back. */
    protected open val section: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Player.init(this)
        val root = FrameLayout(this)
        backdrop = BackdropView(this)
        root.addView(backdrop, -1, -1)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = StatusBarView(this)
        column.addView(status, -1, (24 * u).toInt())
        section?.let { column.addView(sectionTitle(it)) }
        val body = FrameLayout(this)
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(column, -1, -1)
        quick = QuickControlsView(this)
        root.addView(quick, FrameLayout.LayoutParams(-1, quick.heightPx, Gravity.TOP))
        status.onPull = { quick.toggle() }
        setContentView(root)
        zuneImmersive()
        buildBody(body)
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
        zuneImmersive()
    }

    override fun onStop() {
        super.onStop()
        Player.removeListener(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) zuneImmersive()
    }

    override fun onPlayerChanged() {
        backdrop.update()
        status.invalidate()
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
        if (quick.showing) quick.hide() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    fun openNowPlaying() {
        startActivity(Intent(this, NowPlayingActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.zune_up, R.anim.zune_hold)
    }

    fun open(cls: Class<*>) {
        startActivity(Intent(this, cls))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.zune_in, R.anim.zune_hold)
    }
}
