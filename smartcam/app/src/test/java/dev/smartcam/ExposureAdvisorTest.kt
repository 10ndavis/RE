package dev.smartcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureAdvisorTest {
    private val range = -12..12
    private val third = 1f / 3f

    @Test
    fun darkSubjectRaisesExposure() {
        val next = ExposureAdvisor.nextEvIndex(0, third, range, measured = 0.25f, target = 0.46f, highlights = 0f)
        assertTrue(next > 0)
    }

    @Test
    fun closeEnoughStaysPut() {
        assertEquals(2, ExposureAdvisor.nextEvIndex(2, third, range, 0.45f, 0.46f, 0f))
    }

    @Test
    fun blownHighlightsPullBack() {
        val next = ExposureAdvisor.nextEvIndex(0, third, range, 0.46f, 0.46f, highlights = 0.15f)
        assertEquals(-1, next)
    }

    @Test
    fun neverMoreThanTwoStops() {
        assertEquals(6, ExposureAdvisor.nextEvIndex(5, third, range, 0.02f, 0.6f, 0f))
        assertEquals(-6, ExposureAdvisor.nextEvIndex(-6, third, range, 0.99f, 0.3f, 0f))
    }

    @Test
    fun fastSubjectGetsFastShutter() {
        assertEquals(ExposureAdvisor.SECOND / 500, ExposureAdvisor.shutterFor(0.8f))
        assertNull(ExposureAdvisor.shutterFor(0.02f))
    }

    @Test
    fun manualKeepsBrightnessByRaisingIso() {
        val m = ExposureAdvisor.manual(ExposureAdvisor.SECOND / 500, ExposureAdvisor.SECOND / 50, 100, 50..3200, 1000L..1_000_000_000L)
        assertNotNull(m)
        assertEquals(ExposureAdvisor.SECOND / 500, m!!.exposureNs)
        assertEquals(1000, m.iso)
    }

    @Test
    fun manualFallsBackToLongerShutterWhenIsoRunsOut() {
        val m = ExposureAdvisor.manual(ExposureAdvisor.SECOND / 1000, ExposureAdvisor.SECOND / 30, 800, 50..3200, 1000L..1_000_000_000L)!!
        assertEquals(3200, m.iso)
        assertEquals(ExposureAdvisor.SECOND / 120.0, m.exposureNs.toDouble(), 1000.0)
    }

    @Test
    fun autoAlreadyFastEnoughNeedsNoManual() {
        assertNull(ExposureAdvisor.manual(ExposureAdvisor.SECOND / 250, ExposureAdvisor.SECOND / 1000, 50, 50..3200, 1000L..1_000_000_000L))
    }

    @Test
    fun picksFrameRateRanges() {
        val ranges = listOf(15 to 15, 7 to 30, 15 to 30, 30 to 30, 24 to 60)
        assertEquals(30 to 30, ExposureAdvisor.fpsRange(ranges, lowLight = false, steady = true, moving = true))
        assertEquals(7 to 30, ExposureAdvisor.fpsRange(ranges, lowLight = true, steady = true, moving = false))
        assertNull(ExposureAdvisor.fpsRange(ranges, lowLight = false, steady = true, moving = false))
    }
}
