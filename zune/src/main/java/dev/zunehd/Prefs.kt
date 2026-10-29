package dev.zunehd

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("zune", Context.MODE_PRIVATE)

    /** The app whose media session the launcher embeds. */
    var musicPackage: String
        get() = sp.getString("music_pkg", Zune.TIDAL)!!
        set(v) = sp.edit().putString("music_pkg", v).apply()

    /** Show another player's session while the music app is idle and the other one plays. */
    var followOthers: Boolean
        get() = sp.getBoolean("follow_others", true)
        set(v) = sp.edit().putBoolean("follow_others", v).apply()

    /** "black" (the Zune HD default), "art" or "zune" (a magenta and orange glow). */
    var background: String
        get() = sp.getString("background", "black")!!
        set(v) = sp.edit().putString("background", v).apply()

    var accentIndex: Int
        get() = sp.getInt("accent", 0).mod(Zune.ACCENTS.size)
        set(v) = sp.edit().putInt("accent", v.mod(Zune.ACCENTS.size)).apply()

    val accent: Int get() = Zune.ACCENTS[accentIndex]

    var pins: List<String>
        get() = sp.getString("pins", "")!!.split('\n').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("pins", v.distinct().joinToString("\n")).apply()

    /** The app a home menu item such as "videos" opens, once chosen. */
    fun slot(item: String): String? = sp.getString("slot_$item", null)

    fun setSlot(item: String, pkg: String) = sp.edit().putString("slot_$item", pkg).apply()

    /** The home menu, in order. Older installs keep their per-item app choices via [slot]. */
    var menu: List<MenuItem>
        get() = HomeMenu.decode(sp.getString("menu", null))
        set(v) = sp.edit().putString("menu", HomeMenu.encode(v)).apply()

    /** The circled play button at the top of the menu. */
    var showPlay: Boolean
        get() = sp.getBoolean("show_play", true)
        set(v) = sp.edit().putBoolean("show_play", v).apply()

    /** Home menu text: 0 small, 1 zune, 2 large. */
    var menuSize: Int
        get() = sp.getInt("menu_size", 1).coerceIn(0, 2)
        set(v) = sp.edit().putInt("menu_size", v.coerceIn(0, 2)).apply()

    fun resetHome() {
        val e = sp.edit().remove("menu").remove("show_play").remove("menu_size")
        sp.all.keys.filter { it.startsWith("slot_") }.forEach { e.remove(it) }
        e.apply()
    }

    /** Show now playing on the lock screen while music plays. */
    var lockScreen: Boolean
        get() = sp.getBoolean("lock_screen", true)
        set(v) = sp.edit().putBoolean("lock_screen", v).apply()

    var musicPage: Int
        get() = sp.getInt("music_page", 0)
        set(v) = sp.edit().putInt("music_page", v).apply()
}
