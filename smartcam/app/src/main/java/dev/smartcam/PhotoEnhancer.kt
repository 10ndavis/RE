package dev.smartcam

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** A tone curve and colour adjustments for one photo. */
class EnhancePlan(val curve: IntArray, val vibrance: Float, val warmth: Int, val protectSkin: Boolean)

/**
 * Finishing touches after capture, like a quick "auto" edit: stretches the tones to use the full
 * range, lifts dark shots, adds midtone contrast and boosts muted colours, with strengths chosen
 * per scene (gentle on faces, punchier on landscapes and food).
 */
object PhotoEnhancer {

    /** Works out the adjustments from a sample of the photo's ARGB [pixels]. */
    fun plan(pixels: IntArray, scene: SceneType): EnhancePlan {
        val histogram = IntArray(256)
        val stride = max(1, pixels.size / 200_000)
        var i = 0
        while (i < pixels.size) {
            histogram[luma(pixels[i])]++
            i += stride
        }
        val total = histogram.sum()
        fun percentile(p: Float): Int {
            val goal = total * p
            var count = 0
            for (v in 0..255) {
                count += histogram[v]
                if (count >= goal) return v
            }
            return 255
        }
        // Don't stretch more than a little: a dim or misty scene should stay dim or misty.
        val black = min(percentile(0.004f), 28)
        val white = max(percentile(0.997f), 200)
        var mean = 0.0
        for (v in 0..255) mean += histogram[v] * ((v - black).toDouble() / (white - black)).coerceIn(0.0, 1.0)
        mean /= max(1, total)

        val lift = if (mean < 0.38 && mean > 0.01) (ln(0.42) / ln(mean)).coerceIn(0.72, 1.0) else 1.0
        val contrast = when (scene) {
            SceneType.LANDSCAPE -> 0.28
            SceneType.FOOD, SceneType.MACRO -> 0.2
            SceneType.PORTRAIT, SceneType.GROUP -> 0.08
            SceneType.NIGHT -> 0.05
            else -> 0.15
        }
        val curve = IntArray(256) { v ->
            var y = ((v - black).toDouble() / (white - black)).coerceIn(0.0, 1.0)
            y = y.pow(lift)
            y = (1 - contrast) * y + contrast * (y * y * (3 - 2 * y))
            (y * 255).roundToInt().coerceIn(0, 255)
        }
        val vibrance = when (scene) {
            SceneType.LANDSCAPE -> 0.35f
            SceneType.FOOD, SceneType.MACRO, SceneType.PET -> 0.28f
            SceneType.PORTRAIT, SceneType.GROUP, SceneType.NIGHT -> 0.12f
            SceneType.GENERAL -> 0.2f
        }
        val warmth = if (scene == SceneType.FOOD) 6 else 0
        return EnhancePlan(curve, vibrance, warmth, scene == SceneType.PORTRAIT || scene == SceneType.GROUP)
    }

    /** Applies [plan] to ARGB [pixels] in place. */
    fun apply(pixels: IntArray, plan: EnhancePlan) {
        val curve = plan.curve
        for (i in pixels.indices) {
            val c = pixels[i]
            var r = curve[(c shr 16) and 0xFF] + plan.warmth
            var g = curve[(c shr 8) and 0xFF]
            var b = curve[c and 0xFF] - plan.warmth
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            if (hi > lo) {
                // Vibrance: muted colours get more boost than already saturated ones.
                var amount = plan.vibrance * (1f - (hi - lo) / 255f)
                if (plan.protectSkin && r > g && g > b) amount *= 0.4f
                val y = (r * 299 + g * 587 + b * 114) / 1000f
                r = (y + (r - y) * (1 + amount)).roundToInt()
                g = (y + (g - y) * (1 + amount)).roundToInt()
                b = (y + (b - y) * (1 + amount)).roundToInt()
            }
            pixels[i] = (c and 0xFF000000.toInt()) or (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
    }

    private fun luma(c: Int) = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
}
