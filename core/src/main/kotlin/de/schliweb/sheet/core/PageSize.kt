// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object PageSize {
    private const val PHONE_FOCAL = 0.75

    fun of(quad: Quad, imageWidth: Int, imageHeight: Int): Pair<Int, Int> {
        val (top, right, bottom, left) = quad.edgeLengths
        val longest = maxOf(top, right, bottom, left)
        if (longest < 1.0) return 1 to 1
        val ratio = aspectRatio(quad, imageWidth, imageHeight)
            ?: return (max(top, bottom).roundToInt() + 1) to (max(left, right).roundToInt() + 1)
        val landscape = (top + bottom) >= (left + right)
        val w = if (landscape) longest else longest * ratio
        val h = if (landscape) longest / ratio else longest
        return (max(1, w.roundToInt()) + 1) to (max(1, h.roundToInt()) + 1)
    }

    fun aspectRatio(quad: Quad, imageWidth: Int, imageHeight: Int): Double? {
        val cx = imageWidth / 2.0
        val cy = imageHeight / 2.0
        val m1 = quad.topLeft.let { Point(it.x - cx, it.y - cy) }
        val m2 = quad.topRight.let { Point(it.x - cx, it.y - cy) }
        val m3 = quad.bottomLeft.let { Point(it.x - cx, it.y - cy) }
        val m4 = quad.bottomRight.let { Point(it.x - cx, it.y - cy) }
        val k2den = det(m2, m4, m3)
        val k3den = det(m3, m4, m2)
        if (abs(k2den) < 1e-9 || abs(k3den) < 1e-9) return null
        val k2 = det(m1, m4, m3) / k2den
        val k3 = det(m1, m4, m2) / k3den
        val n2 = doubleArrayOf(k2 * m2.x - m1.x, k2 * m2.y - m1.y, k2 - 1.0)
        val n3 = doubleArrayOf(k3 * m3.x - m1.x, k3 * m3.y - m1.y, k3 - 1.0)
        if (abs(n2[2]) < 1e-6 || abs(n3[2]) < 1e-6) return null
        var f2 = -(n2[0] * n3[0] + n2[1] * n3[1]) / (n2[2] * n3[2])
        if (!f2.isFinite() || f2 <= 0.0) f2 = (PHONE_FOCAL * max(imageWidth, imageHeight)).let { it * it }
        val num = n2[0] * n2[0] + n2[1] * n2[1] + n2[2] * n2[2] * f2
        val den = n3[0] * n3[0] + n3[1] * n3[1] + n3[2] * n3[2] * f2
        if (den < 1e-12) return null
        val ratio = sqrt(num / den)
        return ratio.takeIf { it.isFinite() && it in 0.01..100.0 }
    }

    private fun det(a: Point, b: Point, c: Point): Double =
        (a.y - b.y) * c.x + (b.x - a.x) * c.y + (a.x * b.y - a.y * b.x)
}
