package dev.zunehd

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * The Zune HD home screen: quickplay (now playing, pins, history) on the left and the menu of
 * enormous lowercase words on the right. Whichever isn't in front shrinks to the side; swipe,
 * tap the side, or press home to switch.
 */
class HomeActivity : ZuneActivity() {
    private companion object {
        const val PICK = 7
    }

    private lateinit var pager: SnapScroller
    private lateinit var menuPane: ScrollView
    private lateinit var quickPane: ScrollView
    private lateinit var play: PlayCircleView
    private lateinit var quickplay: LinearLayout
    private lateinit var sideCatcher: View
    private lateinit var feed: FeedClient
    private val menuViews = ArrayList<View>()
    private var shownKey: Any? = null
    private var leaving = false
    private lateinit var menu: LinearLayout
    private var menuKey: String? = null
    private var choosing: String? = null
    private var menuTextSize = Section.MENU_SIZE

    override fun buildBody(body: FrameLayout) {
        val w = resources.displayMetrics.widthPixels
        val paneW = (w * 0.86f).toInt()
        pager = SnapScroller(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        quickPane = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        quickplay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * u).toInt(), (6 * u).toInt(), (12 * u).toInt(), (40 * u).toInt())
        }
        quickPane.addView(quickplay)
        row.addView(quickPane, paneW, -1)

        menuPane = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * u).toInt(), 0, (40 * u).toInt())
        }
        play = PlayCircleView(this).apply {
            setOnClickListener {
                when {
                    !Player.hasAccess(context) -> Player.requestAccess(this@HomeActivity)
                    Player.isPlaying -> openNowPlaying()
                    else -> {
                        Player.playAll(this@HomeActivity)
                        openNowPlaying()
                    }
                }
            }
            zuneTilt()
        }
        buildMenu()
        menuPane.addView(menu)
        row.addView(menuPane, paneW, -1)

        // The Google feed fades home as it slides over, like on the Pixel Launcher.
        feed = FeedClient(this) { progress -> row.alpha = 1f - 0.6f * progress }
        pager.feed = feed
        pager.onScroll = ::shrinkSides
        pager.addView(row, -2, -1)
        body.addView(pager, -1, -1)

        // The pane at the side is a target: tap it (or swipe on it) to bring it to the front.
        sideCatcher = SideCatcher(this) { pager.toggle() }
        body.addView(sideCatcher, FrameLayout.LayoutParams((w - paneW), -1, Gravity.START))
        pager.post {
            pager.scrollTo(pager.maxScroll(), 0)
            shrinkSides(pager.maxScroll())
        }
    }

    /** The menu as arranged in settings > home screen. */
    private fun buildMenu() {
        val items = prefs.menu.filter { it.visible }
        val size = floatArrayOf(38f, Section.MENU_SIZE, 54f)[prefs.menuSize]
        menuTextSize = size
        menuKey = HomeMenu.encode(items) + prefs.showPlay + size
        menu.removeAllViews()
        menuViews.clear()
        if (prefs.showPlay) {
            menu.addView(play, -1, (58 * u).toInt())
            menuViews += play
        }
        for (item in items) {
            val t = zText(item.label, size).apply {
                ellipsize = null
                setHorizontallyScrolling(true)
                setPadding((12 * u).toInt(), (1 * u).toInt(), 0, (5 * u).toInt())
                setOnClickListener { go(item, this) }
                if (item.id !in HomeMenu.INTERNAL) {
                    setOnLongClickListener { choose(item); true }
                }
                zuneTilt()
            }
            menu.addView(t, -1, -2)
            menuViews += t
        }
    }

    /** The pane that isn't in front shrinks toward its edge and dims. */
    private fun shrinkSides(scrollX: Int) {
        val max = pager.maxScroll().coerceAtLeast(1)
        val p = (scrollX.toFloat() / max).coerceIn(0f, 1f) // 1 = menu in front
        quickPane.pivotX = quickPane.width.toFloat()
        quickPane.pivotY = 0f
        menuPane.pivotX = 0f
        menuPane.pivotY = 0f
        val q = 0.82f + 0.18f * (1 - p)
        val m = 0.82f + 0.18f * p
        quickPane.scaleX = q
        quickPane.scaleY = q
        quickPane.alpha = 0.35f + 0.65f * (1 - p)
        menuPane.scaleX = m
        menuPane.scaleY = m
        menuPane.alpha = 0.35f + 0.65f * p
        val lp = sideCatcher.layoutParams as FrameLayout.LayoutParams
        val gravity = if (p > 0.5f) Gravity.START else Gravity.END
        if (lp.gravity != gravity) {
            lp.gravity = gravity
            sideCatcher.layoutParams = lp
        }
        sideCatcher.visibility = if (p > 0.95f || p < 0.05f) View.VISIBLE else View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Motion.enter(menuViews, u)
    }

    override fun onStart() {
        super.onStart()
        feed.connect()
    }

    override fun onRestart() {
        super.onRestart()
        val items = prefs.menu.filter { it.visible }
        if (menuKey != HomeMenu.encode(items) + prefs.showPlay + floatArrayOf(38f, Section.MENU_SIZE, 54f)[prefs.menuSize]) {
            buildMenu()
            leaving = true
        }
        if (leaving) {
            leaving = false
            Motion.enter(menuViews, u, fromRight = false)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        feed.attach()
    }

    override fun onResume() {
        super.onResume()
        feed.onResume()
    }

    override fun onPause() {
        super.onPause()
        feed.onPause()
    }

    override fun onDestroy() {
        feed.destroy(isChangingConfigurations)
        super.onDestroy()
    }

    /** Home while already home switches between the menu and quickplay, as on the Zune. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        feed.close()
        when {
            quick.showing -> quick.hide()
            pager.onMenu && menuPane.scrollY > 0 -> menuPane.smoothScrollTo(0, 0)
            else -> pager.toggle()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            quick.showing -> quick.hide()
            !pager.onMenu -> pager.showMenu()
            else -> menuPane.smoothScrollTo(0, 0)
        }
    }

    override fun onPlayerChanged() {
        super.onPlayerChanged()
        play.invalidate()
        val key = listOf(Player.history.entries, prefs.pins, Player.title(), Player.artist(), Player.isPlaying, Player.art)
        if (key != shownKey) {
            shownKey = key
            buildQuickplay()
        }
    }

    // ---- menu ----

    private fun go(menuItem: MenuItem, view: View) {
        if (leaving) return
        val item = menuItem.id
        if (item == "news") {
            news()
            return
        }
        val action: () -> Unit = when (item) {
            "music" -> ({ open(MusicActivity::class.java) })
            "apps" -> ({ open(AppsActivity::class.java) })
            "settings" -> ({ open(SettingsActivity::class.java) })
            else -> {
                val intent = Slots.intent(this, menuItem, prefs)
                if (intent == null) {
                    choose(menuItem)
                    return
                }
                ({
                    try {
                        startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Motion.enter(menuViews, u, fromRight = false)
                        leaving = false
                        choose(menuItem)
                    }
                })
            }
        }
        leaving = true
        // The word you tapped flies up and becomes the cropped title of the next screen.
        val loc = IntArray(2)
        pager.getLocationOnScreen(loc)
        Motion.exit(menuViews, u, hero = view, heroTop = loc[1] - Section.CROP * u, heroScale = Section.SCALE * Section.MENU_SIZE / menuTextSize, then = action)
    }

    /** The Google feed slides in over home; if Google won't serve it here, open the Google app. */
    private fun news() {
        when {
            feed.available -> feed.open()
            FeedClient.installed(this) -> {
                Toast.makeText(this, "the google app didn't share its feed, opening it instead", Toast.LENGTH_SHORT).show()
                Player.launchApp(this, FeedClient.GOOGLE)
            }
            else -> Player.launchApp(this, FeedClient.GOOGLE)
        }
    }

    /** Pick the app behind a menu item such as "videos" or "radio". */
    private fun choose(item: MenuItem) {
        choosing = item.id
        Toast.makeText(this, "choose an app for ${item.label}", Toast.LENGTH_SHORT).show()
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, AppsActivity::class.java).putExtra(AppsActivity.EXTRA_PICK, item.label), PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val id = choosing ?: return
        val pkg = data?.getStringExtra(AppsActivity.EXTRA_PACKAGE) ?: return
        if (requestCode != PICK || resultCode != RESULT_OK) return
        prefs.menu = prefs.menu.map { if (it.id == id) it.copy(pkg = pkg) else it }
        Toast.makeText(this, "${prefs.menu.firstOrNull { it.id == id }?.label} opens ${Player.appLabel(this, pkg)}", Toast.LENGTH_SHORT).show()
    }

    // ---- quickplay ----

    private fun buildQuickplay() {
        quickplay.removeAllViews()
        val paneW = resources.displayMetrics.widthPixels * 0.86f - 24 * u
        val gap = (6 * u).toInt()
        val tile = ((paneW - 2 * gap) / 3).toInt()

        if (Player.hasTrack) {
            section("now playing")
            quickplay.addView(zText(if (Player.isPlaying) "playing" else "paused", 14f, light = false, color = Zune.GRAY))
            val art = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                Player.art?.let { setImageBitmap(it) } ?: setBackgroundColor(Zune.DARK)
                setOnClickListener { openNowPlaying() }
                zuneTilt()
            }
            val big = (tile * 2 + gap)
            quickplay.addView(art, LinearLayout.LayoutParams(big, big).apply { topMargin = (4 * u).toInt() })
            quickplay.addView(zText(Player.title().lowercase(), 20f).apply { setPadding(0, (4 * u).toInt(), 0, 0) })
            quickplay.addView(zText(Player.artist().lowercase(), 13f, light = false, color = Zune.GRAY))
        }

        val pins = prefs.pins.filter { packageManager.getLaunchIntentForPackage(it) != null }
        section("pins")
        if (pins.isEmpty()) hint("press and hold an app in apps to pin it here")
        tiles(pins.map { pkg -> pinTile(pkg, tile) }, tile, gap)

        val history = Player.history.entries.take(6)
        section("history")
        if (history.isEmpty()) hint("songs you play in ${Player.appLabel(this, prefs.musicPackage)} show up here")
        tiles(history.map { historyTile(it, tile) }, tile, gap)
    }

    /** Quickplay items are square tiles, three across. */
    private fun tiles(views: List<View>, size: Int, gap: Int) {
        views.chunked(3).forEach { line ->
            val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.forEachIndexed { i, v -> l.addView(v, LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = gap }) }
            quickplay.addView(l, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = gap })
        }
    }

    private fun section(name: String) {
        quickplay.addView(
            zText(name, 30f),
            LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = (if (quickplay.childCount == 0) 0 else 16 * u).toInt()
                bottomMargin = (6 * u).toInt()
            },
        )
    }

    private fun hint(text: String) {
        quickplay.addView(zText(text, 15f, light = false, color = Zune.GRAY).apply { isSingleLine = false; maxLines = 3 })
    }

    private fun pinTile(pkg: String, size: Int): View {
        val frame = FrameLayout(this).apply { setBackgroundColor(0x33FFFFFF) }
        val icon = ImageView(this).apply { setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()) }
        frame.addView(icon, FrameLayout.LayoutParams(size / 2, size / 2, Gravity.CENTER))
        frame.setOnClickListener { packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity) }
        frame.setOnLongClickListener {
            prefs.pins = prefs.pins - pkg
            onPlayerChanged()
            true
        }
        frame.zuneTilt()
        return frame
    }

    private fun historyTile(e: HistoryEntry, size: Int): View {
        val frame = FrameLayout(this)
        val art: Bitmap? = Player.history.art(e)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (art != null) setImageBitmap(art) else setBackgroundColor(Zune.DARK)
        }
        frame.addView(image, -1, -1)
        if (art == null) {
            val shade = View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0xCC000000.toInt()))
            }
            frame.addView(shade, FrameLayout.LayoutParams(-1, size / 2, Gravity.BOTTOM))
            val label: TextView = zText(e.title.lowercase(), 12f, light = false).apply { setPadding((4 * u).toInt(), 0, (4 * u).toInt(), (4 * u).toInt()) }
            frame.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        frame.setOnClickListener {
            Player.playHistory(this, e)
            openNowPlaying()
        }
        frame.zuneTilt()
        return frame
    }
}

/** The circled play button at the top of the Zune HD menu: plays all your music. */
class PlayCircleView(ctx: Context) : View(ctx) {
    private val u = Zune.u(ctx)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * u; color = Zune.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Zune.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Zune.light(ctx); textSize = 22 * u; color = Zune.WHITE }

    override fun onDraw(c: Canvas) {
        val r = 19 * u
        val cx = 14 * u + r
        val cy = height / 2f
        c.drawCircle(cx, cy, r, ring)
        Glyphs.play(c, cx + 2 * u, cy, 16 * u, fill)
        c.drawText(if (Player.isPlaying) "now playing" else "play", cx + r + 10 * u, cy + text.textSize * 0.33f, text)
    }
}

/** Covers the pane shrunk at the side: a tap or a swipe there brings that pane to the front. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class SideCatcher(ctx: Context, private val onSwitch: () -> Unit) : View(ctx) {
    private var downX = 0f
    private val u = Zune.u(ctx)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downX = e.x
            MotionEvent.ACTION_UP -> if (abs(e.x - downX) < 8 * u || abs(e.x - downX) > 24 * u) onSwitch()
        }
        return true
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

    fun intent(ctx: Context, menuItem: MenuItem, prefs: Prefs): Intent? {
        val pm = ctx.packageManager
        val item = menuItem.id
        (menuItem.pkg ?: prefs.slot(item))?.let { pm.getLaunchIntentForPackage(it)?.let { i -> return i } }
        if (menuItem.custom) return null
        when (item) {
            "pictures" -> return Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
            "internet" -> return Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        }
        return defaults[item]?.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
    }
}
