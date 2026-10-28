package dev.smartcam

import kotlin.math.max
import kotlin.math.min

/**
 * A rectangle in normalized frame coordinates: 0..1 on both axes, y pointing down.
 * Unless noted, boxes are "world upright": the way the photo will look, whichever
 * way the phone is held.
 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2
    val area get() = max(0f, width) * max(0f, height)

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    fun iou(other: Box): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        if (w <= 0 || h <= 0) return 0f
        val inter = w * h
        return inter / (area + other.area - inter)
    }

    /** True if the box runs into a frame edge, i.e. the subject is probably cut off. */
    fun touchesEdge(margin: Float = 0.01f) =
        left <= margin || top <= margin || right >= 1 - margin || bottom >= 1 - margin

    companion object {
        fun around(x: Float, y: Float, half: Float) = Box(x - half, y - half, x + half, y + half)
    }
}

/** Quarter-turn rotations of normalized image coordinates. */
object Rotation {
    /** Where the point (x, y) of an image ends up after rotating the whole image clockwise by [degrees]. */
    fun cw(x: Float, y: Float, degrees: Int): Pair<Float, Float> = when (((degrees % 360) + 360) % 360) {
        90 -> Pair(1 - y, x)
        180 -> Pair(1 - x, 1 - y)
        270 -> Pair(y, 1 - x)
        else -> Pair(x, y)
    }

    fun ccw(x: Float, y: Float, degrees: Int) = cw(x, y, 360 - degrees)

    fun cw(box: Box, degrees: Int): Box {
        val (x1, y1) = cw(box.left, box.top, degrees)
        val (x2, y2) = cw(box.right, box.bottom, degrees)
        return Box(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    fun ccw(box: Box, degrees: Int) = cw(box, 360 - degrees)
}
