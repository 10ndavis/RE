package dev.myotv

import java.util.UUID
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Myo Bluetooth LE protocol, transcribed from Thalmic Labs' published myohw.h
 * (https://github.com/thalmiclabs/myo-bluetooth, Apache 2.0).
 *
 * The header claims big-endian values, but shipping firmware sends little-endian,
 * which is what every working open-source client decodes.
 */
object MyoProtocol {
    private fun myoUuid(short: Int): UUID =
        UUID.fromString(String.format("d506%04x-a904-deb9-4748-2c7f4a124842", short))

    val CONTROL_SERVICE: UUID = myoUuid(0x0001)
    val COMMAND_CHAR: UUID = myoUuid(0x0401)
    val IMU_SERVICE: UUID = myoUuid(0x0002)
    val IMU_DATA_CHAR: UUID = myoUuid(0x0402)
    val CLASSIFIER_SERVICE: UUID = myoUuid(0x0003)
    val CLASSIFIER_EVENT_CHAR: UUID = myoUuid(0x0103)

    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL_CHAR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    enum class Pose(val code: Int) {
        REST(0x0000), FIST(0x0001), WAVE_IN(0x0002), WAVE_OUT(0x0003),
        FINGERS_SPREAD(0x0004), DOUBLE_TAP(0x0005), UNKNOWN(0xffff);

        companion object {
            fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    enum class Arm { RIGHT, LEFT, UNKNOWN }

    sealed interface ClassifierEvent {
        data class ArmSynced(val arm: Arm, val xTowardWrist: Boolean) : ClassifierEvent
        data object ArmUnsynced : ClassifierEvent
        data class PoseChanged(val pose: Pose) : ClassifierEvent
        data object Unlocked : ClassifierEvent
        data object Locked : ClassifierEvent
        data object SyncFailed : ClassifierEvent
    }

    /** Orientation quaternion from one IMU notification. */
    data class ImuSample(val w: Float, val x: Float, val y: Float, val z: Float) {
        /**
         * Pitch in degrees, using the same formula as Thalmic's hello-myo SDK sample.
         * Myo's +x axis runs along the forearm, so this tracks raising and lowering
         * the arm regardless of how the band is rotated around it.
         */
        fun pitchDegrees(): Float {
            val n2 = (w * w + x * x + y * y + z * z).takeIf { it > 0f } ?: return 0f
            val s = 2f * (w * y - z * x) / n2
            return Math.toDegrees(asin(s.coerceIn(-1f, 1f).toDouble())).toFloat()
        }

        /** Heading in degrees (-180..180], same hello-myo convention. Drifts slowly; use for deltas only. */
        fun yawDegrees(): Float {
            val yaw = atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))
            return Math.toDegrees(yaw).toFloat()
        }
    }

    fun parseClassifierEvent(bytes: ByteArray): ClassifierEvent? {
        if (bytes.isEmpty()) return null
        return when (bytes[0].toInt() and 0xff) {
            0x01 -> {
                val arm = when (bytes.u8(1)) {
                    0x01 -> Arm.RIGHT
                    0x02 -> Arm.LEFT
                    else -> Arm.UNKNOWN
                }
                // 0x02 means +x points toward the elbow; unknown is treated as toward the wrist.
                ClassifierEvent.ArmSynced(arm, xTowardWrist = bytes.u8(2) != 0x02)
            }
            0x02 -> ClassifierEvent.ArmUnsynced
            0x03 -> if (bytes.size >= 3) ClassifierEvent.PoseChanged(Pose.of(bytes.u16le(1))) else null
            0x04 -> ClassifierEvent.Unlocked
            0x05 -> ClassifierEvent.Locked
            0x06 -> ClassifierEvent.SyncFailed
            else -> null
        }
    }

    fun parseImu(bytes: ByteArray): ImuSample? {
        if (bytes.size < 20) return null
        val scale = 16384f
        return ImuSample(bytes.s16le(0) / scale, bytes.s16le(2) / scale, bytes.s16le(4) / scale, bytes.s16le(6) / scale)
    }

    // Commands written to COMMAND_CHAR: [command, payload size, payload...].

    /** EMG off, IMU data streaming on, onboard pose classifier on. */
    fun setModeCommand() = bytes(0x01, 3, 0x00, 0x01, 0x01)

    fun neverSleepCommand() = bytes(0x09, 1, 0x01)

    fun normalSleepCommand() = bytes(0x09, 1, 0x00)

    /** Keep the firmware unlocked so it reports every pose; locking is done in [GestureMapper]. */
    fun unlockHoldCommand() = bytes(0x0a, 1, 0x02)

    fun vibrateCommand(kind: Vibration): ByteArray = when (kind) {
        Vibration.TICK -> vibrate2(60 to 200)
        Vibration.UNLOCK -> bytes(0x03, 1, 0x01)
        Vibration.LOCK -> vibrate2(80 to 255, 80 to 0, 80 to 255)
        Vibration.LONG -> bytes(0x03, 1, 0x02)
    }

    enum class Vibration { TICK, UNLOCK, LOCK, LONG }

    /** Extended vibrate: up to six (duration ms, strength 0-255) steps. */
    private fun vibrate2(vararg steps: Pair<Int, Int>): ByteArray {
        require(steps.size <= 6)
        val out = ByteArray(20)
        out[0] = 0x07
        out[1] = 18
        steps.forEachIndexed { i, (durationMs, strength) ->
            val o = 2 + i * 3
            out[o] = (durationMs and 0xff).toByte()
            out[o + 1] = (durationMs shr 8 and 0xff).toByte()
            out[o + 2] = strength.toByte()
        }
        return out
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ByteArray.u8(i: Int) = if (i < size) this[i].toInt() and 0xff else 0xff

    private fun ByteArray.u16le(i: Int) = (this[i].toInt() and 0xff) or ((this[i + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.s16le(i: Int) = u16le(i).toShort().toInt()
}
