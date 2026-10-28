package dev.smartcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectTrackerTest {
    private fun obj(id: Int, x: Float, half: Float = 0.1f) = Detection(Box.around(x, 0.5f, half), DetectionKind.OBJECT, id, "Thing", 0.8f)
    private val face = Detection(Box.around(0.8f, 0.3f, 0.06f), DetectionKind.FACE, 9, "Face", 1f)

    @Test
    fun prefersFaces() {
        val t = SubjectTracker()
        t.update(listOf(obj(1, 0.5f, 0.2f), face), 0)
        assertEquals(9, t.subject!!.trackingId)
    }

    @Test
    fun lockedSubjectIsFollowedAndMeasuresSpeed() {
        val t = SubjectTracker()
        t.lock(obj(2, 0.2f), 0)
        t.update(listOf(obj(2, 0.25f), obj(3, 0.5f, 0.3f)), 100)
        t.update(listOf(obj(2, 0.30f), obj(3, 0.5f, 0.3f)), 200)
        assertEquals(2, t.subject!!.trackingId)
        assertTrue(t.vx > 0.1f)
    }

    @Test
    fun lockIsLostAfterTimeout() {
        val t = SubjectTracker(lostAfterMs = 1000)
        t.lock(obj(2, 0.2f), 0)
        assertFalse(t.update(emptyList(), 500))
        assertEquals(2, t.subject!!.trackingId)
        assertTrue(t.update(emptyList(), 1200))
        assertNull(t.subject)
        assertFalse(t.locked)
    }

    @Test
    fun autoModeSticksWithCurrentUnlessClearlyBeaten() {
        val t = SubjectTracker()
        t.update(listOf(obj(1, 0.5f, 0.15f)), 0)
        t.update(listOf(obj(1, 0.5f, 0.15f), obj(2, 0.3f, 0.16f)), 100)
        assertEquals(1, t.subject!!.trackingId)
    }
}
