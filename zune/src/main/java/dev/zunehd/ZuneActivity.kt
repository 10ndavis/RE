package dev.zunehd

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Every Zune screen: backdrop, status strip, body, and the drop-down media controls. */
abstract class ZuneActivity : Activity(), Player.Listener {
    protected val u by lazy { Zune.u(this) }
    protected val prefs by lazy { Prefs(this) }
    protected lateinit var backdrop: BackdropView
    protected lateinit var status: StatusBarView
    protected lateinit var quick: QuickControlsView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Player.init(this)
        val root = FrameLayout(this)
        backdrop = BackdropView(this)
        root.addView(backdrop, -1, -1)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = StatusBarView(this)
        column.addView(status, -1, (24 * u).toInt())
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
