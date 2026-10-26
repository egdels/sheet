// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.ocr

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object DbPostprocess {
    const val THRESH = 0.3
    const val BOX_THRESH = 0.6
    const val UNCLIP_RATIO = 1.5
    const val MIN_AREA = 16
    const val MIN_SIDE = 3
    private const val THICKNESS_PERCENTILE = 0.9
    private const val MIN_TILT_DEG = 0.3
    private const val MAX_TILT_DEG = 30.0
    private const val MIN_ELONGATION = 3.0

    fun process(prob: FloatArray, width: Int, height: Int): List<TextBox> {
        require(prob.size == width * height) { "map ${prob.size} != ${width}x$height" }
        val visited = BooleanArray(prob.size)
        val result = mutableListOf<TextBox>()
        val stack = IntArray(prob.size)
        val pixels = IntArray(prob.size)
        for (start in prob.indices) {
            if (visited[start] || prob[start] < THRESH) continue
            var top = 0
            var n = 0
            var sum = 0.0
            stack[top++] = start
            visited[start] = true
            while (top > 0) {
                val i = stack[--top]
                pixels[n++] = i
                sum += prob[i]
                val x = i % width
                val y = i / width
                if (x + 1 < width) top = push(i + 1, prob, visited, stack, top)
                if (x > 0) top = push(i - 1, prob, visited, stack, top)
                if (y + 1 < height) top = push(i + width, prob, visited, stack, top)
                if (y > 0) top = push(i - width, prob, visited, stack, top)
            }
            box(pixels, n, sum / n, width)?.let(result::add)
        }
        return result
    }

    private fun push(i: Int, prob: FloatArray, visited: BooleanArray, stack: IntArray, top: Int): Int {
        if (visited[i] || prob[i] < THRESH) return top
        visited[i] = true
        stack[top] = i
        return top + 1
    }

    private fun box(pixels: IntArray, n: Int, meanProb: Double, width: Int): TextBox? {
        if (meanProb < BOX_THRESH || n < MIN_AREA) return null
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var sx = 0.0
        var sy = 0.0
        for (k in 0 until n) {
            val x = pixels[k] % width
            val y = pixels[k] / width
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
            sx += x
            sy += y
        }
        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1
        if (boxW < MIN_SIDE || boxH < MIN_SIDE) return null
        val horizontal = boxW >= boxH
        var angle = 0.0
        if (horizontal) {
            val cx = sx / n
            val cy = sy / n
            var sxx = 0.0
            var sxy = 0.0
            var syy = 0.0
            for (k in 0 until n) {
                val dx = pixels[k] % width - cx
                val dy = pixels[k] / width - cy
                sxx += dx * dx
                sxy += dx * dy
                syy += dy * dy
            }
            val a = 0.5 * atan2(2 * sxy, sxx - syy)
            val deg = abs(Math.toDegrees(a))
            if (deg in MIN_TILT_DEG..MAX_TILT_DEG) angle = a
        }
        return oriented(pixels, n, meanProb, width, horizontal, angle)
            ?: oriented(pixels, n, meanProb, width, horizontal, 0.0)
    }

    private fun oriented(pixels: IntArray, n: Int, meanProb: Double, width: Int, horizontal: Boolean, angle: Double): TextBox? {
        val ux = if (horizontal) cos(angle) else 0.0
        val uy = if (horizontal) sin(angle) else 1.0
        val vx = -uy
        val vy = ux
        var uMin = Double.MAX_VALUE
        var uMax = -Double.MAX_VALUE
        for (k in 0 until n) {
            val u = (pixels[k] % width + 0.5) * ux + (pixels[k] / width + 0.5) * uy
            uMin = min(uMin, u)
            uMax = max(uMax, u)
        }
        val cells = max(1, floor(uMax - uMin).toInt() + 1)
        val lo = DoubleArray(cells) { Double.MAX_VALUE }
        val hi = DoubleArray(cells) { -Double.MAX_VALUE }
        var vMin = Double.MAX_VALUE
        var vMax = -Double.MAX_VALUE
        for (k in 0 until n) {
            val px = pixels[k] % width + 0.5
            val py = pixels[k] / width + 0.5
            val cell = min(cells - 1, floor(px * ux + py * uy - uMin + 1e-9).toInt())
            val v = px * vx + py * vy
            lo[cell] = min(lo[cell], v)
            hi[cell] = max(hi[cell], v)
            vMin = min(vMin, v)
            vMax = max(vMax, v)
        }
        val spans = (0 until cells).filter { lo[it] != Double.MAX_VALUE }.map { hi[it] - lo[it] + 1 }.sorted()
        val thickness = max(1.0, spans[min(spans.size - 1, (THICKNESS_PERCENTILE * (spans.size - 1)).toInt())])
        val length = max(1.0, uMax - uMin + 1)
        if (angle != 0.0 && length / thickness < MIN_ELONGATION) return null
        val d = length * thickness * UNCLIP_RATIO / (2.0 * (length + thickness))
        val u0 = uMin - 0.5 - d
        val u1 = uMax + 0.5 + d
        val v0 = vMin - 0.5 - d
        val v1 = vMax + 0.5 + d
        fun at(u: Double, v: Double) = Point(u * ux + v * vx, u * uy + v * vy)
        val quad = if (horizontal) {
            Quad(at(u0, v0), at(u1, v0), at(u1, v1), at(u0, v1))
        } else {
            Quad(at(u0, v1), at(u0, v0), at(u1, v0), at(u1, v1))
        }
        return TextBox(quad, meanProb)
    }
}
