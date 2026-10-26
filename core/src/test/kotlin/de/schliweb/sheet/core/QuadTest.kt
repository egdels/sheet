// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuadTest {
    private val tl = Point(10.0, 12.0)
    private val tr = Point(200.0, 5.0)
    private val br = Point(210.0, 300.0)
    private val bl = Point(4.0, 290.0)

    @Test
    fun ordersShuffledCorners() {
        val expected = Quad(tl, tr, br, bl)
        for (shuffled in listOf(listOf(br, tl, bl, tr), listOf(bl, br, tr, tl), listOf(tr, bl, tl, br))) {
            assertEquals(expected, Quad.of(shuffled))
        }
    }

    @Test
    fun areaOfFullImage() {
        assertEquals(640.0 * 480.0, Quad.full(640, 480).area)
    }

    @Test
    fun convexity() {
        assertTrue(Quad(tl, tr, br, bl).isConvex)
        assertFalse(Quad(tl, br, tr, bl).isConvex)
        assertFalse(Quad(tl, tr, Point(60.0, 60.0), bl).isConvex)
    }
}

class QuadPlausibilityTest {
    private val page = Quad(Point(10.0, 10.0), Point(90.0, 12.0), Point(88.0, 70.0), Point(12.0, 68.0))

    @Test
    fun pageOrderIsPlausible() {
        assertTrue(page.isPlausibleIn(100, 80))
    }

    @Test
    fun counterClockwiseIsConvexButNotPlausible() {
        val ccw = Quad(page.topLeft, page.bottomLeft, page.bottomRight, page.topRight)
        assertTrue(ccw.isConvex)
        assertFalse(ccw.isPlausibleIn(100, 80))
    }

    @Test
    fun farOutsideIsNotPlausible() {
        assertFalse(page.copy(topRight = Point(126.0, 12.0)).isPlausibleIn(100, 80))
        assertTrue(page.copy(topRight = Point(124.0, 12.0)).isPlausibleIn(100, 80))
    }

    @Test
    fun crossedEdgesIntersect() {
        assertFalse(page.selfIntersects)
        assertTrue(Quad(page.topLeft, page.bottomRight, page.topRight, page.bottomLeft).selfIntersects)
    }
}
