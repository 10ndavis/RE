package dev.zunehd

import android.content.Context
import android.graphics.Typeface
import java.io.File

/**
 * The Zune HD look. Its screen was 272 x 480 px in portrait, and every size in this app is
 * given in those pixels ("u") and scaled to the phone, so proportions match the device.
 */
object Zune {
    const val TIDAL = "com.aspiro.tidal"

    const val WHITE = 0xFFFFFFFF.toInt()
    const val GRAY = 0xFF8C8C8C.toInt()
    const val DARK = 0xFF1E1E1E.toInt()

    val ACCENTS = intArrayOf(
        0xFFEC008C.toInt(), // zune pink
        0xFFF7941D.toInt(), // zune orange
        0xFF1BA1E2.toInt(),
        0xFF8CBF26.toInt(),
        0xFFA200FF.toInt(),
    )
    val ACCENT_NAMES = arrayOf("pink", "orange", "blue", "lime", "purple")

    /** One Zune HD pixel in phone pixels. */
    fun u(ctx: Context): Float {
        val dm = ctx.resources.displayMetrics
        return minOf(dm.widthPixels, dm.heightPixels) / 272f
    }

    private var light: Typeface? = null
    private var regular: Typeface? = null

    /** The thin face used for headers and menus (Zegoe UI Light on the device). */
    fun light(ctx: Context): Typeface = light ?: load(ctx, "light", "sans-serif-light").also { light = it }

    fun regular(ctx: Context): Typeface = regular ?: load(ctx, "regular", "sans-serif").also { regular = it }

    /**
     * Zegoe/Segoe can't be shipped, so a font you own can be dropped into
     * Android/data/dev.zunehd/files/fonts/ as light.ttf and regular.ttf.
     */
    private fun load(ctx: Context, name: String, fallback: String): Typeface {
        val dir = ctx.getExternalFilesDir("fonts")
        if (dir != null) {
            for (ext in listOf("ttf", "otf")) {
                val f = File(dir, "$name.$ext")
                if (f.isFile) runCatching { return Typeface.createFromFile(f) }
            }
        }
        runCatching { return Typeface.createFromAsset(ctx.assets, "fonts/$name.ttf") }
        return Typeface.create(fallback, Typeface.NORMAL)
    }
}
