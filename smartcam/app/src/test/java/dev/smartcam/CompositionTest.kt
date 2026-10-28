package dev.smartcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionTest {
    private fun obj(box: Box) = Detection(box, DetectionKind.OBJECT, 1, "Cup", 0.8f)
    private fun face(box: Box, yaw: Float = 0f) = Detection(box, DetectionKind.FACE, 1, "Face", 1f, yaw)

    private fun guide(
        subject: Detection?,
        scene: SceneType = SceneType.GENERAL,
        roll: Float = 0f,
        composer: Composer = Composer(),
        horizon: Float? = null,
        vx: Float = 0f,
        steady: Boolean = true,
    ) = composer.guide(scene, subject, listOfNotNull(subject?.takeIf { it.kind == DetectionKind.FACE }), horizon, roll,
        false, vx, steady)

    @Test
    fun subjectRightOfThirdMeansPanRight() {
        val g = guide(obj(Box.around(0.85f, 1f / 3f, 0.08f)))
        assertEquals(Composer.THIRD * 2, g.target!!.x, 1e-5f)
        assertTrue(g.message, g.message.startsWith("Pan right"))
        assertFalse(g.aligned)
    }

    @Test
    fun subjectHighMeansTiltUp() {
        val g = guide(obj(Box.around(1f / 3f, 0.1f, 0.08f)))
        assertTrue(g.message, g.message.startsWith("Tilt up"))
    }

    @Test
    fun subjectOnThirdIsAligned() {
        val g = guide(obj(Box.around(0.66f, 0.34f, 0.12f)))
        assertTrue(g.aligned)
        assertEquals("Perfect, take the shot", g.message)
        assertTrue(g.score > 90)
    }

    @Test
    fun shakyPhoneAsksToHoldSteady() {
        val g = guide(obj(Box.around(0.66f, 0.34f, 0.12f)), steady = false)
        assertEquals("Hold steady…", g.message)
    }

    @Test
    fun tiltedPhoneAsksToLevelFirst() {
        val g = guide(obj(Box.around(0.66f, 0.34f, 0.12f)), roll = 6f)
        assertFalse(g.aligned)
        assertTrue(g.message.startsWith("Level"))
    }

    @Test
    fun faceGetsEyesOnTopThirdAndRoomToLookInto() {
        val looksRight = guide(face(Box(0.45f, 0.3f, 0.6f, 0.5f), yaw = 30f), SceneType.PORTRAIT)
        assertEquals(Composer.THIRD, looksRight.target!!.x, 1e-5f)
        assertEquals(Composer.THIRD, looksRight.target!!.y, 1e-5f)
        assertEquals(0.3f + 0.2f * 0.4f, looksRight.subjectPoint!!.y, 1e-5f)
        val looksLeft = guide(face(Box(0.45f, 0.3f, 0.6f, 0.5f), yaw = -30f), SceneType.PORTRAIT)
        assertEquals(1 - Composer.THIRD, looksLeft.target!!.x, 1e-5f)
    }

    @Test
    fun bigSubjectIsCentred() {
        val g = guide(obj(Box(0.05f, 0.1f, 0.95f, 0.85f)))
        assertEquals(0.5f, g.target!!.x, 1e-5f)
    }

    @Test
    fun tinySubjectAsksToMoveCloser() {
        val g = guide(obj(Box.around(1f / 3f, 1f / 3f, 0.05f)))
        assertEquals("Move closer", g.message)
    }

    @Test
    fun movingSubjectGetsLeadingRoom() {
        val g = guide(obj(Box.around(0.6f, 0.34f, 0.12f)), vx = 0.3f)
        assertEquals(Composer.THIRD, g.target!!.x, 1e-5f)
    }

    @Test
    fun keepsChosenThirdWhenSubjectDriftsPastMiddle() {
        val composer = Composer()
        guide(obj(Box.around(0.4f, 0.34f, 0.12f)), composer = composer)
        val g = guide(obj(Box.around(0.53f, 0.34f, 0.12f)), composer = composer)
        assertEquals(Composer.THIRD, g.target!!.x, 1e-5f)
        val far = guide(obj(Box.around(0.65f, 0.34f, 0.12f)), composer = composer)
        assertEquals(1 - Composer.THIRD, far.target!!.x, 1e-5f)
    }

    @Test
    fun horizonGoesOnAThirdLine() {
        val g = guide(null, SceneType.LANDSCAPE, horizon = 0.5f)
        assertTrue(g.horizonOnly)
        assertTrue(g.message, g.message.contains("horizon"))
        val ok = guide(null, SceneType.LANDSCAPE, horizon = 0.65f)
        assertTrue(ok.aligned)
    }

    @Test
    fun noSubjectAsksForTap() {
        assertEquals("Tap what you want to photograph", guide(null).message)
    }
}
