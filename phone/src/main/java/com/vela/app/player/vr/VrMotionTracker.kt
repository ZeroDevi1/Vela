package com.vela.app.player.vr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * VR 转平面的陀螺仪视角（mpv 内核用；ExoPlayer 的球面视图自带传感器转动）。
 *
 * 用 `TYPE_GAME_ROTATION_VECTOR`（陀螺仪 + 加速度计，不受磁场干扰，Z 轴竖直向上、水平基准任意），
 * 把屏幕背面朝向（视线）的水平 / 俯仰变化量回调给调用方叠加到当前视角，因此可与拖动同时使用，
 * 开启时不会跳到设备的绝对朝向。与 iOS 版 `VRMotionTracker` 使用同一套计算。
 * 视线方向与界面横竖屏无关；画面横滚不处理。
 */
class VrMotionTracker(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private var onRotate: ((deltaYaw: Float, deltaPitch: Float) -> Unit)? = null
    /** 上一次的视线水平角与俯仰角（度）；null 表示尚无基准（刚开始或视线接近竖直）。 */
    private var lastHeading: Double? = null
    private var lastPitch: Double? = null

    val isAvailable: Boolean get() = sensor != null

    /** 开始跟踪；[onRotate] 在主线程收到角度增量（度，右 / 上为正）。 */
    fun start(onRotate: (deltaYaw: Float, deltaPitch: Float) -> Unit) {
        val sensor = sensor ?: return
        stop()
        this.onRotate = onRotate
        lastHeading = null
        lastPitch = null
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (onRotate == null) return
        sensorManager?.unregisterListener(this)
        onRotate = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val callback = onRotate ?: return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val (heading, pitch) = orientation(rotationMatrix)
        val deltaPitch = lastPitch?.let { pitch - it } ?: 0.0
        var deltaYaw = 0.0
        if (abs(pitch) < HEADING_PITCH_LIMIT && heading != null) {
            lastHeading?.let { deltaYaw = wrap(heading - it) }
            lastHeading = heading
        } else {
            lastHeading = null
        }
        lastPitch = pitch
        if (deltaYaw == 0.0 && deltaPitch == 0.0) return
        callback(deltaYaw.toFloat(), deltaPitch.toFloat())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** 视线接近竖直（俯仰超过该值，度）时水平角不稳定，暂停水平转动，避免画面突然甩动。 */
        private const val HEADING_PITCH_LIMIT = 80.0

        fun isAvailable(context: Context): Boolean {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            return manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null
        }

        /**
         * 旋转矩阵（行主序 3×3，设备坐标 → 世界坐标，Z 轴竖直向上）→ 视线（设备 -Z）的水平角与俯仰角（度）。
         * 水平角顺时针（向右转）为正；视线接近竖直时水平角无意义，返回 null。
         */
        internal fun orientation(matrix: FloatArray): Pair<Double?, Double> {
            val x = -matrix[2].toDouble()
            val y = -matrix[5].toDouble()
            val z = -matrix[8].toDouble()
            val pitch = Math.toDegrees(asin(z.coerceIn(-1.0, 1.0)))
            val heading = if (hypot(x, y) > 1e-3) -Math.toDegrees(atan2(y, x)) else null
            return heading to pitch
        }

        /** 角度差折回 -180..180，跨过 ±180° 时不产生整圈跳变。 */
        internal fun wrap(degrees: Double): Double {
            var value = degrees % 360.0
            if (value > 180) value -= 360
            if (value < -180) value += 360
            return value
        }
    }
}
