package dev.smartcam

import org.junit.Assert.assertEquals
import org.junit.Test

class GeometryTest {
    private fun assertPoint(x: Float, y: Float, p: Pair<Float, Float>) {
        assertEquals(x, p.first, 1e-5f)
        assertEquals(y, p.second, 1e-5f)
    }

    @Test
    fun clockwiseQuarterTurnMovesTopLeftToTopRight() {
        assertPoint(1f, 0f, Rotation.cw(0f, 0f, 90))
        assertPoint(1f, 1f, Rotation.cw(0f, 0f, 180))
        assertPoint(0f, 1f, Rotation.cw(0f, 0f, 270))
    }

    @Test
    fun counterClockwiseUndoesClockwise() {
        for (deg in listOf(0, 90, 180, 270)) {
            val (x, y) = Rotation.cw(0.2f, 0.7f, deg)
            assertPoint(0.2f, 0.7f, Rotation.ccw(x, y, deg))
        }
    }

    @Test
    fun phoneTurnedClockwiseShowsWorldUpOnScreenLeft() {
        // Held with its left side up, the left edge of the screen is the top of the photo.
        assertPoint(0.5f, 0f, Rotation.cw(0f, 0.5f, 90))
    }

    @Test
    fun rotatedBoxStaysNormalized() {
        val b = Rotation.cw(Box(0.1f, 0.2f, 0.3f, 0.6f), 90)
        assertEquals(Box(0.4f, 0.1f, 0.8f, 0.3f), b.copy(left = round(b.left), top = round(b.top), right = round(b.right), bottom = round(b.bottom)))
    }

    @Test
    fun iou() {
        assertEquals(1f, Box(0f, 0f, 0.5f, 0.5f).iou(Box(0f, 0f, 0.5f, 0.5f)), 1e-6f)
        assertEquals(0f, Box(0f, 0f, 0.2f, 0.2f).iou(Box(0.5f, 0.5f, 0.7f, 0.7f)), 1e-6f)
        assertEquals(1f / 3f, Box(0f, 0f, 0.4f, 0.2f).iou(Box(0.2f, 0f, 0.6f, 0.2f)), 1e-5f)
    }

    private fun round(v: Float) = Math.round(v * 1000) / 1000f
}
