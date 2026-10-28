// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import de.schliweb.sheet.core.corners.Stillness
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class Steady(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyroscope: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val stillness = Stillness()

    fun start() {
        gyroscope?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensors?.unregisterListener(this)
        synchronized(stillness) { stillness.reset() }
    }

    suspend fun awaitStill(timeoutMs: Long) {
        if (gyroscope == null) return
        synchronized(stillness) { stillness.reset() }
        withTimeoutOrNull(timeoutMs) {
            while (!synchronized(stillness) { stillness.isStill(SystemClock.elapsedRealtimeNanos()) }) delay(POLL_MS)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(stillness) { stillness.update(event.timestamp, event.values[0], event.values[1], event.values[2]) }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private companion object {
        const val POLL_MS = 15L
    }
}
