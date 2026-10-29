package dev.zunehd

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.service.media.MediaBrowserService
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Browses the music app's library through its MediaBrowserService, the same door Android Auto
 * uses. Many apps only open it to callers they recognise; when Tidal says no, the page says so
 * and search, history and the queue still work.
 */
class LibraryPage(private val activity: Activity, private val pkg: String, private val onPlay: () -> Unit) {
    private val u = Zune.u(activity)
    private val adapter = RowAdapter(activity) { Prefs(activity).accent }
    private val crumb: TextView = activity.zText("", 13f, light = false, color = Zune.GRAY)
    private val message: TextView = activity.zText("", 18f, color = Zune.GRAY).apply {
        isSingleLine = false
        maxLines = 5
        setLineSpacing(0f, 1.15f)
    }
    val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private var browser: MediaBrowser? = null
    private var started = false
    private val stack = ArrayList<Pair<String, String>>()

    init {
        crumb.setPadding((12 * u).toInt(), 0, (12 * u).toInt(), (4 * u).toInt())
        message.setPadding((12 * u).toInt(), (8 * u).toInt(), (12 * u).toInt(), 0)
        view.addView(crumb, -1, -2)
        view.addView(message, -1, -2)
        val list = activity.zuneList(adapter)
        view.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        say("")
    }

    private fun say(text: String) {
        message.text = text
        message.visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    fun start() {
        if (started) return
        started = true
        val label = Player.appLabel(activity, pkg)
        val svc = activity.packageManager
            .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo
        if (svc == null) {
            say("$label doesn't share a library with other apps. use search, history or the queue.")
            return
        }
        say("opening $label…")
        val b = MediaBrowser(activity, ComponentName(svc.packageName, svc.name), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                say("")
                val root = browser?.root ?: return
                stack.clear()
                open(root, label)
            }

            override fun onConnectionFailed() {
                say("$label keeps its library to itself, so browse it in $label. search, history and the queue still work here.")
            }

            override fun onConnectionSuspended() {
                say("$label disconnected.")
            }
        }, null)
        browser = b
        runCatching { b.connect() }.onFailure { say("couldn't reach $label.") }
    }

    private val subscription = object : MediaBrowser.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
            if (stack.lastOrNull()?.first != parentId) return
            say(if (children.isEmpty()) "nothing here." else "")
            adapter.rows = children.map { item ->
                val d = item.description
                val title = d.title?.toString() ?: ""
                Row(
                    title.lowercase(),
                    d.subtitle?.toString()?.lowercase(),
                    icon = d.iconBitmap?.let { b -> { BitmapDrawable(activity.resources, b) } },
                ) {
                    val id = item.mediaId ?: return@Row
                    when {
                        item.isBrowsable -> open(id, title.lowercase())
                        item.isPlayable -> play(id)
                    }
                }
            }
        }

        override fun onError(parentId: String) {
            say("couldn't open this.")
        }
    }

    private fun open(id: String, title: String) {
        val b = browser ?: return
        stack.lastOrNull()?.let { b.unsubscribe(it.first) }
        stack.add(id to title)
        load()
    }

    private fun load() {
        val b = browser ?: return
        val (id, _) = stack.last()
        crumb.text = stack.joinToString("  ›  ") { it.second }
        adapter.rows = emptyList()
        b.subscribe(id, subscription)
    }

    private fun play(id: String) {
        val b = browser ?: return
        MediaController(activity, b.sessionToken).transportControls.playFromMediaId(id, null)
        onPlay()
    }

    /** Up one level; false at the top. */
    fun back(): Boolean {
        val b = browser ?: return false
        if (stack.size <= 1) return false
        b.unsubscribe(stack.removeAt(stack.size - 1).first)
        load()
        return true
    }

    fun stop() {
        runCatching { browser?.disconnect() }
        browser = null
    }
}
