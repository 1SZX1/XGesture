package com.slideindex.app.shake

import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.util.Log

class FaceDownDetector(
    private val sensorHub: MotionSensorHub,
    private val onTriggered: () -> Unit,
) {

    val isAvailable: Boolean get() = sensorHub.hasAccelerometer
    val isRunning: Boolean get() = listenerRegistered

    private var listenerRegistered = false
    private var holdDurationMs = FaceDownGestureSettings.clampHoldDurationMs(800L)
    private var requireProximity = false

    private var peakAzSinceCycle = Float.NEGATIVE_INFINITY
    private var holdStartMs = 0L
    private var lastAccel = floatArrayOf(0f, 0f, 0f)
    private var accelReady = false
    private var proximityNear = false
    private var proximityMaxRange = 0f
    private var hasProximitySensor = false
    private var gyroStill = true
    private var hasGyroscope = false

    @Synchronized
    fun configure(holdDurationMs: Long, requireProximity: Boolean) {
        this.holdDurationMs = FaceDownGestureSettings.clampHoldDurationMs(holdDurationMs)
        this.requireProximity = requireProximity
    }

    @Synchronized
    fun start() {
        if (listenerRegistered) return
        if (!sensorHub.hasAccelerometer) {
            Log.w(TAG, "start: no accelerometer")
            return
        }
        hasProximitySensor = sensorHub.hasProximity
        hasGyroscope = sensorHub.hasGyroscope
        proximityMaxRange = sensorHub.proximityMaxRange
        proximityNear = !hasProximitySensor
        gyroStill = !hasGyroscope
        accelReady = false
        holdStartMs = 0L
        peakAzSinceCycle = Float.NEGATIVE_INFINITY

        var registered = sensorHub.register(
            MotionSensorHub.Kind.ACCELEROMETER,
            this,
            SensorManager.SENSOR_DELAY_NORMAL,
        ) { handleAccelerometer(it) }
        if (hasProximitySensor) {
            registered = sensorHub.register(
                MotionSensorHub.Kind.PROXIMITY,
                this,
                SensorManager.SENSOR_DELAY_NORMAL,
            ) { handleProximity(it) } && registered
        }
        if (hasGyroscope) {
            registered = sensorHub.register(
                MotionSensorHub.Kind.GYROSCOPE,
                this,
                SensorManager.SENSOR_DELAY_NORMAL,
            ) { handleGyroscope(it) } && registered
        }
        listenerRegistered = registered
        Log.d(
            TAG,
            "start: registered=$registered proximity=$hasProximitySensor gyro=$hasGyroscope " +
                "hold=${holdDurationMs}ms requireProximity=$requireProximity",
        )
    }

    @Synchronized
    fun stop() {
        sensorHub.unregister(this)
        listenerRegistered = false
        holdStartMs = 0L
        accelReady = false
        peakAzSinceCycle = Float.NEGATIVE_INFINITY
    }

    @Synchronized
    private fun handleAccelerometer(event: SensorEvent) {
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        peakAzSinceCycle = maxOf(peakAzSinceCycle, az)

        if (!accelReady) {
            lastAccel[0] = ax
            lastAccel[1] = ay
            lastAccel[2] = az
            accelReady = true
            return
        }

        if (!FaceDownClassifier.hasSeenScreenUp(peakAzSinceCycle)) {
            resetHold()
            lastAccel[0] = ax
            lastAccel[1] = ay
            lastAccel[2] = az
            return
        }

        val now = System.currentTimeMillis()
        if (!FaceDownClassifier.isFaceDownFlat(ax, ay, az)) {
            resetHold()
            lastAccel[0] = ax
            lastAccel[1] = ay
            lastAccel[2] = az
            return
        }
        if (hasProximitySensor && requireProximity && !proximityNear) {
            resetHold()
            lastAccel[0] = ax
            lastAccel[1] = ay
            lastAccel[2] = az
            return
        }
        if (!FaceDownClassifier.isAccelerometerStill(lastAccel[0], lastAccel[1], lastAccel[2], ax, ay, az)) {
            resetHold()
            lastAccel[0] = ax
            lastAccel[1] = ay
            lastAccel[2] = az
            return
        }
        if (hasGyroscope && !gyroStill) {
            resetHold()
            lastAccel[0] = ax
            lastAccel[1] = ay
            lastAccel[2] = az
            return
        }

        lastAccel[0] = ax
        lastAccel[1] = ay
        lastAccel[2] = az

        val elapsedMs = if (holdStartMs == 0L) {
            holdStartMs = now
            0L
        } else {
            now - holdStartMs
        }
        if (elapsedMs >= holdDurationMs) {
            Log.i(TAG, "face-down hold satisfied after ${elapsedMs}ms peakAz=$peakAzSinceCycle")
            peakAzSinceCycle = Float.NEGATIVE_INFINITY
            resetHold()
            onTriggered()
        }
    }

    @Synchronized
    private fun handleProximity(event: SensorEvent) {
        proximityNear = FaceDownClassifier.isProximityNear(event.values[0], proximityMaxRange)
    }

    @Synchronized
    private fun handleGyroscope(event: SensorEvent) {
        gyroStill = FaceDownClassifier.isGyroStill(event.values[0], event.values[1], event.values[2])
    }

    private fun resetHold() {
        holdStartMs = 0L
    }

    companion object {
        private const val TAG = "FaceDownDetector"
    }
}
