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

    private fun pointer(outputs: List<Output>) = outputs.filterIsInstance<Output.PointerAt>().lastOrNull()

    /** Holds the arm still long enough for smoothing to settle, returning where the pointer ends up. */
    private fun settle(m: GestureMapper, sample: MyoProtocol.ImuSample, from: Long): Output.PointerAt? {
        var last: Output.PointerAt? = null
        for (i in 0 until 40) pointer(m.onImu(sample, from + i * 20L))?.let { last = it }
        return last
    }

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
    fun unlockingCentersThePointerWhereverTheArmPoints() {
        unlock()
        assertEquals(Output.PointerAt(0.5f, 0.5f), pointer(mapper.onImu(orientation(73.0, -12.0), 0)))
    }

    @Test
    fun pointingMapsDirectlyToScreenPosition() {
        unlock()
        mapper.onImu(orientation(0.0), 0)
        // 40 degrees spans the width: 10 degrees left is a quarter of the screen left.
        val left = settle(mapper, orientation(10.0), 20)!!
        assertEquals(0.25f, left.x, 0.01f)
        assertEquals(0.5f, left.y, 0.01f)
        // 22.5 degrees spans the height: raising the arm 5.625 degrees is a quarter up.
        val upRight = settle(mapper, orientation(-10.0, 5.625), 1_000)!!
        assertEquals(0.75f, upRight.x, 0.01f)
        assertEquals(0.25f, upRight.y, 0.01f)
        // Pointing back at the start returns the pointer to the center: absolute, not relative.
        val back = settle(mapper, orientation(0.0), 2_000)!!
        assertEquals(0.5f, back.x, 0.01f)
        assertEquals(0.5f, back.y, 0.01f)
    }

    @Test
    fun pointingPastAnEdgeDragsTheCenterAlong() {
        unlock()
        mapper.onImu(orientation(0.0), 0)
        // 50 degrees left is 30 past the left edge.
        assertEquals(0f, settle(mapper, orientation(50.0), 20)!!.x, 0.01f)
        // Coming back 20 degrees now moves halfway across rather than staying stuck at the edge.
        assertEquals(0.5f, settle(mapper, orientation(30.0), 1_000)!!.x, 0.01f)
    }

    @Test
    fun headingWrapsAroundCleanly() {
        unlock()
        mapper.onImu(orientation(179.0), 0)
        // 179 to -175 is a 6 degree turn to the left, not 354 to the right.
        assertEquals(0.35f, settle(mapper, orientation(-175.0), 20)!!.x, 0.01f)
    }

    @Test
    fun stillArmProducesNoUpdates() {
        unlock()
        settle(mapper, orientation(5.0), 0)
        assertEquals(null, pointer(mapper.onImu(orientation(5.0), 5_000)))
    }

    @Test
    fun invertPointerXFlipsHorizontalOnly() {
        val inverted = GestureMapper(GestureMapper.Config(mouseMode = true, invertPointerX = true))
        inverted.onArmSynced(Arm.RIGHT, xTowardWrist = true)
        inverted.onPose(Pose.DOUBLE_TAP, 0)
        inverted.onImu(orientation(0.0), 0)
        val p = settle(inverted, orientation(10.0, 5.625), 20)!!
        assertEquals(0.75f, p.x, 0.01f)
        assertEquals(0.25f, p.y, 0.01f)
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
