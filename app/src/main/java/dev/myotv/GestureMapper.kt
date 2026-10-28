package dev.myotv

import android.view.KeyEvent
import dev.myotv.MyoProtocol.Arm
import dev.myotv.MyoProtocol.Pose
import dev.myotv.MyoProtocol.Vibration
import kotlin.math.abs

/**
 * Turns Myo classifier poses and arm orientation into TV remote key presses.
 *
 * Default scheme, once the arm is synced:
 *   double tap          toggle lock (starts locked, relocks after [Config.idleLockMs] idle)
 *   wave left / right   D-pad left / right
 *   raise / lower arm   D-pad up / down, repeating while held
 *   fist                select
 *   fingers spread      back; hold for [Config.holdForHomeMs] for home
 *
 * Pure logic with an injected clock so it can be unit tested off-device.
 */
class GestureMapper(private val config: Config = Config()) {

    data class Config(
        val idleLockMs: Long = 10_000,
        val tiltThresholdDeg: Float = 20f,
        val tiltReleaseDeg: Float = 10f,
        val tiltRepeatDelayMs: Long = 600,
        val tiltRepeatIntervalMs: Long = 300,
        val holdForHomeMs: Long = 1_200,
        val invertTilt: Boolean = false,
    )

    sealed interface Output {
        data class Key(val keyCode: Int) : Output
        data class Vibrate(val kind: Vibration) : Output
    }

    private enum class Tilt { NONE, UP, DOWN }

    var synced = false
        private set
    var locked = true
        private set
    var arm = Arm.UNKNOWN
        private set
    var pose = Pose.REST
        private set

    private var xTowardWrist = true
    private var poseStartMs = 0L
    private var homeFired = false
    private var lastActivityMs = 0L
    private var neutralPitch: Float? = null
    private var tilt = Tilt.NONE
    private var nextTiltRepeatMs = 0L

    fun onArmSynced(arm: Arm, xTowardWrist: Boolean): List<Output> {
        synced = true
        this.arm = arm
        this.xTowardWrist = xTowardWrist
        lock()
        return emptyList()
    }

    fun onArmUnsynced(): List<Output> {
        synced = false
        lock()
        return emptyList()
    }

    fun onPose(newPose: Pose, nowMs: Long): List<Output> {
        pose = newPose
        poseStartMs = nowMs
        homeFired = false
        if (!synced) return emptyList()

        if (newPose == Pose.DOUBLE_TAP) {
            return if (locked) {
                locked = false
                lastActivityMs = nowMs
                neutralPitch = null
                listOf(Output.Vibrate(Vibration.UNLOCK))
            } else {
                lock()
                listOf(Output.Vibrate(Vibration.LOCK))
            }
        }
        if (locked) return emptyList()

        val key = when (newPose) {
            Pose.FIST -> KeyEvent.KEYCODE_DPAD_CENTER
            Pose.FINGERS_SPREAD -> KeyEvent.KEYCODE_BACK
            Pose.WAVE_IN -> if (arm == Arm.LEFT) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            Pose.WAVE_OUT -> if (arm == Arm.LEFT) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            else -> return emptyList()
        }
        lastActivityMs = nowMs
        return listOf(Output.Key(key), Output.Vibrate(Vibration.TICK))
    }

    fun onImu(sample: MyoProtocol.ImuSample, nowMs: Long): List<Output> {
        if (!synced || locked) return onTick(nowMs)

        var pitch = sample.pitchDegrees()
        if (!xTowardWrist) pitch = -pitch
        if (config.invertTilt) pitch = -pitch

        val neutral = neutralPitch ?: run {
            neutralPitch = pitch
            return onTick(nowMs)
        }
        val delta = pitch - neutral
        val out = mutableListOf<Output>()

        val wanted = when {
            delta > config.tiltThresholdDeg -> Tilt.UP
            delta < -config.tiltThresholdDeg -> Tilt.DOWN
            else -> Tilt.NONE
        }
        when {
            tilt == Tilt.NONE && wanted != Tilt.NONE -> {
                tilt = wanted
                nextTiltRepeatMs = nowMs + config.tiltRepeatDelayMs
                out += tiltKey(wanted, nowMs)
            }
            tilt != Tilt.NONE && (abs(delta) < config.tiltReleaseDeg || (wanted != Tilt.NONE && wanted != tilt)) -> {
                tilt = Tilt.NONE
            }
            tilt != Tilt.NONE && wanted == tilt && nowMs >= nextTiltRepeatMs -> {
                nextTiltRepeatMs = nowMs + config.tiltRepeatIntervalMs
                out += tiltKey(tilt, nowMs)
            }
        }
        if (tilt == Tilt.NONE && abs(delta) < config.tiltReleaseDeg) {
            // Let the resting position drift slowly so a tired, lowering arm doesn't trigger "down".
            neutralPitch = neutral + (pitch - neutral) * 0.02f
        }
        return out + onTick(nowMs)
    }

    /** Timeouts: hold-for-home and idle relock. Call regularly even without new data. */
    fun onTick(nowMs: Long): List<Output> {
        if (!synced || locked) return emptyList()
        if (pose == Pose.FINGERS_SPREAD && !homeFired && nowMs - poseStartMs >= config.holdForHomeMs) {
            homeFired = true
            lastActivityMs = nowMs
            return listOf(Output.Key(KeyEvent.KEYCODE_HOME), Output.Vibrate(Vibration.LONG))
        }
        val idle = pose == Pose.REST && tilt == Tilt.NONE
        if (idle && nowMs - lastActivityMs >= config.idleLockMs) {
            lock()
            return listOf(Output.Vibrate(Vibration.LOCK))
        }
        return emptyList()
    }

    private fun tiltKey(direction: Tilt, nowMs: Long): List<Output> {
        lastActivityMs = nowMs
        val key = if (direction == Tilt.UP) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        return listOf(Output.Key(key), Output.Vibrate(Vibration.TICK))
    }

    private fun lock() {
        locked = true
        neutralPitch = null
        tilt = Tilt.NONE
    }
}
