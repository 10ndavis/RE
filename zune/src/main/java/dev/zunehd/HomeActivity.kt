package dev.zunehd

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The Zune HD main menu: one column of enormous lowercase words that run off the right edge,
 * with quickplay (history and pins) waiting just past it. Swipe left for quickplay.
 */
class HomeActivity : ZuneActivity() {
    private lateinit var pager: SnapScroller
    private lateinit var menuScroll: ScrollView
    private lateinit var mini: MiniPlayerView
    private lateinit var quickplay: LinearLayout
    private val menuViews = ArrayList<View>()
    private var shownHistory: List<HistoryEntry>? = null
    private var shownPins: List<String>? = null
    private var leaving = false

    private val items = listOf(
        "music", "videos", "pictures", "radio", "podcasts",
        "marketplace", "social", "internet", "apps", "settings",
    )

    override fun buildBody(body: FrameLayout) {
        val w = resources.displayMetrics.widthPixels
        pager = SnapScroller(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        menuScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * u).toInt(), 0, (40 * u).toInt())
        }
        mini = MiniPlayerView(this).apply { onOpen = { openNowPlaying() } }
        menu.addView(mini, -1, (62 * u).toInt())
        menuViews += mini
        for (item in items) {
            val t = zText(item, 46f).apply {
                ellipsize = null
                setHorizontallyScrolling(true)
                setPadding((12 * u).toInt(), (1 * u).toInt(), 0, (5 * u).toInt())
                setOnClickListener { go(item) }
                if (item !in listOf("music", "apps", "settings")) {
                    setOnLongClickListener { choose(item); true }
                }
                zuneTilt()
            }
            menu.addView(t, -1, -2)
            menuViews += t
        }
        menuScroll.addView(menu)
        row.addView(menuScroll, (w * 0.86f).toInt(), -1)

        val qScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        quickplay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((4 * u).toInt(), (8 * u).toInt(), (12 * u).toInt(), (40 * u).toInt())
        }
        qScroll.addView(quickplay)
        row.addView(qScroll, (w * 0.92f).toInt(), -1)

        pager.addView(row, -2, -1)
        body.addView(pager, -1, -1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Motion.enter(menuViews, u)
    }

    override fun onRestart() {
        super.onRestart()
        if (leaving) {
            leaving = false
            Motion.enter(menuViews, u, fromRight = false)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while already home: back to the top of the menu.
        if (quick.showing) quick.hide()
        pager.showMenu()
        menuScroll.smoothScrollTo(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            quick.showing -> quick.hide()
            !pager.onMenu -> pager.showMenu()
            else -> menuScroll.smoothScrollTo(0, 0)
        }
    }

    override fun onPlayerChanged() {
        super.onPlayerChanged()
        mini.update()
        val history = Player.history.entries
        val pins = prefs.pins
        if (history !== shownHistory || pins != shownPins) {
            shownHistory = history
            shownPins = pins
            buildQuickplay(history, pins)
        }
    }

    // ---- menu ----

    private fun go(item: String) {
        if (leaving) return
        val action: () -> Unit = when (item) {
            "music" -> ({ open(MusicActivity::class.java) })
            "apps" -> ({ open(AppsActivity::class.java) })
            "settings" -> ({ open(SettingsActivity::class.java) })
            else -> {
                val intent = Slots.intent(this, item, prefs)
                if (intent == null) {
                    choose(item)
                    return
                }
                ({
                    try {
                        startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        leaving = false
                        Motion.enter(menuViews, u, fromRight = false)
                        choose(item)
                    }
                })
            }
        }
        leaving = true
        Motion.exit(menuViews, u, action)
    }

    /** Pick the app behind a menu item such as "videos" or "radio". */
    private fun choose(item: String) {
        Toast.makeText(this, "choose an app for $item", Toast.LENGTH_SHORT).show()
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, AppsActivity::class.java).putExtra(AppsActivity.EXTRA_PICK, item), items.indexOf(item))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val item = items.getOrNull(requestCode) ?: return
        val pkg = data?.getStringExtra(AppsActivity.EXTRA_PACKAGE) ?: return
        if (resultCode != RESULT_OK) return
        prefs.setSlot(item, pkg)
        Toast.makeText(this, "$item opens ${Player.appLabel(this, pkg)}", Toast.LENGTH_SHORT).show()
    }

    // ---- quickplay ----

    private fun buildQuickplay(history: List<HistoryEntry>, pins: List<String>) {
        quickplay.removeAllViews()
        val paneW = (resources.displayMetrics.widthPixels * 0.92f - 16 * u)
        val gap = (6 * u).toInt()
        val tile = ((paneW - gap) / 2).toInt()

        section("history")
        if (history.isEmpty()) {
            hint("songs you play in ${Player.appLabel(this, prefs.musicPackage)} show up here")
        }
        history.take(8).chunked(2).forEach { pair ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, e ->
                line.addView(historyTile(e, tile), LinearLayout.LayoutParams(tile, tile).apply { if (i == 1) marginStart = gap })
            }
            quickplay.addView(line, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = gap })
        }

        section("pins")
        if (pins.isEmpty()) hint("press and hold an app in apps to pin it here")
        for (pkg in pins) {
            val launch = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (5 * u).toInt(), 0, (5 * u).toInt())
            }
            val icon = ImageView(this).apply { setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()) }
            line.addView(icon, LinearLayout.LayoutParams((40 * u).toInt(), (40 * u).toInt()).apply { marginEnd = (10 * u).toInt() })
            line.addView(zText(Player.appLabel(this, pkg), 22f), LinearLayout.LayoutParams(0, -2, 1f))
            line.setOnClickListener { startActivity(launch) }
            line.setOnLongClickListener {
                prefs.pins = prefs.pins - pkg
                onPlayerChanged()
                true
            }
            line.zuneTilt()
            quickplay.addView(line, -1, -2)
        }
    }

    private fun section(name: String) {
        quickplay.addView(
            zText(name, 30f),
            LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = (if (quickplay.childCount == 0) 0 else 18 * u).toInt()
                bottomMargin = (8 * u).toInt()
            },
        )
    }

    private fun hint(text: String) {
        quickplay.addView(zText(text, 15f, light = false, color = Zune.GRAY).apply { isSingleLine = false; maxLines = 3 })
    }

    private fun historyTile(e: HistoryEntry, size: Int): View {
        val frame = FrameLayout(this)
        val art: Bitmap? = Player.history.art(e)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (art != null) setImageBitmap(art) else setBackgroundColor(Zune.DARK)
        }
        frame.addView(image, -1, -1)
        val shade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0xCC000000.toInt()))
        }
        frame.addView(shade, FrameLayout.LayoutParams(-1, size / 2, Gravity.BOTTOM))
        val label: TextView = zText(e.title.lowercase(), 14f, light = false).apply { setPadding((6 * u).toInt(), 0, (6 * u).toInt(), (6 * u).toInt()) }
        frame.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        frame.setOnClickListener {
            Player.playHistory(this, e)
            openNowPlaying()
        }
        frame.zuneTilt()
        return frame
    }
}

/** Default apps behind the menu items the Zune had built in. Each can be changed by long press. */
object Slots {
    private val defaults = mapOf(
        "videos" to listOf("com.google.android.youtube", "com.netflix.mediaclient", "com.google.android.videos"),
        "radio" to listOf("tunein.player", "com.audials", "radio.garden"),
        "podcasts" to listOf("au.com.shiftyjelly.pocketcasts", "com.google.android.apps.podcasts", "com.bambuna.podcastaddict"),
        "marketplace" to listOf("com.android.vending"),
        "social" to listOf("com.instagram.android", "com.instagram.barcelona", "com.twitter.android", "com.facebook.katana"),
    )

    fun intent(ctx: android.content.Context, item: String, prefs: Prefs): Intent? {
        val pm = ctx.packageManager
        prefs.slot(item)?.let { pm.getLaunchIntentForPackage(it)?.let { i -> return i } }
        when (item) {
            "pictures" -> return Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
            "internet" -> return Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        }
        return defaults[item]?.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
    }
}
