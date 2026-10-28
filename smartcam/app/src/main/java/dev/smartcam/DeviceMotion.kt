package dev.smartcam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How the phone is held: which way is up, how far off level, lying flat, and how much it is shaking. */
class DeviceMotion(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravity = FloatArray(3)

    /** Quarter turn the phone is held at, clockwise from upright portrait: 0, 90, 180 or 270. */
    @Volatile var rotation = 0
        private set
    /** Degrees the phone is turned clockwise past [rotation]. */
    @Volatile var rollError = 0f
        private set
    /** Pointing straight down or up, where level and orientation don't apply. */
    @Volatile var flat = false
        private set
    /** Smoothed rotation rate in rad/s. */
    @Volatile var shake = 0f
        private set

    val steady get() = gyro == null || shake < 0.12f

    fun start() {
        gravitySensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sensors.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> shake = shake * 0.85f + sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) * 0.15f
            Sensor.TYPE_GRAVITY -> update(v[0], v[1], v[2])
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) gravity[i] = gravity[i] * 0.8f + v[i] * 0.2f
                update(gravity[0], gravity[1], gravity[2])
            }
        }
    }

    private fun update(x: Float, y: Float, z: Float) {
        flat = isFlat(x, y, z)
        if (flat) {
            rollError = 0f
            return
        }
        val angle = angle(x, y)
        rotation = quantize(angle, rotation)
        rollError = wrap(angle - rotation)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    companion object {
        /** Clockwise turn from upright portrait, 0..360, from gravity along the screen's x and y axes. */
        fun angle(x: Float, y: Float): Float {
            val a = Math.toDegrees(atan2(-x.toDouble(), y.toDouble())).toFloat()
            return (a + 360f) % 360f
        }

        fun isFlat(x: Float, y: Float, z: Float) = hypot(x, y) < abs(z) * 0.45f

        /** Nearest quarter turn, only leaving [current] once well past the halfway point. */
        fun quantize(angle: Float, current: Int): Int {
            if (abs(wrap(angle - current)) < 55f) return current
            return ((angle / 90f).roundToInt() * 90) % 360
        }

        fun wrap(degrees: Float): Float {
            var d = degrees % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }
    }
}
