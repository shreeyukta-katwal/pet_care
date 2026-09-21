package com.petcare.app.gesture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.sqrt

/**
 * # Hardware Sensor Gesture: Accelerometer Shake Detector
 *
 * ## Technical Operation:
 * - Subscribes to [Sensor.TYPE_ACCELEROMETER] via Android's [SensorManager].
 * - Normalizes linear acceleration components (X, Y, Z) against standard gravity ([SensorManager.GRAVITY_EARTH])
 *   to compute the total vector G-force: `gForce = sqrt(gx^2 + gy^2 + gz^2)`.
 * - Employs a multi-peak temporal window algorithm:
 *   1. **Threshold**: Detects acceleration spikes where `gForce >= SHAKE_THRESHOLD_G` (2.7g).
 *   2. **Temporal Clustering**: Requires at least [MIN_SPIKES] (3 directional changes/spikes)
 *      within [SPIKE_WINDOW_MS] (800 ms) to filter out single bumps, vehicle jolts, or walking footsteps.
 *   3. **Debounce Cooldown**: Enforces a [COOLDOWN_MS] (1500 ms) refractory period after a successful
 *      trigger to prevent accidental repeated activations.
 * - Implements [DefaultLifecycleObserver] to automatically start in `onResume` and unregister in
 *   `onPause`/`onStop`, conserving device battery and preventing background sensor battery drain.
 *
 * ## Usability & UX Benefit:
 * - Provides a delightful, tactile physical gesture to perform a bulk operation (resetting the day's
 *   checklist) without hunting through nested menus.
 * - Paired with haptic feedback and a confirmation dialog to prevent accidental triggers.
 * - Fully accessible: a direct non-gesture alternative ("Reset today's checklist" in toolbar overflow)
 *   is provided for users with motor impairments or devices lacking accelerometer sensors.
 */
class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit
) : SensorEventListener, DefaultLifecycleObserver {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Whether the host device possesses an active accelerometer hardware sensor. */
    val isSupported: Boolean get() = accelerometer != null

    private var isListening: Boolean = false

    // Multi-spike tracking state
    private var spikeCount: Int = 0
    private var firstSpikeTimestampMs: Long = 0L
    private var lastShakeTimestampMs: Long = 0L

    companion object {
        /** G-force threshold for a single directional shake spike (~2.7g). */
        const val SHAKE_THRESHOLD_G = 2.7f

        /** Time window in milliseconds within which spikes must occur. */
        const val SPIKE_WINDOW_MS = 800L

        /** Minimum number of acceleration spikes required to qualify as an intentional shake. */
        const val MIN_SPIKES = 3

        /** Cooldown period in milliseconds following a successful shake to avoid duplicate firings. */
        const val COOLDOWN_MS = 1500L
    }

    /**
     * Starts listening for accelerometer motion events.
     */
    fun start() {
        if (!isSupported || isListening) return
        sensorManager?.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI
        )
        isListening = true
        resetSpikes()
    }

    /**
     * Stops listening and unregisters from [SensorManager] to conserve device battery.
     */
    fun stop() {
        if (!isListening) return
        sensorManager?.unregisterListener(this)
        isListening = false
        resetSpikes()
    }

    override fun onResume(owner: LifecycleOwner) {
        start()
    }

    override fun onPause(owner: LifecycleOwner) {
        stop()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val now = System.currentTimeMillis()

        // Respect debounce cooldown
        if (now - lastShakeTimestampMs < COOLDOWN_MS) {
            return
        }

        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH

        val gForce = sqrt(x * x + y * y + z * z)

        if (gForce >= SHAKE_THRESHOLD_G) {
            if (spikeCount == 0) {
                firstSpikeTimestampMs = now
                spikeCount = 1
            } else {
                val elapsedSinceFirstSpike = now - firstSpikeTimestampMs
                if (elapsedSinceFirstSpike <= SPIKE_WINDOW_MS) {
                    spikeCount++
                    if (spikeCount >= MIN_SPIKES) {
                        // Successful intentional shake detected
                        lastShakeTimestampMs = now
                        resetSpikes()
                        onShake()
                    }
                } else {
                    // Window expired, start new window
                    firstSpikeTimestampMs = now
                    spikeCount = 1
                }
            }
        } else {
            // Check if window timed out
            if (spikeCount > 0 && (now - firstSpikeTimestampMs > SPIKE_WINDOW_MS)) {
                resetSpikes()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op for accelerometer thresholding
    }

    private fun resetSpikes() {
        spikeCount = 0
        firstSpikeTimestampMs = 0L
    }
}
