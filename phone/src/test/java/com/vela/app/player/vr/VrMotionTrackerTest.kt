package com.vela.app.player.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** 世界坐标 Z 轴竖直向上；设备 -Z（屏幕背面）为视线。矩阵为行主序 3×3（设备 → 世界）。 */
class VrMotionTrackerTest {

    private fun rotationX(degrees: Double): DoubleArray {
        val r = Math.toRadians(degrees)
        return doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(r), -sin(r), 0.0, sin(r), cos(r))
    }

    private fun rotationZ(degrees: Double): DoubleArray {
        val r = Math.toRadians(degrees)
        return doubleArrayOf(cos(r), -sin(r), 0.0, sin(r), cos(r), 0.0, 0.0, 0.0, 1.0)
    }

    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { index ->
        val row = index / 3
        val column = index % 3
        (0 until 3).sumOf { a[row * 3 + it] * b[it * 3 + column] }
    }

    private fun DoubleArray.toFloats() = FloatArray(size) { this[it].toFloat() }

    @Test
    fun flatDeviceLooksDown() {
        val (heading, pitch) = VrMotionTracker.orientation(rotationZ(0.0).toFloats())
        assertEquals(-90.0, pitch, 1e-4)
        assertNull(heading)
    }

    @Test
    fun turningRightIncreasesHeadingAndTiltingUpIncreasesPitch() {
        // 竖起设备（绕设备 X 轴 +90°）：视线水平。
        val upright = rotationX(90.0)
        val base = VrMotionTracker.orientation(upright.toFloats())
        assertEquals(0.0, base.second, 1e-4)
        // 绕竖直轴顺时针（俯视）转 30°，即向右转。
        val right = VrMotionTracker.orientation(multiply(rotationZ(-30.0), upright).toFloats())
        val delta = VrMotionTracker.wrap(assertNotNullValue(right.first) - assertNotNullValue(base.first))
        assertEquals(30.0, delta, 1e-4)
        // 再竖起 20°：视线抬高。
        val tilted = VrMotionTracker.orientation(rotationX(110.0).toFloats())
        assertEquals(20.0, tilted.second, 1e-4)
    }

    @Test
    fun wrapAcrossBoundary() {
        assertEquals(-10.0, VrMotionTracker.wrap(350.0), 1e-9)
        assertEquals(10.0, VrMotionTracker.wrap(-350.0), 1e-9)
    }

    private fun assertNotNullValue(value: Double?): Double {
        assertNotNull(value)
        return value!!
    }
}
