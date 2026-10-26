// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.corners

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class PageOutline(val quad: Quad, val confidence: Double, val angle: Int)

class RotatedFrame internal constructor(
    val angle: Int,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val width: Int,
    val height: Int,
) {
    private val radians = Math.toRadians(angle.toDouble())

    val affine: DoubleArray
        get() {
            val c = cos(radians)
            val s = sin(radians)
            val cx = scaledWidth / 2.0
            val cy = scaledHeight / 2.0
            return doubleArrayOf(c, -s, width / 2.0 - c * cx + s * cy, s, c, height / 2.0 - s * cx - c * cy)
        }

    fun forward(p: Point): Point {
        val dx = p.x - scaledWidth / 2.0
        val dy = p.y - scaledHeight / 2.0
        return Point(cos(radians) * dx - sin(radians) * dy + width / 2.0, sin(radians) * dx + cos(radians) * dy + height / 2.0)
    }

    fun back(p: Point): Point {
        val dx = p.x - width / 2.0
        val dy = p.y - height / 2.0
        return Point(cos(radians) * dx + sin(radians) * dy + scaledWidth / 2.0, -sin(radians) * dx + cos(radians) * dy + scaledHeight / 2.0)
    }
}

object RotationTta {
    val ANGLES = listOf(90, -30, 30, -60, 60)
    const val ACCEPT_CONFIDENCE = 0.5
    const val EARLY_EXIT_CONFIDENCE = 0.7
    const val MIN_ROTATED_CONFIDENCE = 0.1
    const val MIN_ROTATED_MARGIN = 0.15
    private const val MAX_ROTATION_EDGE = 720

    fun frame(sourceWidth: Int, sourceHeight: Int, angle: Int): RotatedFrame {
        val s = scale(sourceWidth, sourceHeight)
        val w = (sourceWidth * s).roundToInt()
        val h = (sourceHeight * s).roundToInt()
        val a = Math.toRadians(angle.toDouble())
        val c = abs(cos(a))
        val sn = abs(sin(a))
        return RotatedFrame(angle, w, h, ceil(w * c + h * sn - 1e-9).toInt(), ceil(w * sn + h * c - 1e-9).toInt())
    }

    private fun scale(w: Int, h: Int): Double = min(1.0, MAX_ROTATION_EDGE / max(w, h).toDouble())

    fun detect(
        sourceWidth: Int,
        sourceHeight: Int,
        plain: () -> Detection?,
        rotated: (RotatedFrame) -> Detection?,
    ): PageOutline? {
        val d0 = plain()
        val plainOk = d0 != null && d0.quad.isPlausibleIn(sourceWidth, sourceHeight)
        val conf0 = if (d0 != null && d0.corners.isPlausibleIn(sourceWidth, sourceHeight)) d0.confidence else -1.0
        if (plainOk && conf0 >= ACCEPT_CONFIDENCE) return PageOutline(d0.quad, d0.confidence, 0)

        val toSource = 1.0 / scale(sourceWidth, sourceHeight)
        var bestQuad: Quad? = null
        var bestConf = conf0
        var bestAngle = 0
        for (angle in ANGLES) {
            val frame = frame(sourceWidth, sourceHeight, angle)
            val r = rotated(frame) ?: continue
            val quad = Quad.of(r.corners.corners.map { frame.back(it).let { p -> Point(p.x * toSource, p.y * toSource) } })
            val conf = if (quad.isPlausibleIn(sourceWidth, sourceHeight)) r.confidence else -1.0
            if (conf > bestConf) {
                bestConf = conf
                bestQuad = quad
                bestAngle = angle
            }
            if (bestConf >= EARLY_EXIT_CONFIDENCE) break
        }
        if (bestQuad != null && acceptsRotated(conf0, bestConf)) return PageOutline(bestQuad, bestConf, bestAngle)
        return if (plainOk) PageOutline(d0.quad, d0.confidence, 0) else null
    }

    internal fun acceptsRotated(plainConfidence: Double, rotatedConfidence: Double): Boolean {
        if (rotatedConfidence < MIN_ROTATED_CONFIDENCE) return false
        return plainConfidence < 0.0 || rotatedConfidence >= plainConfidence + MIN_ROTATED_MARGIN
    }
}
