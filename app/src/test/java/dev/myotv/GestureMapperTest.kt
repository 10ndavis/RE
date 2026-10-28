package dev.myotv

import android.view.KeyEvent
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

class GestureMapperTest {
    private val mapper = GestureMapper()

    private fun keys(outputs: List<Output>) = outputs.filterIsInstance<Output.Key>().map { it.keyCode }

    private fun pitch(degrees: Double): MyoProtocol.ImuSample {
        val half = Math.toRadians(degrees) / 2
        return MyoProtocol.ImuSample(cos(half).toFloat(), 0f, sin(half).toFloat(), 0f)
    }

    private fun unlocked(arm: Arm = Arm.RIGHT, now: Long = 0): GestureMapper {
        mapper.onArmSynced(arm, xTowardWrist = true)
        mapper.onPose(Pose.DOUBLE_TAP, now)
        mapper.onPose(Pose.REST, now)
        return mapper
    }

    @Test
    fun ignoresPosesUntilUnlocked() {
        mapper.onArmSynced(Arm.RIGHT, xTowardWrist = true)
        assertTrue(keys(mapper.onPose(Pose.FIST, 0)).isEmpty())
        val unlock = mapper.onPose(Pose.DOUBLE_TAP, 100)
        assertEquals(listOf(Output.Vibrate(Vibration.UNLOCK)), unlock)
        assertFalse(mapper.locked)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_CENTER), keys(mapper.onPose(Pose.FIST, 200)))
    }

    @Test
    fun wavesFollowTheArm() {
        unlocked(Arm.RIGHT)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT), keys(mapper.onPose(Pose.WAVE_IN, 10)))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT), keys(mapper.onPose(Pose.WAVE_OUT, 20)))

        unlocked(Arm.LEFT)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT), keys(mapper.onPose(Pose.WAVE_IN, 10)))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT), keys(mapper.onPose(Pose.WAVE_OUT, 20)))
    }

    @Test
    fun fingersSpreadIsBackAndHoldingItIsHome() {
        unlocked()
        assertEquals(listOf(KeyEvent.KEYCODE_BACK), keys(mapper.onPose(Pose.FINGERS_SPREAD, 1_000)))
        assertTrue(keys(mapper.onTick(1_500)).isEmpty())
        assertEquals(listOf(KeyEvent.KEYCODE_HOME), keys(mapper.onTick(2_300)))
        assertTrue("home fires once per hold", keys(mapper.onTick(3_000)).isEmpty())
    }

    @Test
    fun tiltGivesUpAndDownWithRepeatAndRelease() {
        unlocked()
        mapper.onImu(pitch(0.0), 0) // captures neutral
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP), keys(mapper.onImu(pitch(30.0), 100)))
        assertTrue("no repeat before delay", keys(mapper.onImu(pitch(30.0), 400)).isEmpty())
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP), keys(mapper.onImu(pitch(30.0), 700)))
        assertTrue("returning to neutral is silent", keys(mapper.onImu(pitch(2.0), 800)).isEmpty())
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_DOWN), keys(mapper.onImu(pitch(-30.0), 900)))
    }

    @Test
    fun invertTiltSwapsUpAndDown() {
        val inverted = GestureMapper(GestureMapper.Config(invertTilt = true))
        inverted.onArmSynced(Arm.RIGHT, xTowardWrist = true)
        inverted.onPose(Pose.DOUBLE_TAP, 0)
        inverted.onPose(Pose.REST, 0)
        inverted.onImu(pitch(0.0), 0)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_DOWN), keys(inverted.onImu(pitch(30.0), 100)))
    }

    @Test
    fun relocksAfterIdleAndOnDoubleTap() {
        unlocked(now = 0)
        assertEquals(listOf(Output.Vibrate(Vibration.LOCK)), mapper.onTick(10_000))
        assertTrue(mapper.locked)

        mapper.onPose(Pose.DOUBLE_TAP, 20_000)
        assertFalse(mapper.locked)
        assertEquals(listOf(Output.Vibrate(Vibration.LOCK)), mapper.onPose(Pose.DOUBLE_TAP, 20_500))
        assertTrue(mapper.locked)
    }

    @Test
    fun removingTheArmbandLocks() {
        unlocked()
        mapper.onArmUnsynced()
        assertTrue(mapper.locked)
        assertTrue(keys(mapper.onPose(Pose.FIST, 100)).isEmpty())
    }
}
