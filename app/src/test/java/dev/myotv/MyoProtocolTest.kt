package dev.myotv

import dev.myotv.MyoProtocol.Arm
import dev.myotv.MyoProtocol.ClassifierEvent
import dev.myotv.MyoProtocol.Pose
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MyoProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun uuidsFollowTheMyoBaseUuid() {
        assertEquals("d5060001-a904-deb9-4748-2c7f4a124842", MyoProtocol.CONTROL_SERVICE.toString())
        assertEquals("d5060401-a904-deb9-4748-2c7f4a124842", MyoProtocol.COMMAND_CHAR.toString())
        assertEquals("d5060103-a904-deb9-4748-2c7f4a124842", MyoProtocol.CLASSIFIER_EVENT_CHAR.toString())
        assertEquals("d5060402-a904-deb9-4748-2c7f4a124842", MyoProtocol.IMU_DATA_CHAR.toString())
    }

    @Test
    fun commandsMatchTheHeaderLayouts() {
        assertArrayEquals(bytes(0x01, 3, 0, 1, 1), MyoProtocol.setModeCommand())
        assertArrayEquals(bytes(0x09, 1, 1), MyoProtocol.neverSleepCommand())
        assertArrayEquals(bytes(0x0a, 1, 2), MyoProtocol.unlockHoldCommand())
        val tick = MyoProtocol.vibrateCommand(MyoProtocol.Vibration.TICK)
        assertEquals(20, tick.size)
        assertArrayEquals(bytes(0x07, 18, 60, 0, 200), tick.copyOf(5))
    }

    @Test
    fun parsesClassifierEvents() {
        assertEquals(ClassifierEvent.ArmSynced(Arm.RIGHT, xTowardWrist = true), MyoProtocol.parseClassifierEvent(bytes(1, 1, 1)))
        assertEquals(ClassifierEvent.ArmSynced(Arm.LEFT, xTowardWrist = false), MyoProtocol.parseClassifierEvent(bytes(1, 2, 2)))
        assertEquals(ClassifierEvent.PoseChanged(Pose.WAVE_OUT), MyoProtocol.parseClassifierEvent(bytes(3, 3, 0)))
        assertEquals(ClassifierEvent.PoseChanged(Pose.UNKNOWN), MyoProtocol.parseClassifierEvent(bytes(3, 0xff, 0xff)))
        assertEquals(ClassifierEvent.ArmUnsynced, MyoProtocol.parseClassifierEvent(bytes(2, 0, 0)))
        assertNull(MyoProtocol.parseClassifierEvent(bytes(0x7f)))
    }

    @Test
    fun parsesImuQuaternionAndPitch() {
        val angle = Math.toRadians(30.0)
        val w = (cos(angle / 2) * 16384).toInt()
        val y = (sin(angle / 2) * 16384).toInt()
        val payload = ByteArray(20).also {
            it[0] = w.toByte(); it[1] = (w shr 8).toByte()
            it[4] = y.toByte(); it[5] = (y shr 8).toByte()
        }
        val sample = MyoProtocol.parseImu(payload)!!
        assertEquals(30f, sample.pitchDegrees(), 0.1f)
        assertNull(MyoProtocol.parseImu(ByteArray(10)))
    }
}
