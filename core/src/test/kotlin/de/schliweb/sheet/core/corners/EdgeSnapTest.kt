// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.corners

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import de.schliweb.sheet.core.image.GrayImage
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdgeSnapTest {
    private class Synthetic(
        override val width: Int,
        override val height: Int,
        val page: Quad,
        val ruleInset: Double = 0.0,
    ) : PageImage {
        private val pixels: IntArray = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            var inside = 0
            var rule = 0
            for (sy in 0 until 4) for (sx in 0 until 4) {
                val px = x + (sx + 0.5) / 4
                val py = y + (sy + 0.5) / 4
                if (contains(px, py)) {
                    inside++
                    if (ruleInset > 0 && insideBottom(px, py) in ruleInset..ruleInset + 2.0) rule++
                }
            }
            val v = 50 + (170 * inside) / 16 - (150 * rule) / 16
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        private fun contains(x: Double, y: Double): Boolean {
            val c = page.corners
            return (0 until 4).all { i ->
                val a = c[i]
                val b = c[(i + 1) % 4]
                (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) >= 0
            }
        }

        private fun insideBottom(x: Double, y: Double): Double {
            val a = page.bottomRight
            val b = page.bottomLeft
            val len = hypot(b.x - a.x, b.y - a.y)
            return ((b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)) / len
        }

        override fun argb() = pixels
        override fun letterboxed(letterbox: Letterbox) = throw UnsupportedOperationException()
        override fun scaled(width: Int, height: Int) = throw UnsupportedOperationException()
        override fun rotated(frame: RotatedFrame) = throw UnsupportedOperationException()
        override fun render(width: Int, height: Int, affine: DoubleArray, background: Int) = throw UnsupportedOperationException()
    }

    private val truth = Quad(Point(120.3, 95.7), Point(610.2, 130.4), Point(575.8, 820.1), Point(90.6, 790.9))

    private fun maxError(a: Quad, b: Quad) = a.corners.zip(b.corners).maxOf { (p, q) -> hypot(p.x - q.x, p.y - q.y) }

    @Test
    fun snapsOffsetOutlineOntoThePage() {
        val image = Synthetic(720, 960, truth)
        val guess = Quad(Point(126.0, 101.0), Point(603.0, 124.0), Point(583.0, 828.0), Point(84.0, 797.0))
        val refined = EdgeSnap.refine(image, guess)
        assertTrue(maxError(guess, truth) > 8.0)
        assertTrue(maxError(refined, truth) < 1.0, "error ${maxError(refined, truth)}")
    }

    @Test
    fun printedRuleNearTheEdgeIsNotTheEdge() {
        val image = Synthetic(720, 960, truth, ruleInset = 8.0)
        val guess = truth.copy(
            bottomRight = Point(truth.bottomRight.x - 1.0, truth.bottomRight.y - 6.0),
            bottomLeft = Point(truth.bottomLeft.x + 1.0, truth.bottomLeft.y - 6.0),
        )
        val refined = EdgeSnap.refine(image, guess)
        assertTrue(maxError(refined, truth) < 1.0, "error ${maxError(refined, truth)}")
    }

    @Test
    fun keepsCornersWithoutEdgesNearby() {
        val flat = Synthetic(720, 960, Quad(Point(-10.0, -10.0), Point(-9.0, -10.0), Point(-9.0, -9.0), Point(-10.0, -9.0)))
        val guess = Quad(Point(126.0, 101.0), Point(603.0, 124.0), Point(583.0, 828.0), Point(84.0, 797.0))
        assertTrue(maxError(guess, EdgeSnap.refine(flat, guess)) < 1e-9)
    }

    @Test
    fun farOffOutlineMovesAtMostThreePercent() {
        val image = Synthetic(720, 960, truth)
        val far = truth.map { Point(it.x + 60.0, it.y + 60.0) }
        val refined = EdgeSnap.refine(image, far)
        val limit = 0.03 * hypot(720.0, 960.0)
        assertTrue(far.corners.zip(refined.corners).all { (a, b) -> hypot(a.x - b.x, a.y - b.y) <= limit + 1e-9 })
    }

    @Test
    fun filtersMatchOpenCvKernels() {
        val img = GrayImage(6, 3, FloatArray(18) { if (it % 6 >= 3) 100f else 0f })
        val gx = img.sobelX()
        assertEquals(listOf(0f, 0f, 400f, 400f, 0f, 0f), (0 until 6).map { gx[it, 1] })
        assertEquals(0f, img.sobelY()[3, 1])
        val b = img.blurred()
        assertEquals(listOf(0f, 0f, 25f, 75f, 100f, 100f), (0 until 6).map { b[it, 1] })
    }

    @Test
    fun lineFitAndIntersection() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0)
        val ys = doubleArrayOf(1.0, 3.0, 5.0, 7.0)
        val l = EdgeSnap.fitLine(xs, ys, 4)!!
        assertEquals(0.0, l.distance(10.0, 21.0), 1e-9)
        val vertical = EdgeSnap.Line(5.0, 0.0, 0.0, 1.0)
        val p = EdgeSnap.intersect(l, vertical)!!
        assertEquals(5.0, p.x, 1e-9)
        assertEquals(11.0, p.y, 1e-9)
    }
}
