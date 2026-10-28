package dev.myotv

import dev.myotv.GestureMapper.Output
import dev.myotv.MyoProtocol.Arm
import dev.myotv.MyoProtocol.Pose
import dev.myotv.MyoProtocol.Vibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MouseModeTest {
    private val mapper = GestureMapper(GestureMapper.Config(mouseMode = true))

    /** Arm pointing at [yawDeg] heading and [pitchDeg] elevation (yaw applied after pitch). */
    private fun orientation(yawDeg: Double, pitchDeg: Double = 0.0): MyoProtocol.ImuSample {
        val cy = cos(Math.toRadians(yawDeg) / 2); val sy = sin(Math.toRadians(yawDeg) / 2)
        val cp = cos(Math.toRadians(pitchDeg) / 2); val sp = sin(Math.toRadians(pitchDeg) / 2)
        return MyoProtocol.ImuSample((cy * cp).toFloat(), (-sy * sp).toFloat(), (cy * sp).toFloat(), (sy * cp).toFloat())
    }

    private fun moves(outputs: List<Output>) = outputs.filterIsInstance<Output.PointerMove>()

    private fun unlock(now: Long = 0) {
        mapper.onArmSynced(Arm.RIGHT, xTowardWrist = true)
        mapper.onPose(Pose.DOUBLE_TAP, now)
        mapper.onPose(Pose.REST, now)
    }

    @Test
    fun yawAndPitchDecodeFromQuaternion() {
        assertEquals(40f, orientation(40.0, 10.0).yawDegrees(), 0.1f)
        assertEquals(10f, orientation(40.0, 10.0).pitchDegrees(), 0.1f)
    }

    @Test
    fun doubleTapUnlocksThenClicks() {
        mapper.onArmSynced(Arm.RIGHT, xTowardWrist = true)
        assertEquals(listOf(Output.Vibrate(Vibration.UNLOCK)), mapper.onPose(Pose.DOUBLE_TAP, 0))
        mapper.onPose(Pose.REST, 100)
        assertEquals(listOf(Output.Click, Output.Vibrate(Vibration.TICK)), mapper.onPose(Pose.DOUBLE_TAP, 200))
        assertFalse("clicking doesn't lock", mapper.locked)
    }

    @Test
    fun armMovementMovesThePointer() {
        unlock()
        assertTrue("first sample only sets the reference", moves(mapper.onImu(orientation(0.0), 0)).isEmpty())
        val left = moves(mapper.onImu(orientation(2.0), 20)).single()
        assertEquals(-60f, left.dx, 1f)
        assertEquals(0f, left.dy, 1f)
        val up = moves(mapper.onImu(orientation(2.0, 1.0), 40)).single()
        assertEquals(-30f, up.dy, 1f)
    }

    @Test
    fun ignoresSensorJitterAndWrapsHeading() {
        unlock()
        mapper.onImu(orientation(179.0), 0)
        assertTrue(moves(mapper.onImu(orientation(179.01), 20)).isEmpty())
        val across = moves(mapper.onImu(orientation(-179.0), 40)).single()
        assertEquals("179 to -179 is a 2 degree turn", -60f, across.dx, 1f)
    }

    @Test
    fun invertPointerXFlipsHorizontalOnly() {
        val inverted = GestureMapper(GestureMapper.Config(mouseMode = true, invertPointerX = true))
        inverted.onArmSynced(Arm.RIGHT, xTowardWrist = true)
        inverted.onPose(Pose.DOUBLE_TAP, 0)
        inverted.onImu(orientation(0.0), 0)
        val move = moves(inverted.onImu(orientation(2.0, 1.0), 20)).single()
        assertEquals(60f, move.dx, 1f)
        assertEquals(-30f, move.dy, 1f)
    }

    @Test
    fun fistPressesAndReleaseLifts() {
        unlock()
        assertEquals(listOf(Output.TouchDown, Output.Vibrate(Vibration.TICK)), mapper.onPose(Pose.FIST, 100))
        assertTrue(mapper.touching)
        assertTrue("holding a fist never idle-locks", mapper.onTick(60_000).isEmpty())
        assertEquals(listOf(Output.TouchUp), mapper.onPose(Pose.REST, 60_100))
        assertFalse(mapper.touching)
    }

    @Test
    fun takingTheArmbandOffMidPressLiftsTheTouch() {
        unlock()
        mapper.onPose(Pose.FIST, 100)
        assertEquals(listOf(Output.TouchUp), mapper.onArmUnsynced())
    }

    @Test
    fun movingKeepsItUnlockedAndStillnessLocks() {
        unlock(now = 0)
        mapper.onImu(orientation(0.0), 0)
        mapper.onImu(orientation(5.0), 9_000)
        assertTrue(mapper.onTick(12_000).isEmpty())
        assertEquals(listOf(Output.Vibrate(Vibration.LOCK)), mapper.onTick(19_000))
        assertTrue(mapper.locked)
    }
}
