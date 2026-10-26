// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.corners

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import de.schliweb.sheet.core.image.Gradients
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object EdgeSnap {
    const val WORK_MAX_EDGE = 1600
    private const val SAMPLES_PER_EDGE = 48
    private const val EDGE_END_MARGIN = 0.10
    private const val MIN_VALID = 24
    private const val MIN_GRADIENT = 40.0
    private const val NEAREST_PEAK_FRACTION = 0.5
    private const val MAX_MEDIAN_RESIDUAL = 3.0
    private const val SEARCH_RADIUS_FRACTION = 0.02
    private const val MAX_CORNER_SHIFT_FRACTION = 0.03
    private const val LINE_PAIR_DISTANCE = 4
    private const val LINE_PAIR_FRACTION = 0.5

    fun refine(image: PageImage, quad: Quad): Quad {
        if (quad.corners.any { !it.isFinite } || image.width <= 2 || image.height <= 2) return quad
        val s = min(1.0, WORK_MAX_EDGE / max(image.width, image.height).toDouble())
        val work = if (s < 1.0) image.scaled(max(2, (image.width * s).roundToInt()), max(2, (image.height * s).roundToInt())) else image
        val refined = refine(Gradients(work.width, work.height, work.argb()), quad.map { Point(it.x * s, it.y * s) })
        return refined.map { Point(it.x / s, it.y / s) }
    }

    internal fun refine(g: Gradients, quad: Quad): Quad {
        val w = g.width
        val h = g.height
        val diag = hypot(w.toDouble(), h.toDouble())
        val searchRadius = (diag * SEARCH_RADIUS_FRACTION).coerceIn(4.0, 48.0)
        val maxShift = diag * MAX_CORNER_SHIFT_FRACTION
        val c = quad.corners
        val lines = Array(4) { i ->
            val a = c[i]
            val b = c[(i + 1) % 4]
            snapEdge(g, a, b, searchRadius) ?: run {
                val len = hypot(b.x - a.x, b.y - a.y)
                if (len < 1e-6) return quad
                Line(a.x, a.y, (b.x - a.x) / len, (b.y - a.y) / len)
            }
        }
        val out = List(4) { i ->
            val p = intersect(lines[(i + 3) % 4], lines[i])
            if (p == null || hypot(p.x - c[i].x, p.y - c[i].y) > maxShift) c[i] else p
        }
        val refined = Quad(out[0], out[1], out[2], out[3])
        return if (refined.isPlausibleIn(w, h)) refined else quad
    }

    internal data class Line(val px: Double, val py: Double, val dx: Double, val dy: Double) {
        fun distance(x: Double, y: Double): Double = -(x - px) * dy + (y - py) * dx
    }

    private fun snapEdge(g: Gradients, a: Point, b: Point, searchRadius: Double): Line? {
        val ex = b.x - a.x
        val ey = b.y - a.y
        val len = hypot(ex, ey)
        if (len < 8.0) return null
        val nx = -ey / len
        val ny = ex / len
        val r = ceil(searchRadius).toInt()
        val profile = DoubleArray(2 * r + 1)
        val signed = DoubleArray(2 * r + 1)
        val xs = DoubleArray(SAMPLES_PER_EDGE)
        val ys = DoubleArray(SAMPLES_PER_EDGE)
        var count = 0
        for (s in 0 until SAMPLES_PER_EDGE) {
            val t = EDGE_END_MARGIN + (1.0 - 2.0 * EDGE_END_MARGIN) * s / (SAMPLES_PER_EDGE - 1)
            val px = a.x + t * ex
            val py = a.y + t * ey
            var maxResp = -1.0
            for (u in -r..r) {
                val ix = Math.round(px + u * nx).toInt()
                val iy = Math.round(py + u * ny).toInt()
                val grad = if (ix < 1 || iy < 1 || ix >= g.width - 1 || iy >= g.height - 1) {
                    Double.NaN
                } else {
                    g.x(ix, iy) * nx + g.y(ix, iy) * ny
                }
                val resp = if (grad.isNaN()) -1.0 else abs(grad)
                profile[u + r] = resp
                signed[u + r] = if (grad.isNaN()) 0.0 else grad
                if (resp > maxResp) maxResp = resp
            }
            if (maxResp < MIN_GRADIENT) continue
            val accept = max(MIN_GRADIENT, NEAREST_PEAK_FRACTION * maxResp)
            var bestU = Int.MIN_VALUE
            for (k in 1 until profile.size - 1) {
                val v = profile[k]
                if (v < accept || v < profile[k - 1] || v < profile[k + 1]) continue
                if (isLine(signed, k, v)) continue
                if (bestU == Int.MIN_VALUE || abs(k - r) < abs(bestU)) bestU = k - r
            }
            if (bestU == Int.MIN_VALUE) continue
            var u = bestU.toDouble()
            val k = bestU + r
            val before = profile[k - 1]
            val after = profile[k + 1]
            if (before >= 0 && after >= 0) {
                val denom = before - 2.0 * profile[k] + after
                if (abs(denom) > 1e-9) {
                    val offset = 0.5 * (before - after) / denom
                    if (offset in -1.0..1.0) u += offset
                }
            }
            xs[count] = px + u * nx
            ys[count] = py + u * ny
            count++
        }
        if (count < MIN_VALID) return null
        val line = fitLine(xs, ys, count) ?: return null
        val residuals = DoubleArray(count) { abs(line.distance(xs[it], ys[it])) }
        val median = residuals.sorted().let { if (count % 2 == 1) it[count / 2] else 0.5 * (it[count / 2 - 1] + it[count / 2]) }
        if (median > MAX_MEDIAN_RESIDUAL) return null
        val threshold = max(1.5, 3.0 * median)
        val keep = (0 until count).filter { residuals[it] <= threshold }
        if (keep.size < MIN_VALID) return null
        return fitLine(DoubleArray(keep.size) { xs[keep[it]] }, DoubleArray(keep.size) { ys[keep[it]] }, keep.size) ?: line
    }

    private fun isLine(signed: DoubleArray, k: Int, v: Double): Boolean {
        val sign = if (signed[k] > 0) 1.0 else -1.0
        for (j in max(0, k - LINE_PAIR_DISTANCE)..min(signed.size - 1, k + LINE_PAIR_DISTANCE)) {
            if (signed[j] * sign < 0 && abs(signed[j]) >= LINE_PAIR_FRACTION * v) return true
        }
        return false
    }

    internal fun fitLine(xs: DoubleArray, ys: DoubleArray, n: Int): Line? {
        if (n < 2) return null
        var mx = 0.0
        var my = 0.0
        for (i in 0 until n) {
            mx += xs[i]
            my += ys[i]
        }
        mx /= n
        my /= n
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val cx = xs[i] - mx
            val cy = ys[i] - my
            sxx += cx * cx
            sxy += cx * cy
            syy += cy * cy
        }
        if (sxx + syy < 1e-12) return null
        val theta = 0.5 * atan2(2.0 * sxy, sxx - syy)
        return Line(mx, my, cos(theta), sin(theta))
    }

    internal fun intersect(l1: Line, l2: Line): Point? {
        val cross = l1.dx * l2.dy - l1.dy * l2.dx
        if (abs(cross) < 1e-9) return null
        val t = ((l2.px - l1.px) * l2.dy - (l2.py - l1.py) * l2.dx) / cross
        return Point(l1.px + t * l1.dx, l1.py + t * l1.dy)
    }
}
