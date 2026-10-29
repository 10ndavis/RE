package dev.zunehd

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.widget.FrameLayout

/** "apps": every app as a big lowercase list, games on their own pivot page. */
class AppsActivity : ZuneActivity() {
    companion object {
        /** Set to a home menu item to pick the app behind it instead of launching. */
        const val EXTRA_PICK = "pick"
        const val EXTRA_PACKAGE = "package"
    }

    private class App(val label: String, val component: ComponentName, val game: Boolean, val icon: Drawable?)

    private lateinit var all: RowAdapter
    private lateinit var games: RowAdapter
    private lateinit var allList: android.view.ViewGroup
    private lateinit var body: FrameLayout
    private lateinit var strip: LetterStripView
    private lateinit var bubble: android.widget.TextView
    private val picking get() = intent.getStringExtra(EXTRA_PICK)
    override val section get() = if (picking != null) null else "apps"

    override fun buildBody(body: FrameLayout) {
        this.body = body
        val pivot = PivotView(this)
        all = RowAdapter(this, compact = true) { prefs.accent }
        games = RowAdapter(this, compact = true) { prefs.accent }
        allList = zuneList(all, "loading…")
        strip = LetterStripView(this) { l -> jump(l) }
        allList.addView(strip, FrameLayout.LayoutParams((30 * u).toInt(), -1, android.view.Gravity.END))
        bubble = zText("", 64f).apply {
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE0000000.toInt())
                setStroke((2 * u).toInt(), Zune.WHITE)
            }
            visibility = android.view.View.GONE
        }
        strip.onScrub = { l ->
            bubble.text = l ?: ""
            bubble.visibility = if (l == null) android.view.View.GONE else android.view.View.VISIBLE
        }
        val pick = picking
        if (pick != null) {
            pivot.addPage(pick, allList)
        } else {
            pivot.addPage("all", allList)
            pivot.addPage("games", zuneList(games, "no games installed."))
        }
        body.addView(pivot, -1, -1)
        body.addView(bubble, FrameLayout.LayoutParams((96 * u).toInt(), (96 * u).toInt(), android.view.Gravity.CENTER))
        load()
    }

    private fun load() {
        Thread {
            val pm = packageManager
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    val ai = it.activityInfo
                    App(
                        it.loadLabel(pm).toString().lowercase(),
                        ComponentName(ai.packageName, ai.name),
                        ai.applicationInfo.category == ApplicationInfo.CATEGORY_GAME,
                        runCatching { it.loadIcon(pm) }.getOrNull(),
                    )
                }
                .sortedWith(compareBy<App> { Letters.of(it.label) != "#" }.thenBy(java.text.Collator.getInstance()) { it.label })
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                all.rows = withLetters(apps)
                strip.present = apps.map { letterOf(it) }.toSet()
                games.rows = apps.filter { it.game }.map(::row)
            }
        }.start()
    }

    private fun letterOf(a: App): String = Letters.of(a.label)

    private fun jump(letter: String) {
        val i = all.rows.indexOfFirst { it.tile && it.primary == letter }
        if (i >= 0) (allList.getChildAt(0) as android.widget.ListView).setSelectionFromTop(i, 0)
    }

    /** The list, broken up by in-line letter tiles like the Zune's music lists. */
    private fun withLetters(apps: List<App>): List<Row> {
        val out = ArrayList<Row>()
        var last: String? = null
        for (a in apps) {
            val l = letterOf(a)
            if (l != last) {
                last = l
                out += Row(l, tile = true) { alphabet() }
            }
            out += row(a)
        }
        return out
    }

    /** Tapping a letter tile shows every letter; pick one to jump there. */
    private fun alphabet() {
        val present = all.rows.filter { it.tile }.map { it.primary }.toSet()
        val overlay = FrameLayout(this).apply { setBackgroundColor(0xF0000000.toInt()) }
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 4
            setPadding((12 * u).toInt(), (12 * u).toInt(), (12 * u).toInt(), (12 * u).toInt())
        }
        val side = ((resources.displayMetrics.widthPixels - 24 * u) / 4 - 8 * u).toInt()
        for (l in Letters.ALL) {
            val on = l in present
            val tile = zText(l, 30f, color = if (on) Zune.WHITE else 0x40FFFFFF).apply {
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setStroke((1.5f * u).toInt(), if (on) Zune.WHITE else 0x40FFFFFF)
                }
                if (on) {
                    setOnClickListener {
                        body.removeView(overlay)
                        jump(l)
                    }
                    zuneTilt()
                }
            }
            grid.addView(tile, android.widget.GridLayout.LayoutParams().apply {
                width = side
                height = side
                setMargins((4 * u).toInt(), (4 * u).toInt(), (4 * u).toInt(), (4 * u).toInt())
            })
        }
        overlay.addView(grid, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER_HORIZONTAL))
        overlay.setOnClickListener { body.removeView(overlay) }
        body.addView(overlay, -1, -1)
        Motion.enter((0 until grid.childCount).map { grid.getChildAt(it) }, u)
    }

    private fun row(a: App) = Row(a.label, icon = { a.icon }, longAction = { menu(a) }) {
        if (picking != null) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PACKAGE, a.component.packageName))
            finish()
        } else {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
    }

    private fun menu(a: App) {
        val pkg = a.component.packageName
        val pinned = pkg in prefs.pins
        val options = arrayOf(if (pinned) "unpin from quickplay" else "pin to quickplay", "app info", "uninstall")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(a.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> prefs.pins = if (pinned) prefs.pins - pkg else prefs.pins + pkg
                    1 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                    2 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                }
            }
            .show()
    }
}
