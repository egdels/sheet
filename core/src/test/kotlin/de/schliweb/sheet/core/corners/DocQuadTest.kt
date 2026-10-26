// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.corners

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocQuadTest {
    private fun output(peaks: List<Point>, mask: IntArray?): DocQuadOutput {
        val heat = FloatArray(4 * DocQuad.CELLS) { -8f }
        for ((c, p) in peaks.withIndex()) {
            for (y in 0 until 64) for (x in 0 until 64) {
                val d2 = (x + 0.5 - p.x) * (x + 0.5 - p.x) + (y + 0.5 - p.y) * (y + 0.5 - p.y)
                heat[c * DocQuad.CELLS + y * 64 + x] = (-8.0 + 14.0 * exp(-d2 / 2.0)).toFloat()
            }
        }
        val m = FloatArray(DocQuad.CELLS) { -6f }
        if (mask != null) {
            for (y in mask[1]..mask[3]) for (x in mask[0]..mask[2]) m[y * 64 + x] = 6f
        }
        return DocQuadOutput(heat, m)
    }

    private val page = listOf(Point(10.0, 8.0), Point(54.0, 8.0), Point(54.0, 58.0), Point(10.0, 58.0))

    @Test
    fun peaksAtSubCellPrecision() {
        val off = page.map { Point(it.x + 0.3, it.y - 0.2) }
        val corners = DocQuad.cornerPeaks(output(off, null)).corners
        for ((e, a) in off.zip(corners)) {
            assertEquals(e.x * 4, a.x, 0.4)
            assertEquals(e.y * 4, a.y, 0.4)
        }
    }

    @Test
    fun cleanPageIsConfidentAndUsesTheCorners() {
        val out = output(page, intArrayOf(10, 8, 53, 57))
        val d = DocQuad.postprocess(out, Letterbox.of(256, 256))
        assertFalse(d.fromMask)
        assertTrue(d.confidence > 0.9, "confidence ${d.confidence}")
        assertEquals(Point(40.0, 32.0), d.quad.topLeft.let { Point(Math.round(it.x).toDouble(), Math.round(it.y).toDouble()) })
        assertTrue(d.quad.isPlausibleIn(256, 256))
    }

    @Test
    fun emptyMaskFallsBackToTheCorners() {
        val out = output(page, null)
        assertNull(DocQuad.maskQuad(out))
        val d = DocQuad.postprocess(out, Letterbox.of(256, 256))
        assertFalse(d.fromMask)
        assertEquals(0.0, d.confidence)
    }

    @Test
    fun maskReplacesCrossedCorners() {
        val crossed = listOf(page[0], page[2], page[1], page[3])
        val out = output(crossed, intArrayOf(10, 8, 53, 57))
        val d = DocQuad.postprocess(out, Letterbox.of(256, 256))
        assertTrue(d.fromMask)
        assertTrue(d.quad.isPlausibleIn(256, 256))
        assertFalse(d.corners.isPlausibleIn(256, 256))
    }

    @Test
    fun maskQuadIsTheMaskBox() {
        val q = assertNotNull(DocQuad.maskQuad(output(page, intArrayOf(10, 8, 53, 57))))
        assertEquals(Quad(Point(42.0, 34.0), Point(214.0, 34.0), Point(214.0, 230.0), Point(42.0, 230.0)).corners.toString(),
            q.corners.map { Point(Math.round(it.x).toDouble(), Math.round(it.y).toDouble()) }.toString())
    }

    @Test
    fun penaltyRanksBrokenOutlines() {
        val good = Quad(Point(40.0, 32.0), Point(216.0, 32.0), Point(216.0, 232.0), Point(40.0, 232.0))
        assertEquals(0.0, DocQuad.geometryPenalty(good))
        val outside = good.copy(topLeft = Point(-30.0, 32.0))
        assertTrue(DocQuad.geometryPenalty(outside) >= 1e5)
        val crossed = Quad(good.topLeft, good.bottomRight, good.topRight, good.bottomLeft)
        assertTrue(DocQuad.geometryPenalty(crossed) >= 1e6)
    }
}
