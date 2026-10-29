package dev.zunehd

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The Zune HD home screen: the menu (lowercase words with small uppercase captions, the play
 * circle beside "music") and quickplay (Playing, Pins, History). The pane in front fills the
 * screen and the other waits at the side, shrunk; swipe, tap it, or press home to switch.
 */
class HomeActivity : ZuneActivity() {
    private companion object {
        const val PICK = 7
        val SIZES = floatArrayOf(26f, 32f, 38f)
    }

    private lateinit var pager: HomePager
    private lateinit var menuPane: ScrollView
    private lateinit var menu: LinearLayout
    private lateinit var quickplay: LinearLayout
    private lateinit var feed: FeedClient
    private val menuViews = ArrayList<View>()
    private var shownKey: Any? = null
    private var menuKey: String? = null
    private var choosing: String? = null
    private var leaving = false

    override fun buildBody(body: FrameLayout) {
        pager = HomePager(this)
        val quickPane = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        quickplay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // A narrow column, so hints wrap under the art like on the device.
            setPadding((HomePager.QUICK_LEFT * u).toInt(), (HomePager.QUICK_TOP * u).toInt(), (resources.displayMetrics.widthPixels - (HomePager.QUICK_LEFT + 124) * u).toInt(), (40 * u).toInt())
        }
        quickPane.addView(quickplay)
        pager.addView(quickPane)

        menuPane = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (HomePager.MENU_TOP * u).toInt(), 0, (40 * u).toInt())
        }
        menuPane.addView(menu)
        pager.addView(menuPane)
        buildMenu()

        // The Google feed (menu > news) dims home as it slides over, like on the Pixel Launcher.
        feed = FeedClient(this) { p -> pager.alpha = 1f - 0.6f * p }
        body.addView(pager, -1, -1)
    }

    private fun currentMenuKey() = HomeMenu.encode(prefs.menu) + prefs.showPlay + prefs.menuSize

    /** The menu as arranged in settings > home screen. */
    private fun buildMenu() {
        menuKey = currentMenuKey()
        menu.removeAllViews()
        menuViews.clear()
        val size = SIZES[prefs.menuSize]
        for (item in prefs.menu.filter { it.visible }) {
            val word = MenuWordView(this, item.label, caption(item), size, circle = prefs.showPlay && item.id == "music").apply {
                setOnClickListener { go(item, this) }
                onCircle = ::playCircle
                if (item.id !in HomeMenu.INTERNAL) setOnLongClickListener { choose(item); true }
                zuneTilt()
            }
            menu.addView(word, -1, -2)
            menuViews += word
        }
    }

    /** The small uppercase line under each menu word, like "ALBUMS ARTISTS PLAYLISTS SONGS". */
    private fun caption(item: MenuItem): String = when (item.id) {
        "music" -> "queue history library search"
        "apps" -> "all games"
        "news" -> "google"
        "settings" -> "home screen music accent"
        else -> Slots.intent(this, item, prefs)?.let { i ->
            val ri = packageManager.resolveActivity(i.selector ?: i, 0)
            ri?.activityInfo?.packageName?.takeIf { it != "android" }?.let { Player.appLabel(this, it) }
        } ?: ""
    }.uppercase()

    private fun playCircle() {
        when {
            !Player.hasAccess(this) -> Player.requestAccess(this)
            Player.isPlaying -> openNowPlaying()
            else -> {
                Player.playAll(this)
                openNowPlaying()
            }
        }
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
        if (menuKey != currentMenuKey()) {
            buildMenu()
            leaving = true
        }
        if (leaving) {
            leaving = false
            Motion.enter(menuViews, u, fromLeft = false)
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
        val key = listOf(Player.history.entries, prefs.pins, Player.title(), Player.isPlaying, Player.art)
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
                        leaving = false
                        Motion.enter(menuViews, u, fromLeft = false)
                        choose(menuItem)
                    }
                })
            }
        }
        leaving = true
        // The word flies up to where the next screen's huge cropped title sits.
        val word = view as MenuWordView
        val scale = Section.MENU_SIZE * Section.SCALE / word.size
        val loc = IntArray(2)
        word.getLocationOnScreen(loc)
        val bodyLoc = IntArray(2)
        pager.getLocationOnScreen(bodyLoc)
        val titlePaint = Paint().apply { typeface = Zune.light(this@HomeActivity); textSize = Section.MENU_SIZE * Section.SCALE * u }
        val titleBaseline = bodyLoc[1] - Section.CROP * u + u * Section.SCALE - titlePaint.fontMetrics.ascent
        val dx = 12 * u * Section.SCALE - (loc[0] + word.textX * scale)
        val dy = titleBaseline - (loc[1] + word.baseline * scale)
        Motion.exit(menuViews, u, hero = word, heroDx = dx, heroDy = dy, heroScale = scale, then = action)
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
        buildMenu()
    }

    // ---- quickplay ----

    private fun buildQuickplay() {
        quickplay.removeAllViews()
        val music = Player.appLabel(this, prefs.musicPackage)

        if (Player.hasTrack) {
            header(if (Player.isPlaying) "Playing" else "Paused")
            val side = (118 * u).toInt()
            val tile = FrameLayout(this).apply {
                setOnClickListener { openNowPlaying() }
                zuneOutline()
            }
            tile.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                Player.art?.let { setImageBitmap(it) } ?: setBackgroundColor(Zune.DARK)
            }, -1, -1)
            tile.addView(
                tiny(Player.title().uppercase(), 2).apply {
                    setShadowLayer(3 * u, 0f, 0f, 0xFF000000.toInt())
                    setPadding((4 * u).toInt(), 0, (4 * u).toInt(), (4 * u).toInt())
                },
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM),
            )
            quickplay.addView(tile, LinearLayout.LayoutParams(side, side))
        }

        header("Pins")
        val pins = prefs.pins.filter { packageManager.getLaunchIntentForPackage(it) != null }
        if (pins.isEmpty()) quickplay.addView(tiny("press and hold an app in apps to pin it to quickplay.".uppercase(), 3))
        strip(pins.map { pinTile(it) })

        header("History")
        val history = Player.history.entries.take(12)
        if (history.isEmpty()) quickplay.addView(tiny("songs you play in $music show up here.".uppercase(), 3))
        strip(history.map { historyTile(it) })
    }

    private fun header(text: String) {
        quickplay.addView(
            zText(text, 25f),
            LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = (if (quickplay.childCount == 0) 0 else 14 * u).toInt()
                bottomMargin = (4 * u).toInt()
            },
        )
    }

    /** Tiny bold uppercase text, as the Zune used for hints and tile captions. */
    private fun tiny(text: String, lines: Int): TextView = TextView(this).apply {
        this.text = text
        typeface = Typeface.create(Zune.regular(context), Typeface.BOLD)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 7.5f * u)
        setTextColor(Zune.WHITE)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setLineSpacing(0f, 1.05f)
    }

    /** A sideways-scrolling row of small square tiles. */
    private fun strip(tiles: List<View>) {
        if (tiles.isEmpty()) return
        val side = (52 * u).toInt()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.forEachIndexed { i, t -> row.addView(t, LinearLayout.LayoutParams(side, side).apply { if (i > 0) marginStart = (4 * u).toInt() }) }
        quickplay.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }, -1, -2)
    }

    private fun pinTile(pkg: String): View = FrameLayout(this).apply {
        setBackgroundColor(0x26FFFFFF)
        addView(ImageView(context).apply {
            setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
        }, FrameLayout.LayoutParams((32 * u).toInt(), (32 * u).toInt(), Gravity.CENTER))
        setOnClickListener { packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity) }
        setOnLongClickListener {
            prefs.pins = prefs.pins - pkg
            onPlayerChanged()
            true
        }
        zuneOutline()
    }

    private fun historyTile(e: HistoryEntry): View = FrameLayout(this).apply {
        val art = Player.history.art(e)
        addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (art != null) setImageBitmap(art) else setBackgroundColor(Zune.DARK)
        }, -1, -1)
        if (art == null) addView(tiny(e.title.uppercase(), 3).apply { setPadding((3 * u).toInt(), (3 * u).toInt(), (3 * u).toInt(), 0) }, -1, -2)
        setOnClickListener {
            Player.playHistory(this@HomeActivity, e)
            openNowPlaying()
        }
        zuneOutline()
    }
}

/**
 * One home menu entry: a light lowercase word with a small bold uppercase caption under it,
 * and for "music" the circled play button to its left.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class MenuWordView(ctx: Context, private val word: String, private val caption: String, val size: Float, private val circle: Boolean) : View(ctx) {
    private val u = Zune.u(ctx)
    var onCircle: (() -> Unit)? = null

    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Zune.light(ctx); textSize = size * u; color = Zune.WHITE }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Zune.regular(ctx), Typeface.BOLD)
        textSize = 5.5f * u
        color = 0xFFB0B0B0.toInt()
        letterSpacing = 0.04f
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f * u; color = Zune.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Zune.WHITE }

    val textX get() = HomePager.MENU_LEFT * u
    val baseline get() = -wordPaint.fontMetrics.ascent * 0.92f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // Rows sit about 1.5 words apart, as on the device.
        val h = size * 1.48f * u
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h.toInt())
    }

    override fun onDraw(c: Canvas) {
        c.drawText(word, textX, baseline, wordPaint)
        if (caption.isNotEmpty()) c.drawText(caption, textX + 8 * u, baseline + 8.5f * u, capPaint)
        if (circle) {
            val r = 10.5f * u
            val cx = textX - 15 * u
            val cy = baseline - size * u * 0.27f
            c.drawCircle(cx, cy, r, ring)
            Glyphs.play(c, cx + 1.2f * u, cy, 9 * u, fill)
        }
    }

    private var downX = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) downX = e.x
        if (circle && e.actionMasked == MotionEvent.ACTION_UP && downX < textX - 2 * u && downX > textX - 30 * u) {
            onCircle?.invoke()
            return true
        }
        return super.onTouchEvent(e)
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
