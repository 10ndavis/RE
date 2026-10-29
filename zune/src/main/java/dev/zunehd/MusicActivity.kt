package dev.zunehd

import android.content.res.ColorStateList
import android.graphics.drawable.BitmapDrawable
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.util.TypedValue

/**
 * "music", the Zune way: a pivot of queue, history, library and search over the music app's
 * session, with the embedded mini player docked at the bottom.
 */
class MusicActivity : ZuneActivity() {
    override val section = "music"

    private lateinit var pivot: PivotView
    private lateinit var mini: MiniPlayerView
    private lateinit var queue: RowAdapter
    private lateinit var history: RowAdapter
    private lateinit var library: LibraryPage

    override fun buildBody(body: FrameLayout) {
        val music = Player.appLabel(this, prefs.musicPackage)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pivot = PivotView(this)
        queue = RowAdapter(this) { prefs.accent }
        history = RowAdapter(this) { prefs.accent }
        library = LibraryPage(this, prefs.musicPackage) { openNowPlaying() }

        pivot.addPage("queue", zuneList(queue, "nothing queued. play something in $music and its up next list shows here."))
        pivot.addPage("history", zuneList(history, "songs you play in $music show up here."))
        pivot.addPage("library", library.view)
        pivot.addPage("search", searchPage(music))
        pivot.onPageSelected = { i ->
            prefs.musicPage = i
            if (i == 2) library.start()
            if (i != 3) hideKeyboard()
        }
        pivot.setPage(prefs.musicPage)
        if (pivot.current == 2) library.start()
        column.addView(pivot, LinearLayout.LayoutParams(-1, 0, 1f))

        mini = MiniPlayerView(this).apply { onOpen = { openNowPlaying() } }
        column.addView(mini, -1, (62 * u).toInt())
        body.addView(column, -1, -1)
    }

    override fun onPlayerChanged() {
        super.onPlayerChanged()
        mini.update()
        if (!Player.hasAccess(this)) {
            val connect = Row("connect ${Player.appLabel(this, prefs.musicPackage)}", "allow music access so the launcher can show and control it") { Player.requestAccess(this) }
            queue.rows = listOf(connect)
            history.rows = listOf(connect)
            return
        }
        val active = Player.activeQueueId()
        queue.rows = Player.queue().map { q ->
            val d = q.description
            Row(
                d.title?.toString()?.lowercase() ?: "",
                d.subtitle?.toString()?.lowercase(),
                icon = d.iconBitmap?.let { b -> { BitmapDrawable(resources, b) } },
                highlight = q.queueId == active,
            ) { Player.skipTo(q.queueId) }
        }
        history.rows = Player.history.entries.map { e ->
            Row(e.title.lowercase(), listOf(e.artist, e.album).filter { it.isNotEmpty() }.joinToString(" · ").lowercase(),
                icon = { Player.history.art(e)?.let { BitmapDrawable(resources, it) } }) {
                Player.playHistory(this, e)
                openNowPlaying()
            }
        }
    }

    private fun searchPage(music: String): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * u).toInt(), (4 * u).toInt(), (12 * u).toInt(), 0)
        }
        val field = EditText(this).apply {
            typeface = Zune.light(context)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 26 * u)
            setTextColor(Zune.WHITE)
            setHintTextColor(0x66FFFFFF)
            hint = "search $music"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            backgroundTintList = ColorStateList.valueOf(prefs.accent)
            setOnEditorActionListener { v, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                val q = v.text.toString().trim()
                if (enter && q.isNotEmpty()) {
                    hideKeyboard()
                    Player.search(this@MusicActivity, q)
                    openNowPlaying()
                }
                enter
            }
        }
        page.addView(field, -1, -2)
        val hint = zText("type a song, artist or album and $music plays the best match.", 15f, light = false, color = Zune.GRAY).apply {
            isSingleLine = false
            maxLines = 3
            setPadding(0, (10 * u).toInt(), 0, 0)
        }
        page.addView(hint, -1, -2)
        return page
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pivot.current == 2 && library.back()) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        library.stop()
    }
}
