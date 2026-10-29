package dev.zunehd

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.view.WindowManager

/**
 * The Google Discover feed, as the Pixel Launcher shows it. The feed isn't a view a launcher
 * can embed: the Google app binds to the launcher's window through its "launcher overlay"
 * service and draws the feed itself, sliding in from the left. This speaks that service's
 * binder protocol (the one Launcher3 and Lawnchair use) by hand.
 *
 * The Google app only serves launchers it trusts. If it doesn't accept this one,
 * [available] stays false and callers fall back to opening the Google app.
 */
class FeedClient(private val activity: Activity, private val onScrollChanged: (Float) -> Unit) {
    companion object {
        const val GOOGLE = "com.google.android.googlequicksearchbox"
        private const val OVERLAY = "com.google.android.libraries.launcherclient.ILauncherOverlay"
        private const val CALLBACK = "com.google.android.libraries.launcherclient.ILauncherOverlayCallback"

        // ILauncherOverlay transaction codes, in declaration order.
        private const val START_SCROLL = IBinder.FIRST_CALL_TRANSACTION
        private const val ON_SCROLL = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val END_SCROLL = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val WINDOW_ATTACHED = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val WINDOW_DETACHED = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val CLOSE_OVERLAY = IBinder.FIRST_CALL_TRANSACTION + 5
        private const val ON_PAUSE = IBinder.FIRST_CALL_TRANSACTION + 6
        private const val ON_RESUME = IBinder.FIRST_CALL_TRANSACTION + 7
        private const val OPEN_OVERLAY = IBinder.FIRST_CALL_TRANSACTION + 8
        private const val WINDOW_ATTACHED_2 = IBinder.FIRST_CALL_TRANSACTION + 13

        // ILauncherOverlayCallback.
        private const val SCROLL_CHANGED = IBinder.FIRST_CALL_TRANSACTION
        private const val STATUS_CHANGED = IBinder.FIRST_CALL_TRANSACTION + 1

        private const val CLIENT_OPTIONS = 1 // feed enabled
        private const val ANIMATE = 1

        fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(GOOGLE, 0) }.isSuccess
    }

    private var overlay: IBinder? = null
    private var bound = false
    private var resumed = false

    /** The Google app accepted the window and has a feed to show. */
    var available = false
        private set

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                SCROLL_CHANGED -> {
                    data.enforceInterface(CALLBACK)
                    val p = data.readFloat()
                    activity.runOnUiThread { onScrollChanged(p) }
                    return true
                }
                STATUS_CHANGED -> {
                    data.enforceInterface(CALLBACK)
                    val status = data.readInt()
                    activity.runOnUiThread { available = status and 1 != 0 }
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            overlay = service
            attach()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            overlay = null
            available = false
        }
    }

    private fun serviceIntent(): Intent {
        val pkg = activity.packageName
        val uri = Uri.parse("app://$pkg:${Process.myUid()}").buildUpon()
            .appendQueryParameter("v", "7")
            .appendQueryParameter("cv", "9")
            .build()
        return Intent("com.android.launcher3.WINDOW_OVERLAY").setPackage(GOOGLE).setData(uri)
    }

    private fun serverVersion(): Int = runCatching {
        activity.packageManager.queryIntentServices(serviceIntent(), PackageManager.GET_META_DATA)
            .firstOrNull()?.serviceInfo?.metaData?.getInt("service.api.version", 1) ?: 1
    }.getOrDefault(1)

    fun connect() {
        if (bound) return
        bound = runCatching {
            activity.bindService(serviceIntent(), connection, Context.BIND_AUTO_CREATE or Context.BIND_ADJUST_WITH_ACTIVITY)
        }.getOrDefault(false)
    }

    /** Hand the Google app our window so it can draw the feed over it. Needs the window token. */
    fun attach() {
        val lp: WindowManager.LayoutParams = activity.window.attributes
        if (lp.token == null) return
        if (serverVersion() >= 3) {
            val b = Bundle().apply {
                putParcelable("layout_params", lp)
                putParcelable("configuration", activity.resources.configuration)
                putInt("client_options", CLIENT_OPTIONS)
            }
            call(WINDOW_ATTACHED_2) {
                it.writeInt(1)
                b.writeToParcel(it, 0)
                it.writeStrongBinder(callback)
            }
        } else {
            call(WINDOW_ATTACHED) {
                it.writeInt(1)
                lp.writeToParcel(it, 0)
                it.writeStrongBinder(callback)
                it.writeInt(CLIENT_OPTIONS)
            }
        }
        if (resumed) call(ON_RESUME)
    }

    fun onResume() {
        resumed = true
        call(ON_RESUME)
    }

    fun onPause() {
        resumed = false
        call(ON_PAUSE)
    }

    fun open() = call(OPEN_OVERLAY) { it.writeInt(ANIMATE) }

    fun close() = call(CLOSE_OVERLAY) { it.writeInt(ANIMATE) }

    fun startScroll() = call(START_SCROLL)

    fun scroll(progress: Float) = call(ON_SCROLL) { it.writeFloat(progress) }

    fun endScroll() = call(END_SCROLL)

    fun destroy(changingConfigurations: Boolean) {
        call(WINDOW_DETACHED) { it.writeInt(if (changingConfigurations) 1 else 0) }
        if (bound) runCatching { activity.unbindService(connection) }
        bound = false
        overlay = null
        available = false
    }

    private fun call(code: Int, write: (Parcel) -> Unit = {}) {
        val b = overlay ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(OVERLAY)
            write(data)
            b.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
            available = false
        } finally {
            data.recycle()
        }
    }
}
