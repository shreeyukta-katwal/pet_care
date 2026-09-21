package com.petcare.app.gesture

import android.hardware.SensorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit tests validating the mathematical thresholding and temporal debounce
 * logic used by the accelerometer [ShakeDetector].
 */
class ShakeDetectorTest {

    private fun calculateGForce(x: Float, y: Float, z: Float): Float {
        val gx = x / SensorManager.GRAVITY_EARTH
        val gy = y / SensorManager.GRAVITY_EARTH
        val gz = z / SensorManager.GRAVITY_EARTH
        return sqrt(gx * gx + gy * gy + gz * gz)
    }

    @Test
    fun `stationary device at 1g does not trigger shake threshold`() {
        // Device resting flat on a table (1g on Z axis)
        val gForce = calculateGForce(0f, 0f, SensorManager.GRAVITY_EARTH)
        assertEquals(1.0f, gForce, 0.01f)
        assertFalse(gForce >= ShakeDetector.SHAKE_THRESHOLD_G)
    }

    @Test
    fun `gentle movement below threshold does not qualify as shake spike`() {
        // Gentle walking or tilting (e.g. 1.5g)
        val gForce = calculateGForce(
            SensorManager.GRAVITY_EARTH * 0.8f,
            SensorManager.GRAVITY_EARTH * 0.8f,
            SensorManager.GRAVITY_EARTH * 0.8f
        )
        assertTrue(gForce < ShakeDetector.SHAKE_THRESHOLD_G)
    }

    @Test
    fun `rapid directional acceleration spike exceeds shake threshold`() {
        // Vigorous shake spike (~28 m/s^2 ≈ 2.85g)
        val gForce = calculateGForce(25f, 10f, 5f)
        assertTrue(gForce >= ShakeDetector.SHAKE_THRESHOLD_G)
    }

    @Test
    fun `temporal spike window and minimum spike constants are configured correctly`() {
        assertEquals(2.7f, ShakeDetector.SHAKE_THRESHOLD_G, 0.01f)
        assertEquals(3, ShakeDetector.MIN_SPIKES)
        assertEquals(800L, ShakeDetector.SPIKE_WINDOW_MS)
        assertEquals(1500L, ShakeDetector.COOLDOWN_MS)
    }
}
