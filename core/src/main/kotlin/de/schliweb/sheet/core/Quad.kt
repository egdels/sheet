// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

@Serializable
data class Point(val x: Double, val y: Double) {
    val isFinite: Boolean get() = x.isFinite() && y.isFinite()
}

@Serializable
data class Quad(val topLeft: Point, val topRight: Point, val bottomRight: Point, val bottomLeft: Point) {

    val corners: List<Point> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun map(transform: (Point) -> Point): Quad =
        Quad(transform(topLeft), transform(topRight), transform(bottomRight), transform(bottomLeft))

    fun withCorner(index: Int, p: Point): Quad = when (index) {
        0 -> copy(topLeft = p)
        1 -> copy(topRight = p)
        2 -> copy(bottomRight = p)
        3 -> copy(bottomLeft = p)
        else -> throw IndexOutOfBoundsException("corner $index")
    }

    fun clampedTo(width: Int, height: Int): Quad =
        map { Point(it.x.coerceIn(0.0, width.toDouble()), it.y.coerceIn(0.0, height.toDouble())) }

    val area: Double
        get() {
            val c = corners
            var sum = 0.0
            for (i in 0 until 4) {
                val a = c[i]
                val b = c[(i + 1) % 4]
                sum += a.x * b.y - b.x * a.y
            }
            return abs(0.5 * sum)
        }

    val edgeLengths: List<Double>
        get() {
            val c = corners
            return List(4) { hypot(c[(it + 1) % 4].x - c[it].x, c[(it + 1) % 4].y - c[it].y) }
        }

    val isConvex: Boolean get() = turnSign(COLLINEAR_EPS) != 0

    val selfIntersects: Boolean
        get() = segmentsIntersect(topLeft, topRight, bottomRight, bottomLeft) ||
            segmentsIntersect(topRight, bottomRight, bottomLeft, topLeft)

    fun isPlausibleIn(width: Int, height: Int): Boolean {
        for (p in corners) {
            if (!p.isFinite) return false
            if (p.x < -width * 0.25 || p.x > width * 1.25) return false
            if (p.y < -height * 0.25 || p.y > height * 1.25) return false
        }
        return turnSign(0.0) > 0
    }

    private fun turnSign(eps: Double): Int {
        val c = corners
        var sign = 0
        for (i in 0 until 4) {
            val cross = orient(c[i], c[(i + 1) % 4], c[(i + 2) % 4])
            if (!cross.isFinite() || abs(cross) <= eps) return 0
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return 0
        }
        return sign
    }

    companion object {
        private const val COLLINEAR_EPS = 1e-9

        fun of(points: List<Point>): Quad {
            require(points.size == 4) { "a quad has four corners, got ${points.size}" }
            var cx = 0.0
            var cy = 0.0
            for (p in points) {
                cx += p.x
                cy += p.y
            }
            cx /= 4.0
            cy /= 4.0
            val clockwise = points.sortedBy { atan2(it.y - cy, it.x - cx) }
            val first = clockwise.indices.minBy { clockwise[it].x + clockwise[it].y }
            val c = List(4) { clockwise[(first + it) % 4] }
            return Quad(c[0], c[1], c[2], c[3])
        }

        fun full(width: Int, height: Int, inset: Double = 0.0): Quad {
            val l = width * inset
            val t = height * inset
            return Quad(
                Point(l, t),
                Point(width - l, t),
                Point(width - l, height - t),
                Point(l, height - t),
            )
        }
    }
}

internal fun orient(a: Point, b: Point, c: Point): Double = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

internal fun onSegment(a: Point, b: Point, p: Point, eps: Double): Boolean {
    if (abs(orient(a, b, p)) > eps) return false
    return min(a.x, b.x) - eps <= p.x && p.x <= max(a.x, b.x) + eps &&
        min(a.y, b.y) - eps <= p.y && p.y <= max(a.y, b.y) + eps
}

private fun segmentsIntersect(a: Point, b: Point, c: Point, d: Point): Boolean {
    val eps = 1e-9
    fun sign(v: Double) = if (v > eps) 1 else if (v < -eps) -1 else 0
    val s1 = sign(orient(a, b, c))
    val s2 = sign(orient(a, b, d))
    val s3 = sign(orient(c, d, a))
    val s4 = sign(orient(c, d, b))
    if (s1 == 0 && onSegment(a, b, c, eps)) return true
    if (s2 == 0 && onSegment(a, b, d, eps)) return true
    if (s3 == 0 && onSegment(c, d, a, eps)) return true
    if (s4 == 0 && onSegment(c, d, b, eps)) return true
    return s1 * s2 < 0 && s3 * s4 < 0
}
