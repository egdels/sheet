// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.eval

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import kotlin.test.Test
import kotlin.test.assertEquals

class QuadErrorTest {
    private val square = Quad(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 100.0), Point(0.0, 100.0))

    @Test
    fun identicalIsPerfect() {
        assertEquals(QuadError(0.0, 0.0, 1.0, 0), QuadError.of(square, square, 300, 400))
    }

    @Test
    fun rotatedOrderIsFoundAndReported() {
        val shifted = Quad(square.bottomLeft, square.topLeft, square.topRight, square.bottomRight)
        val e = QuadError.of(shifted, square, 300, 400)
        assertEquals(0.0, e.meanRel)
        assertEquals(1, e.orderShift)
    }

    @Test
    fun halfOverlapIou() {
        val moved = square.map { Point(it.x + 50.0, it.y) }
        assertEquals(1.0 / 3.0, QuadError.iou(moved.corners, square.corners), 1e-12)
    }

    @Test
    fun nearestRankPercentile() {
        val v = (1..10).map { it.toDouble() }
        assertEquals(5.0, percentile(v, 0.5))
        assertEquals(9.0, percentile(v, 0.9))
        assertEquals(10.0, percentile(v, 1.0))
    }
}
