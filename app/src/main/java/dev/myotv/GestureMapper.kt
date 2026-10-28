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
 *   point arm           the pointer goes where you point, like a Wii remote; unlocking sets the center
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
        /** Degrees of arm swing that span the screen. 40 x 22.5 keeps a 16:9 TV's proportions. */
        val pointerRangeXDeg: Float = 40f,
        val pointerRangeYDeg: Float = 22.5f,
        /** Weight of each new sample in the pointer's smoothing; lower is steadier but laggier. */
        val pointerSmoothing: Float = 0.4f,
        /** Arm movement faster than this per sample counts as activity and keeps the armband unlocked. */
        val pointerActivityDeg: Float = 0.5f,
    )

    sealed interface Output {
        data class Key(val keyCode: Int) : Output
        data class Vibrate(val kind: Vibration) : Output
        /** Put the pointer at this fraction of the screen: (0, 0) is top-left, (1, 1) bottom-right. */
        data class PointerAt(val x: Float, val y: Float) : Output
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
    private var centerYaw = 0f
    private var centerPitch = 0f
    private var pointerX = 0.5f
    private var pointerY = 0.5f

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

        val out = if (config.mouseMode) pointAt(sample.yawDegrees(), pitch, nowMs) else tiltKeys(pitch, nowMs)
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

    /**
     * Absolute pointing: the arm's direction relative to where it pointed at unlock maps
     * straight to a screen position. Pointing past an edge drags the center along, so the
     * pointer stays under the arm on the way back and slow compass drift corrects itself.
     */
    private fun pointAt(yaw: Float, pitch: Float, nowMs: Long): List<Output> {
        val previousYaw = lastYaw
        val previousPitch = lastPitch
        lastYaw = yaw
        lastPitch = pitch
        if (previousYaw == null) {
            centerYaw = yaw
            centerPitch = pitch
            pointerX = 0.5f
            pointerY = 0.5f
            return listOf(Output.PointerAt(pointerX, pointerY))
        }
        if (abs(wrap(yaw - previousYaw)) >= config.pointerActivityDeg || abs(pitch - previousPitch) >= config.pointerActivityDeg) {
            lastActivityMs = nowMs
        }

        // Turning the arm left raises yaw, so the pointer goes left as yaw grows.
        val xSign = if (config.invertPointerX) -1f else 1f
        val rawX = 0.5f - xSign * wrap(yaw - centerYaw) / config.pointerRangeXDeg
        val rawY = 0.5f - (pitch - centerPitch) / config.pointerRangeYDeg
        val x = rawX.coerceIn(0f, 1f)
        val y = rawY.coerceIn(0f, 1f)
        if (x != rawX) centerYaw = wrap(yaw - xSign * (0.5f - x) * config.pointerRangeXDeg)
        if (y != rawY) centerPitch = pitch - (0.5f - y) * config.pointerRangeYDeg

        val newX = pointerX + (x - pointerX) * config.pointerSmoothing
        val newY = pointerY + (y - pointerY) * config.pointerSmoothing
        // Below a tenth of a pixel on a 1080p screen there's nothing to redraw.
        if (abs(newX - pointerX) < 0.00005f && abs(newY - pointerY) < 0.00005f) return emptyList()
        pointerX = newX
        pointerY = newY
        return listOf(Output.PointerAt(pointerX, pointerY))
    }

    private fun wrap(degrees: Float): Float = when {
        degrees > 180f -> degrees - 360f
        degrees <= -180f -> degrees + 360f
        else -> degrees
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
