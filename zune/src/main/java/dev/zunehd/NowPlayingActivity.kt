package dev.zunehd

import android.app.Activity
import android.os.Bundle

open class NowPlayingActivity : Activity(), Player.Listener {
    protected lateinit var view: NowPlayingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Player.init(this)
        view = NowPlayingView(this)
        view.onClose = { view.close { finish() } }
        setContentView(view)
        zuneEdgeToEdge()
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
        if (hasFocus) zuneEdgeToEdge()
    }

    override fun onPlayerChanged() = view.update()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = view.close { finish() }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.zune_none, R.anim.zune_none)
    }
}

/**
 * Now playing on the lock screen, like the Zune HD's: the ambient art and giant scrolling text,
 * shown over the keyguard when the screen goes off while music plays. Swipe up to unlock; the
 * controls work without unlocking. It lives in its own task so unlocking returns to whatever
 * app you were using.
 */
class LockScreenActivity : NowPlayingActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        view.lockScreen = true
        view.onClose = { finish() }
        view.onSwipeUp = ::unlock
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        view.lockScreen = true
    }

    private fun unlock() {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            finish()
            return
        }
        km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = finish()
        })
    }

    /** Unlocked any way at all (fingerprint, face, PIN): it has done its job. */
    private val unlocked = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) = finish()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(unlocked, android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT))
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(unlocked) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = finish()

    companion object {
        /** Put now playing on the lock screen if it's on, music is playing and Android allows it. */
        fun showIfPlaying(ctx: android.content.Context) {
            if (!Prefs(ctx).lockScreen || !Player.isPlaying) return
            if (android.os.Build.VERSION.SDK_INT >= 29 && !android.provider.Settings.canDrawOverlays(ctx)) return
            runCatching {
                ctx.startActivity(
                    android.content.Intent(ctx, LockScreenActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }
        }
    }
}
