// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.corners

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

class LiveTracker(
    private val minCutoff: Double = 0.3,
    private val beta: Double = 0.01,
    private val derivativeCutoff: Double = 1.0,
) {
    private val value = DoubleArray(8)
    private val speed = DoubleArray(8)
    private var lastMillis = 0L
    private var filtering = false
    private var shown = false
    private var validRun = 0
    private var invalidRun = 0

    fun update(detection: Detection?, width: Int, height: Int, millis: Long): Quad? {
        val quad = detection?.takeIf { it.confidence >= MIN_CONFIDENCE }?.quad?.takeIf { it.isPlausibleIn(width, height) }
        if (quad == null) {
            invalidRun++
            validRun = 0
            if (invalidRun >= HIDE_AFTER) {
                shown = false
                filtering = false
            }
            return if (shown) current() else null
        }
        invalidRun = 0
        validRun++
        val raw = quad.corners.flatMap { listOf(it.x, it.y) }
        val dt = (millis - lastMillis) / 1000.0
        if (!filtering || jump(raw) > JUMP_FRACTION * hypot(width.toDouble(), height.toDouble())) {
            raw.forEachIndexed { i, v -> value[i] = v }
            speed.fill(0.0)
            filtering = true
        } else if (dt > 0) {
            val rate = 1.0 / dt
            for (i in 0 until 8) {
                val dx = (raw[i] - value[i]) * rate
                speed[i] += alpha(rate, derivativeCutoff) * (dx - speed[i])
                value[i] += alpha(rate, minCutoff + beta * abs(speed[i])) * (raw[i] - value[i])
            }
        }
        lastMillis = millis
        if (validRun >= SHOW_AFTER) shown = true
        return if (shown) current() else null
    }

    fun reset() {
        filtering = false
        shown = false
        validRun = 0
        invalidRun = 0
    }

    private fun current() = Quad(
        Point(value[0], value[1]),
        Point(value[2], value[3]),
        Point(value[4], value[5]),
        Point(value[6], value[7]),
    )

    private fun jump(raw: List<Double>): Double = (0 until 4).maxOf { hypot(raw[2 * it] - value[2 * it], raw[2 * it + 1] - value[2 * it + 1]) }

    private fun alpha(rate: Double, cutoff: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau * rate)
    }

    companion object {
        const val SHOW_AFTER = 2
        const val HIDE_AFTER = 3

        const val MIN_CONFIDENCE = 0.1
        private const val JUMP_FRACTION = 0.25
    }
}
