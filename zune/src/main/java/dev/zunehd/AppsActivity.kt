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
    private val picking get() = intent.getStringExtra(EXTRA_PICK)

    override fun buildBody(body: FrameLayout) {
        val pivot = PivotView(this)
        all = RowAdapter(this) { prefs.accent }
        games = RowAdapter(this) { prefs.accent }
        val pick = picking
        if (pick != null) {
            pivot.addPage(pick, zuneList(all, "loading…"))
        } else {
            pivot.addPage("apps", zuneList(all, "loading…"))
            pivot.addPage("games", zuneList(games, "no games installed."))
        }
        body.addView(pivot, -1, -1)
        Motion.enter(listOf(pivot), u)
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
                .sortedBy { it.label }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                all.rows = apps.map(::row)
                games.rows = apps.filter { it.game }.map(::row)
            }
        }.start()
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
