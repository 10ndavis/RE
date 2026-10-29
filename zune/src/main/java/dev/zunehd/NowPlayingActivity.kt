package dev.zunehd

import android.app.Activity
import android.os.Bundle

class NowPlayingActivity : Activity(), Player.Listener {
    private lateinit var view: NowPlayingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Player.init(this)
        view = NowPlayingView(this)
        view.onClose = { finish() }
        setContentView(view)
        zuneImmersive(hideNav = true)
    }

    override fun onStart() {
        super.onStart()
        Player.addListener(this)
        view.update()
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
        if (hasFocus) zuneImmersive(hideNav = true)
    }

    override fun onPlayerChanged() = view.update()

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.zune_in, R.anim.zune_down)
    }
}
