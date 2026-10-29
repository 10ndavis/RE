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

    /** "zune", "art" or "black". */
    var background: String
        get() = sp.getString("background", "zune")!!
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

    var musicPage: Int
        get() = sp.getInt("music_page", 0)
        set(v) = sp.edit().putInt("music_page", v).apply()
}
