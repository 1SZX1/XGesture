package com.slideindex.app.shake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程内唯一的一路传感器入口。
 *
 * 背景：摇一摇、翻转息屏、背面双击此前各自 `registerListener`，加速度计和陀螺仪被
 * 重复注册，同一份采样要走多份 Binder 派发、多个主线程消息。这里改成：每种物理传感器
 * 只注册一次，按"最快的消费者"决定采样率，再分发给所有消费者；比自身请求更快的消费者
 * 在分发时按事件时间戳节流，保持各自原来的有效采样率。
 *
 * 线程模型与旧实现一致：注册、注销、分发都在主线程，消费者可以直接改 UI 状态。
 * 本类只做"合并注册"，不改采样率、不改检测阈值。
 */
@Singleton
class MotionSensorHub @Inject constructor(
    @ApplicationContext context: Context,
) {
    enum class Kind { ACCELEROMETER, GYROSCOPE, PROXIMITY, LIGHT }

    private class Consumer(
        val owner: Any,
        val delayUs: Int,
        val callback: (SensorEvent) -> Unit,
    ) {
        var lastDeliveredNs: Long = Long.MIN_VALUE
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val sensorThread = HandlerThread("MotionSensorHub").apply { start() }
    private val handler = Handler(sensorThread.looper)

    private val lock = Any()
    private val consumers = HashMap<Kind, MutableList<Consumer>>()
    private val registeredDelayUs = HashMap<Kind, Int>()
    private val sensorCache = HashMap<Kind, Sensor?>()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val kind = kindOf(event.sensor.type) ?: return
            val snapshot = synchronized(lock) {
                consumers[kind]?.takeIf { it.isNotEmpty() }?.toTypedArray()
            } ?: return
            for (consumer in snapshot) {
                val minGapNs = consumer.delayUs * 1_000L
                val timestampNs = event.timestamp
                if (
                    minGapNs > 0L &&
                    consumer.lastDeliveredNs != Long.MIN_VALUE &&
                    timestampNs - consumer.lastDeliveredNs < minGapNs
                ) {
                    continue
                }
                consumer.lastDeliveredNs = timestampNs
                runCatching { consumer.callback(event) }
                    .onFailure { Log.w(TAG, "consumer failed for $kind", it) }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    val hasAccelerometer: Boolean get() = sensor(Kind.ACCELEROMETER) != null
    val hasGyroscope: Boolean get() = sensor(Kind.GYROSCOPE) != null
    val hasProximity: Boolean get() = sensor(Kind.PROXIMITY) != null
    val hasLight: Boolean get() = sensor(Kind.LIGHT) != null

    /** 距离传感器的最大量程；没有距离传感器时为 0。 */
    val proximityMaxRange: Float
        get() = sensor(Kind.PROXIMITY)?.maximumRange ?: 0f

    /**
     * 注册一个消费者。同一个 [owner] 在同类传感器上重复注册会覆盖旧回调，
     * 采样率不变时不会触发重新注册。
     */
    fun register(
        kind: Kind,
        owner: Any,
        delayUs: Int,
        callback: (SensorEvent) -> Unit,
    ): Boolean = synchronized(lock) {
        if (sensor(kind) == null) return@synchronized false
        val list = consumers.getOrPut(kind) { mutableListOf() }
        list.removeAll { it.owner === owner }
        list.add(Consumer(owner, normalizeDelayUs(delayUs), callback))
        apply(kind)
        true
    }

    /** 注销 [owner] 在所有传感器上的注册。 */
    fun unregister(owner: Any) {
        synchronized(lock) {
            for ((kind, list) in consumers) {
                if (list.removeAll { it.owner === owner }) {
                    apply(kind)
                }
            }
        }
    }

    /**
     * `SENSOR_DELAY_*` 是 0～3 的速率常量，不是微秒。统一折算成微秒后再用于节流和注册，
     * 否则 `SENSOR_DELAY_NORMAL` 会被当成 3us，慢消费者等于没有节流。
     */
    private fun normalizeDelayUs(rate: Int): Int = when (rate) {
        SensorManager.SENSOR_DELAY_FASTEST -> 0
        SensorManager.SENSOR_DELAY_GAME -> 20_000
        SensorManager.SENSOR_DELAY_UI -> 66_667
        SensorManager.SENSOR_DELAY_NORMAL -> 200_000
        else -> rate.coerceAtLeast(0)
    }

    private fun apply(kind: Kind) {
        synchronized(lock) {
            val sensor = sensor(kind) ?: return
            val list = consumers[kind].orEmpty()
            if (list.isEmpty()) {
                if (registeredDelayUs.remove(kind) != null) {
                    sensorManager.unregisterListener(listener, sensor)
                }
                return
            }
            val delayUs = list.minOf { it.delayUs }
            if (registeredDelayUs[kind] == delayUs) return
            sensorManager.unregisterListener(listener, sensor)
            if (sensorManager.registerListener(listener, sensor, delayUs, handler)) {
                registeredDelayUs[kind] = delayUs
            } else {
                registeredDelayUs.remove(kind)
            }
        }
    }

    private fun sensor(kind: Kind): Sensor? = synchronized(lock) {
        // 只缓存非 null：开机初期 SensorManager 可能还没就绪，缓存 null 会让该传感器永久不可用。
        sensorCache[kind]?.let { return@synchronized it }
        val resolved = when (kind) {
            Kind.ACCELEROMETER -> sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            Kind.GYROSCOPE -> sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            Kind.PROXIMITY -> sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            Kind.LIGHT -> sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT, true)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        }
        if (resolved != null) sensorCache[kind] = resolved
        resolved
    }

    private fun kindOf(type: Int): Kind? = when (type) {
        Sensor.TYPE_ACCELEROMETER -> Kind.ACCELEROMETER
        Sensor.TYPE_GYROSCOPE -> Kind.GYROSCOPE
        Sensor.TYPE_PROXIMITY -> Kind.PROXIMITY
        Sensor.TYPE_LIGHT -> Kind.LIGHT
        else -> null
    }

    private companion object {
        private const val TAG = "MotionSensorHub"
    }
}
