package dev.smartcam

import kotlin.math.abs

/**
 * Brightness of a frame on a coarse grid, in world-upright layout, plus how much of it is
 * blown out or crushed. Values are 0..1 (gamma-encoded, as the camera delivers them).
 */
class LumaGrid(val size: Int, val cells: FloatArray, val highlights: Float, val shadows: Float) {

    fun at(col: Int, row: Int) = cells[row * size + col]

    fun mean() = cells.average().toFloat()

    /** Average brightness of the cells whose centres fall inside [box], or the nearest cell for tiny boxes. */
    fun average(box: Box): Float {
        var sum = 0f
        var n = 0
        for (row in 0 until size) for (col in 0 until size) {
            if (box.contains((col + 0.5f) / size, (row + 0.5f) / size)) {
                sum += at(col, row)
                n++
            }
        }
        if (n > 0) return sum / n
        val col = (box.cx * size).toInt().coerceIn(0, size - 1)
        val row = (box.cy * size).toInt().coerceIn(0, size - 1)
        return at(col, row)
    }

    /**
     * Finds a horizon as the row boundary with the biggest brightness step from a brighter sky
     * above to darker ground below. Returns its normalized y, or null if there's no clear one.
     */
    fun horizon(minStep: Float = 0.08f): Float? {
        val rows = FloatArray(size) { row -> (0 until size).map { at(it, row) }.average().toFloat() }
        var best = 0f
        var bestRow = -1
        for (split in 2 until size - 1) {
            val above = rows.slice(0 until split).average()
            val below = rows.slice(split until size).average()
            // A sharp edge right at the boundary matters more than a gradual fade.
            val edge = rows[split - 1] - rows[split]
            val step = (above - below).toFloat() * 0.6f + edge * 0.4f
            if (step > best) {
                best = step
                bestRow = split
            }
        }
        return if (bestRow > 0 && best >= minStep) bestRow.toFloat() / size else null
    }

    /** Mean absolute brightness change from [other]: a cheap measure of scene motion or shake. */
    fun difference(other: LumaGrid): Float {
        if (other.size != size) return 0f
        var sum = 0f
        for (i in cells.indices) sum += abs(cells[i] - other.cells[i])
        return sum / cells.size
    }

    companion object {
        /**
         * Builds a grid from a camera luma plane of [width] x [height] pixels. [rotation] is how far the
         * buffer must turn clockwise to be world upright. [luma] returns 0..255 for a buffer pixel.
         */
        fun build(width: Int, height: Int, rotation: Int, size: Int = 16, step: Int = 4, luma: (Int, Int) -> Int): LumaGrid {
            val sums = FloatArray(size * size)
            val counts = IntArray(size * size)
            var bright = 0
            var dark = 0
            var total = 0
            var y = step / 2
            while (y < height) {
                var x = step / 2
                while (x < width) {
                    val v = luma(x, y)
                    val (u, w) = Rotation.cw((x + 0.5f) / width, (y + 0.5f) / height, rotation)
                    val col = (u * size).toInt().coerceIn(0, size - 1)
                    val row = (w * size).toInt().coerceIn(0, size - 1)
                    sums[row * size + col] += v / 255f
                    counts[row * size + col]++
                    if (v >= 250) bright++
                    if (v <= 6) dark++
                    total++
                    x += step
                }
                y += step
            }
            val cells = FloatArray(size * size) { if (counts[it] > 0) sums[it] / counts[it] else 0f }
            val n = total.coerceAtLeast(1).toFloat()
            return LumaGrid(size, cells, bright / n, dark / n)
        }
    }
}
