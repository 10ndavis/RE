package dev.myotv

import android.view.KeyEvent
import dev.myotv.MyoProtocol.Arm
import dev.myotv.MyoProtocol.Pose
import dev.myotv.MyoProtocol.Vibration
import kotlin.math.abs

/**
 * Turns Myo classifier poses and arm orientation into TV remote input.
 *
 * Common to both modes, once the arm is synced:
 *   double tap          unlock (starts locked, relocks after [Config.idleLockMs] idle)
 *   wave left / right   D-pad left / right
 *   fingers spread      back; hold for [Config.holdForHomeMs] for home
 *
 * D-pad mode:
 *   double tap          lock
 *   raise / lower arm   D-pad up / down, repeating while held
 *   fist                select
 *
 * Mouse mode:
 *   move arm            move the pointer
 *   double tap          click
 *   fist                press and hold at the pointer: hold still to long-press, move to drag or scroll
 *
 * Pure logic with an injected clock so it can be unit tested off-device.
 */
class GestureMapper(private val config: Config = Config()) {

    data class Config(
        val mouseMode: Boolean = false,
        val idleLockMs: Long = 10_000,
        val tiltThresholdDeg: Float = 20f,
        val tiltReleaseDeg: Float = 10f,
        val tiltRepeatDelayMs: Long = 600,
        val tiltRepeatIntervalMs: Long = 300,
        val holdForHomeMs: Long = 1_200,
        val invertTilt: Boolean = false,
        val invertPointerX: Boolean = false,
        val pointerPxPerDeg: Float = 30f,
        /** Orientation changes smaller than this per sample are sensor noise, not movement. */
        val pointerJitterDeg: Float = 0.05f,
        /** Arm movement faster than this per sample counts as activity and keeps the armband unlocked. */
        val pointerActivityDeg: Float = 0.5f,
    )

    sealed interface Output {
        data class Key(val keyCode: Int) : Output
        data class Vibrate(val kind: Vibration) : Output
        /** Move the pointer by this many screen pixels; +x is right, +y is down. */
        data class PointerMove(val dx: Float, val dy: Float) : Output
        data object Click : Output
        data object TouchDown : Output
        data object TouchUp : Output
    }

    private enum class Tilt { NONE, UP, DOWN }

    val mouseMode get() = config.mouseMode
    var synced = false
        private set
    var locked = true
        private set
    var arm = Arm.UNKNOWN
        private set
    var pose = Pose.REST
        private set
    var touching = false
        private set

    private var xTowardWrist = true
    private var poseStartMs = 0L
    private var homeFired = false
    private var lastActivityMs = 0L
    private var neutralPitch: Float? = null
    private var tilt = Tilt.NONE
    private var nextTiltRepeatMs = 0L
    private var lastYaw: Float? = null
    private var lastPitch = 0f

    fun onArmSynced(arm: Arm, xTowardWrist: Boolean): List<Output> {
        synced = true
        this.arm = arm
        this.xTowardWrist = xTowardWrist
        return lock()
    }

    fun onArmUnsynced(): List<Output> {
        synced = false
        return lock()
    }

    fun onPose(newPose: Pose, nowMs: Long): List<Output> {
        pose = newPose
        poseStartMs = nowMs
        homeFired = false
        if (!synced) return emptyList()

        val out = mutableListOf<Output>()
        if (touching && newPose != Pose.FIST) {
            touching = false
            out += Output.TouchUp
        }

        if (newPose == Pose.DOUBLE_TAP) {
            when {
                locked -> {
                    locked = false
                    lastActivityMs = nowMs
                    out += Output.Vibrate(Vibration.UNLOCK)
                }
                config.mouseMode -> {
                    lastActivityMs = nowMs
                    out += listOf(Output.Click, Output.Vibrate(Vibration.TICK))
                }
                else -> out += lock() + Output.Vibrate(Vibration.LOCK)
            }
            return out
        }
        if (locked) return out

        if (newPose == Pose.FIST && config.mouseMode) {
            touching = true
            lastActivityMs = nowMs
            return out + listOf(Output.TouchDown, Output.Vibrate(Vibration.TICK))
        }
        val key = when (newPose) {
            Pose.FIST -> KeyEvent.KEYCODE_DPAD_CENTER
            Pose.FINGERS_SPREAD -> KeyEvent.KEYCODE_BACK
            Pose.WAVE_IN -> if (arm == Arm.LEFT) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            Pose.WAVE_OUT -> if (arm == Arm.LEFT) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            else -> return out
        }
        lastActivityMs = nowMs
        return out + listOf(Output.Key(key), Output.Vibrate(Vibration.TICK))
    }

    fun onImu(sample: MyoProtocol.ImuSample, nowMs: Long): List<Output> {
        if (!synced || locked) return onTick(nowMs)

        var pitch = sample.pitchDegrees()
        if (!xTowardWrist) pitch = -pitch
        if (config.invertTilt) pitch = -pitch

        val out = if (config.mouseMode) movePointer(sample.yawDegrees(), pitch, nowMs) else tiltKeys(pitch, nowMs)
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
        val idle = pose == Pose.REST && tilt == Tilt.NONE && !touching
        if (idle && nowMs - lastActivityMs >= config.idleLockMs) {
            return lock() + Output.Vibrate(Vibration.LOCK)
        }
        return emptyList()
    }

    private fun movePointer(yaw: Float, pitch: Float, nowMs: Long): List<Output> {
        val previousYaw = lastYaw
        val previousPitch = lastPitch
        lastYaw = yaw
        lastPitch = pitch
        if (previousYaw == null) return emptyList()

        var dYaw = yaw - previousYaw
        if (dYaw > 180f) dYaw -= 360f else if (dYaw < -180f) dYaw += 360f
        val dPitch = pitch - previousPitch
        if (abs(dYaw) < config.pointerJitterDeg && abs(dPitch) < config.pointerJitterDeg) return emptyList()
        if (abs(dYaw) >= config.pointerActivityDeg || abs(dPitch) >= config.pointerActivityDeg) lastActivityMs = nowMs

        // Turning the arm left raises yaw, so the pointer moves the opposite way on screen.
        var dx = -dYaw * config.pointerPxPerDeg
        if (config.invertPointerX) dx = -dx
        val dy = -dPitch * config.pointerPxPerDeg
        return listOf(Output.PointerMove(dx, dy))
    }

    private fun tiltKeys(pitch: Float, nowMs: Long): List<Output> {
        val neutral = neutralPitch ?: run {
            neutralPitch = pitch
            return emptyList()
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
        return out
    }

    private fun tiltKey(direction: Tilt, nowMs: Long): List<Output> {
        lastActivityMs = nowMs
        val key = if (direction == Tilt.UP) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        return listOf(Output.Key(key), Output.Vibrate(Vibration.TICK))
    }

    private fun lock(): List<Output> {
        locked = true
        neutralPitch = null
        tilt = Tilt.NONE
        lastYaw = null
        if (!touching) return emptyList()
        touching = false
        return listOf(Output.TouchUp)
    }
}
