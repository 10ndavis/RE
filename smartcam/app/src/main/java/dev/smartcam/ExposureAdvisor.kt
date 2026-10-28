package dev.smartcam

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign

data class ManualExposure(val exposureNs: Long, val iso: Int)

/**
 * Exposure decisions on top of the camera's own auto exposure: how bright the subject should be,
 * when to force a fast shutter for motion, and when to allow a slow one for a steady low-light shot.
 */
object ExposureAdvisor {
    /** Brightness the subject should end up at, 0..1 gamma-encoded (about 0.46 is middle grey). */
    fun targetLuma(scene: SceneType, brightScene: Boolean): Float = when {
        brightScene -> 0.62f
        scene == SceneType.NIGHT -> 0.32f
        scene == SceneType.PORTRAIT || scene == SceneType.GROUP -> 0.50f
        scene == SceneType.FOOD -> 0.52f
        else -> 0.46f
    }

    /** How many stops brighter the subject needs to be. Pulls back when highlights are blowing out. */
    fun evError(measured: Float, target: Float, highlights: Float): Float {
        var error = 2.2f * log2(target / measured.coerceAtLeast(0.02f))
        if (highlights > 0.06f && measured > target * 0.85f) error = min(error, 0f) - 0.33f
        return error
    }

    /**
     * Next exposure compensation index, nudging part of the way toward [target] each call so the
     * loop settles rather than overshoots. Stays within ±2 EV and the camera's [range].
     */
    fun nextEvIndex(current: Int, stepEv: Float, range: IntRange, measured: Float, target: Float, highlights: Float): Int {
        val error = evError(measured, target, highlights)
        if (abs(error) < DEADBAND_EV) return current
        var steps = (error * GAIN / stepEv).roundToInt()
        if (steps == 0) steps = error.sign.toInt()
        val limit = (MAX_EV / stepEv).roundToInt()
        return (current + steps).coerceIn(max(range.first, -limit), min(range.last, limit))
    }

    /** The same nudge for manual exposure, as an EV offset applied to ISO. */
    fun nextManualEv(current: Float, measured: Float, target: Float, highlights: Float): Float {
        val error = evError(measured, target, highlights)
        if (abs(error) < DEADBAND_EV) return current
        return (current + error * GAIN).coerceIn(-MAX_EV, MAX_EV)
    }

    /** Shutter speed needed to freeze a subject moving at [speed] frame widths per second, or null for any. */
    fun shutterFor(speed: Float): Long? = when {
        speed > 0.5f -> SECOND / 500
        speed > 0.25f -> SECOND / 250
        speed > 0.1f -> SECOND / 125
        else -> null
    }

    /**
     * Manual settings giving the same brightness as auto exposure's ([autoNs], [autoIso]) but with a
     * shutter of at most [desiredNs], raising ISO to compensate. Null if auto is already fast enough.
     * If ISO runs out, uses the fastest shutter the ISO range allows.
     */
    fun manual(desiredNs: Long, autoNs: Long, autoIso: Int, isoRange: IntRange, exposureRange: LongRange, evOffset: Float = 0f): ManualExposure? {
        if (autoNs <= desiredNs) return null
        val light = autoNs.toDouble() * autoIso * 2.0.pow(evOffset.toDouble())
        var exposure = desiredNs
        var iso = (light / exposure).roundToInt()
        if (iso > isoRange.last) {
            iso = isoRange.last
            exposure = (light / iso).roundToLong()
        }
        return ManualExposure(exposure.coerceIn(exposureRange), iso.coerceIn(isoRange))
    }

    /**
     * Frame rate range for auto exposure, which bounds how long the shutter can stay open.
     * Moving or shaky: a high fixed rate keeps the shutter short. Steady in low light: a low minimum
     * rate lets it stay open longer for a cleaner image. Otherwise null for the camera's default.
     */
    fun fpsRange(available: List<Pair<Int, Int>>, lowLight: Boolean, steady: Boolean, moving: Boolean): Pair<Int, Int>? {
        if (moving || !steady) {
            return available.filter { it.second <= 60 && it.first >= 24 }
                .maxWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenBy { -it.second })
        }
        if (lowLight) {
            return available.filter { it.second >= 24 && it.first < 15 }.minByOrNull { it.first }
        }
        return null
    }

    /** Fill flash only helps a nearby person in the dark. */
    fun useFlash(scene: SceneType, meanLuma: Float) =
        (scene == SceneType.PORTRAIT || scene == SceneType.GROUP) && meanLuma < 0.12f

    const val SECOND = 1_000_000_000L
    private const val DEADBAND_EV = 0.25f
    private const val GAIN = 0.6f
    private const val MAX_EV = 2f
}
