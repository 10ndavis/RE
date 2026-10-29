package dev.zunehd

import android.content.Intent
import android.provider.Settings
import android.widget.FrameLayout

class SettingsActivity : ZuneActivity() {
    private lateinit var rows: RowAdapter

    private val backgrounds = listOf("zune", "art", "black")
    private val backgroundNames = mapOf("zune" to "zune glow", "art" to "album art", "black" to "black")

    override fun buildBody(body: FrameLayout) {
        val pivot = PivotView(this)
        rows = RowAdapter(this) { prefs.accent }
        pivot.addPage("settings", zuneList(rows))
        body.addView(pivot, -1, -1)
        Motion.enter(listOf(pivot), u)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPlayerChanged() {
        super.onPlayerChanged()
        if (::rows.isInitialized) refresh()
    }

    private fun refresh() {
        val music = Player.appLabel(this, prefs.musicPackage)
        val access = Player.hasAccess(this)
        rows.rows = listOf(
            Row("music access", if (access) "on: controlling $music" else "off: tap to allow", highlight = !access) {
                Player.requestAccess(this)
            },
            Row("music app", "$music (${prefs.musicPackage})") {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this, AppsActivity::class.java).putExtra(AppsActivity.EXTRA_PICK, "music app"), 1)
            },
            Row("follow other players", if (prefs.followOthers) "on: show whatever is playing when $music is paused" else "off: only $music") {
                prefs.followOthers = !prefs.followOthers
                Player.connect()
                refresh()
            },
            Row("background", backgroundNames[prefs.background] ?: prefs.background) {
                prefs.background = backgrounds[(backgrounds.indexOf(prefs.background) + 1) % backgrounds.size]
                backdrop.update()
                refresh()
            },
            Row("accent", Zune.ACCENT_NAMES[prefs.accentIndex], highlight = true) {
                prefs.accentIndex = prefs.accentIndex + 1
                quick.update()
                refresh()
            },
            Row("default home", "make this your home screen") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) },
            Row("phone settings", "android settings") { startActivity(Intent(Settings.ACTION_SETTINGS)) },
            Row("fonts", "drop light.ttf and regular.ttf in Android/data/$packageName/files/fonts") {},
            Row("about", "zune hd launcher ${packageManager.getPackageInfo(packageName, 0).versionName}") {},
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val pkg = data?.getStringExtra(AppsActivity.EXTRA_PACKAGE)
        if (requestCode == 1 && resultCode == RESULT_OK && pkg != null) {
            prefs.musicPackage = pkg
            Player.connect()
            refresh()
        }
    }
}
