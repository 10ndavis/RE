package dev.zunehd

import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout

/** settings > home screen: show, hide, reorder, rename and add menu items, or reset them all. */
class HomeEditActivity : ZuneActivity() {
    override val section = "settings"

    private lateinit var rows: RowAdapter
    private var picking: String? = null // menu id to set the app for, or ADD

    private companion object {
        const val ADD = "+add"
        const val PICK = 3
        val SIZES = arrayOf("small", "zune", "large")
    }

    override fun buildBody(body: FrameLayout) {
        val pivot = PivotView(this)
        rows = RowAdapter(this) { prefs.accent }
        pivot.addPage("home screen", zuneList(rows))
        body.addView(pivot, -1, -1)
        Motion.enter(listOf(pivot), u)
        refresh()
    }

    private fun opens(item: MenuItem): String = when {
        item.id == "music" -> "opens music (${Player.appLabel(this, prefs.musicPackage)})"
        item.id == "news" -> "opens the google feed"
        item.id == "apps" || item.id == "settings" -> "opens ${item.id}"
        else -> {
            val pkg = item.pkg ?: prefs.slot(item.id)
            if (pkg != null) "opens ${Player.appLabel(this, pkg)}" else "opens the default app"
        }
    }

    private fun refresh() {
        val menu = prefs.menu
        rows.rows = menu.mapIndexed { i, item ->
            Row(
                item.label,
                (if (item.visible) "" else "hidden · ") + opens(item),
                highlight = !item.visible,
            ) { options(i) }
        } + listOf(
            Row("+ add an app", "put any app on the menu") { pick(ADD) },
            Row("play button", if (prefs.showPlay) "shown at the top of the menu" else "hidden") {
                prefs.showPlay = !prefs.showPlay
                refresh()
            },
            Row("menu text size", SIZES[prefs.menuSize]) {
                prefs.menuSize = (prefs.menuSize + 1) % SIZES.size
                refresh()
            },
            Row("reset to default", "the zune menu, play button and text size") { reset() },
        )
    }

    private fun options(i: Int) {
        val menu = prefs.menu
        val item = menu[i]
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += (if (item.visible) "hide" else "show") to { save(menu.toMutableList().also { it[i] = item.copy(visible = !item.visible) }) }
        if (i > 0) actions += "move up" to { save(HomeMenu.move(menu, i, -1)) }
        if (i < menu.size - 1) actions += "move down" to { save(HomeMenu.move(menu, i, 1)) }
        actions += "rename" to { rename(i) }
        if (item.id !in HomeMenu.INTERNAL) actions += "choose app" to { pick(item.id) }
        if (item.custom) actions += "remove" to { save(menu.filterIndexed { j, _ -> j != i }) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(item.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun save(menu: List<MenuItem>) {
        prefs.menu = menu
        refresh()
    }

    private fun rename(i: Int) {
        val item = prefs.menu[i]
        val field = EditText(this).apply {
            setText(item.label)
            inputType = InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("rename")
            .setView(field)
            .setPositiveButton("save") { _, _ ->
                val name = field.text.toString().trim().lowercase()
                if (name.isNotEmpty()) save(prefs.menu.toMutableList().also { it[i] = it[i].copy(label = name) })
            }
            .setNegativeButton("cancel", null)
            .show()
    }

    private fun pick(target: String) {
        picking = target
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, AppsActivity::class.java).putExtra(AppsActivity.EXTRA_PICK, "choose"), PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val pkg = data?.getStringExtra(AppsActivity.EXTRA_PACKAGE)
        val target = picking
        if (requestCode != PICK || resultCode != RESULT_OK || pkg == null || target == null) return
        val menu = prefs.menu
        save(
            if (target == ADD) {
                if (menu.any { it.id == "app:$pkg" }) menu
                else menu + MenuItem("app:$pkg", Player.appLabel(this, pkg), pkg)
            } else {
                menu.map { if (it.id == target) it.copy(pkg = pkg) else it }
            },
        )
    }

    private fun reset() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("reset home screen?")
            .setMessage("brings back the zune menu, its order and names, the play button and the text size. added apps are removed.")
            .setPositiveButton("reset") { _, _ ->
                prefs.resetHome()
                refresh()
            }
            .setNegativeButton("cancel", null)
            .show()
    }
}
