package dev.smartcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LumaGridTest {
    @Test
    fun findsHorizonBetweenBrightSkyAndDarkGround() {
        val grid = LumaGrid.build(160, 120, 0) { _, y -> if (y < 40) 220 else 60 }
        val horizon = grid.horizon()
        assertNotNull(horizon)
        assertEquals(1f / 3f, horizon!!, 0.07f)
    }

    @Test
    fun noHorizonInFlatScene() {
        assertNull(LumaGrid.build(160, 120, 0) { _, _ -> 128 }.horizon())
    }

    @Test
    fun rotatesBufferToWorldUpright() {
        // A sensor buffer that is bright on its left half; turned 90° clockwise that is the top half.
        val grid = LumaGrid.build(160, 120, 90) { x, _ -> if (x < 80) 240 else 20 }
        assertTrue(grid.average(Box(0f, 0f, 1f, 0.45f)) > 0.8f)
        assertTrue(grid.average(Box(0f, 0.55f, 1f, 1f)) < 0.2f)
    }

    @Test
    fun measuresSubjectAndClipping() {
        val grid = LumaGrid.build(160, 160, 0) { x, y -> if (x in 60..100 && y in 60..100) 255 else 50 }
        assertTrue(grid.average(Box(0.4f, 0.4f, 0.6f, 0.6f)) > 0.9f)
        assertTrue(grid.highlights in 0.04f..0.1f)
        assertTrue(grid.mean() < 0.4f)
    }

    @Test
    fun tinyBoxUsesNearestCell() {
        val grid = LumaGrid.build(160, 160, 0) { x, _ -> if (x < 80) 0 else 255 }
        assertEquals(1f, grid.average(Box.around(0.9f, 0.5f, 0.001f)), 0.01f)
    }
}
