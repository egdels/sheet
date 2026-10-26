// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.corners

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class DocQuadOutput(val heatmaps: FloatArray, val mask: FloatArray) {
    init {
        require(heatmaps.size == 4 * DocQuad.CELLS) { "heatmaps must be [4][64][64], got ${heatmaps.size}" }
        require(mask.size == DocQuad.CELLS) { "mask must be [64][64], got ${mask.size}" }
    }

    internal fun heat(c: Int, x: Int, y: Int): Float = heatmaps[c * DocQuad.CELLS + y * DocQuad.GRID + x]

    internal fun mask(x: Int, y: Int): Float = mask[y * DocQuad.GRID + x]
}

data class Detection(val quad: Quad, val corners: Quad, val fromMask: Boolean, val confidence: Double)

object DocQuad {
    const val INPUT_SIZE = 256
    const val GRID = 64
    const val CELLS = GRID * GRID
    private const val CELL = (INPUT_SIZE / GRID).toDouble()

    fun postprocess(out: DocQuadOutput, letterbox: Letterbox): Detection {
        val corners = cornerPeaks(out)
        val fromMask = maskQuad(out)
        val useMask = fromMask != null && preferMask(corners, fromMask, out)
        val chosen = if (useMask) fromMask else corners
        return Detection(
            quad = letterbox.toSource(chosen),
            corners = letterbox.toSource(corners),
            fromMask = useMask,
            confidence = minPeakProbability(out) * cornerMaskIou(corners, out),
        )
    }

    internal fun cornerPeaks(out: DocQuadOutput): Quad {
        val p = List(4) { peak(out, it) }
        return Quad(p[0], p[1], p[2], p[3])
    }

    private fun peak(out: DocQuadOutput, c: Int): Point {
        var best = -Float.MAX_VALUE
        var bx = 0
        var by = 0
        for (y in 0 until GRID) for (x in 0 until GRID) {
            val v = out.heat(c, x, y)
            if (v > best) {
                best = v
                bx = x
                by = y
            }
        }
        fun offset(before: Double, at: Double, after: Double): Double? {
            val denom = before - 2.0 * at + after
            if (denom >= -1e-12) return null
            return (0.5 * (before - after) / denom).coerceIn(-0.5, 0.5)
        }
        val dx = if (bx in 1 until GRID - 1) {
            offset(out.heat(c, bx - 1, by).toDouble(), out.heat(c, bx, by).toDouble(), out.heat(c, bx + 1, by).toDouble())
        } else {
            null
        }
        val dy = if (by in 1 until GRID - 1) {
            offset(out.heat(c, bx, by - 1).toDouble(), out.heat(c, bx, by).toDouble(), out.heat(c, bx, by + 1).toDouble())
        } else {
            null
        }
        if (dx != null || dy != null) {
            return Point((bx + 0.5 + (dx ?: 0.0)) * CELL, (by + 0.5 + (dy ?: 0.0)) * CELL)
        }
        val x0 = max(0, bx - 2)
        val x1 = min(GRID - 1, bx + 2)
        val y0 = max(0, by - 2)
        val y1 = min(GRID - 1, by + 2)
        var maxLogit = Double.NEGATIVE_INFINITY
        for (y in y0..y1) for (x in x0..x1) maxLogit = max(maxLogit, out.heat(c, x, y).toDouble())
        var sumW = 0.0
        var sumX = 0.0
        var sumY = 0.0
        for (y in y0..y1) for (x in x0..x1) {
            val w = exp(out.heat(c, x, y) - maxLogit)
            sumW += w
            sumX += w * (x + 0.5)
            sumY += w * (y + 0.5)
        }
        if (sumW == 0.0 || !sumW.isFinite()) return Point((bx + 0.5) * CELL, (by + 0.5) * CELL)
        return Point(sumX / sumW * CELL, sumY / sumW * CELL)
    }

    internal fun maskQuad(out: DocQuadOutput): Quad? {
        var count = 0
        var sumX = 0.0
        var sumY = 0.0
        forEachMaskCell(out) { x, y ->
            count++
            sumX += x
            sumY += y
        }
        if (count == 0) return null
        val cx = sumX / count
        val cy = sumY / count
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        forEachMaskCell(out) { x, y ->
            val dx = x - cx
            val dy = y - cy
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        sxx /= count
        sxy /= count
        syy /= count
        val trace = sxx + syy
        if (!trace.isFinite() || trace < 1e-12) return null
        val det = sxx * syy - sxy * sxy
        val lambda1 = trace / 2.0 + sqrt(max(0.0, trace * trace / 4.0 - det))
        var v1x: Double
        var v1y: Double
        if (kotlin.math.abs(sxy) > 1e-12) {
            v1x = lambda1 - syy
            v1y = sxy
        } else if (sxx >= syy) {
            v1x = 1.0
            v1y = 0.0
        } else {
            v1x = 0.0
            v1y = 1.0
        }
        val n = hypot(v1x, v1y)
        if (n == 0.0 || !n.isFinite()) return null
        v1x /= n
        v1y /= n
        val v2x = -v1y
        val v2y = v1x
        var uMin = Double.POSITIVE_INFINITY
        var uMax = Double.NEGATIVE_INFINITY
        var vMin = Double.POSITIVE_INFINITY
        var vMax = Double.NEGATIVE_INFINITY
        forEachMaskCell(out) { x, y ->
            val px = x - cx
            val py = y - cy
            val u = px * v1x + py * v1y
            val v = px * v2x + py * v2y
            if (u < uMin) uMin = u
            if (u > uMax) uMax = u
            if (v < vMin) vMin = v
            if (v > vMax) vMax = v
        }
        if (uMax - uMin < 1e-12 || vMax - vMin < 1e-12) return null
        fun corner(u: Double, v: Double) = Point(cx + u * v1x + v * v2x, cy + u * v1y + v * v2y)
        val ordered = Quad.of(listOf(corner(uMax, vMax), corner(uMin, vMax), corner(uMin, vMin), corner(uMax, vMin)))
        return ordered.map { Point(it.x * CELL, it.y * CELL) }
    }

    private inline fun forEachMaskCell(out: DocQuadOutput, action: (Double, Double) -> Unit) {
        for (y in 0 until GRID) for (x in 0 until GRID) {
            if (sigmoid(out.mask(x, y).toDouble()) > 0.5) action(x + 0.5, y + 0.5)
        }
    }

    internal fun preferMask(corners: Quad, mask: Quad, out: DocQuadOutput): Boolean {
        val cornersPenalty = geometryPenalty(corners)
        val maskPenalty = geometryPenalty(mask)
        if (cornersPenalty >= HARD_PENALTY && maskPenalty < HARD_PENALTY) return true
        if (maskPenalty >= HARD_PENALTY) return false
        if (maxCornerDistance(corners, mask) > AGREEMENT_MAX_DISTANCE) return false
        return maskPenalty < cornersPenalty - MASK_SCORE_MARGIN
    }

    private const val HARD_PENALTY = 1e5
    private const val AGREEMENT_MAX_DISTANCE = 32.0
    private const val MASK_SCORE_MARGIN = 50.0

    private fun maxCornerDistance(a: Quad, b: Quad): Double {
        val ca = a.corners
        val cb = b.corners
        return (0 until 4).maxOf { hypot(ca[it].x - cb[it].x, ca[it].y - cb[it].y) }
    }

    internal fun geometryPenalty(q: Quad): Double {
        val c = q.corners
        if (c.any { !it.isFinite }) return 1e6
        var penalty = 0.0
        val tol = 2.0
        val lo = -tol
        val hi = INPUT_SIZE - 1.0 + tol
        fun oob(v: Double) = if (v < lo) lo - v else if (v > hi) v - hi else 0.0
        var oobSum = 0.0
        var oobMax = 0.0
        for (p in c) {
            val o = oob(p.x) + oob(p.y)
            oobSum += o
            oobMax = max(oobMax, o)
        }
        if (oobSum > 0.0) penalty += oobSum * 10.0
        if (oobMax > 16.0) penalty += 1e5 + (oobMax - 16.0) * 1000.0
        if (q.selfIntersects) penalty += 1e6
        if (!q.isConvex) penalty += 1e6
        if (!(q.area > 1.0)) penalty += 1e6
        val edges = q.edgeLengths
        val edgeMin = edges.min()
        val edgeMax = edges.max()
        if (edgeMin < 8.0) penalty += (8.0 - edgeMin) * 1000.0
        val ratio = edgeMax / max(edgeMin, 1e-9)
        if (ratio > 25.0) penalty += (ratio - 25.0) * 100.0
        return penalty
    }

    internal fun minPeakProbability(out: DocQuadOutput): Double {
        var result = 1.0
        for (c in 0 until 4) {
            var best = -Float.MAX_VALUE
            for (i in c * CELLS until (c + 1) * CELLS) if (out.heatmaps[i] > best) best = out.heatmaps[i]
            result = min(result, sigmoid(best.toDouble()))
        }
        return result
    }

    internal fun cornerMaskIou(quad: Quad, out: DocQuadOutput): Double {
        val c = quad.corners.map { Point(it.x / CELL, it.y / CELL) }
        var inter = 0
        var union = 0
        val xs = DoubleArray(4)
        for (y in 0 until GRID) {
            val cy = y + 0.5
            var n = 0
            for (i in 0 until 4) {
                val a = c[i]
                val b = c[(i + 1) % 4]
                if ((a.y > cy) != (b.y > cy)) xs[n++] = a.x + (cy - a.y) * (b.x - a.x) / (b.y - a.y)
            }
            xs.sort(0, n)
            var k = 0
            var inside = false
            for (x in 0 until GRID) {
                val cx = x + 0.5
                while (k < n && xs[k] <= cx) {
                    inside = !inside
                    k++
                }
                val inMask = out.mask(x, y) > 0.0f
                if (inside && inMask) inter++
                if (inside || inMask) union++
            }
        }
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
