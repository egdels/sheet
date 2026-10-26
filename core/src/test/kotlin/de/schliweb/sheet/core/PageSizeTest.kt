// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageSizeTest {
    private val w = 4000
    private val h = 3000
    private val f = 3200.0

    private fun photo(pw: Double, ph: Double, tiltX: Double, tiltY: Double, roll: Double = 0.0, distance: Double = 600.0): Quad {
        val corners = listOf(-pw / 2 to -ph / 2, pw / 2 to -ph / 2, pw / 2 to ph / 2, -pw / 2 to ph / 2).map { (x0, y0) ->
            val x1 = x0 * cos(roll) - y0 * sin(roll)
            val y1 = x0 * sin(roll) + y0 * cos(roll)
            val y2 = y1 * cos(tiltX)
            val z2 = y1 * sin(tiltX)
            val x3 = x1 * cos(tiltY) + z2 * sin(tiltY)
            val z3 = -x1 * sin(tiltY) + z2 * cos(tiltY) + distance
            Point(w / 2.0 + f * x3 / z3, h / 2.0 + f * y2 / z3)
        }
        return Quad(corners[0], corners[1], corners[2], corners[3])
    }

    @Test
    fun recoversA4UnderPerspective() {
        val a4 = 210.0 / 297.0
        for ((tx, ty) in listOf(0.5 to 0.0, 0.0 to 0.5, 0.4 to 0.3, -0.6 to 0.2, 0.3 to -0.45)) {
            val q = photo(210.0, 297.0, tx, ty, roll = 0.1)
            val ratio = PageSize.aspectRatio(q, w, h)!!
            assertEquals(a4, ratio, 0.01, "tilt $tx/$ty")
        }
    }

    @Test
    fun landscapePageGetsLandscapeSize() {
        val q = photo(297.0, 210.0, 0.4, 0.2)
        val (pw, ph) = PageSize.of(q, w, h)
        assertEquals(297.0 / 210.0, pw.toDouble() / ph, 0.02)
        val longest = q.edgeLengths.max()
        assertEquals(Math.round(longest).toInt() + 1, pw)
    }

    @Test
    fun straightOnFallsBackToEdgeLengths() {
        val q = Quad(Point(100.0, 100.0), Point(1100.0, 100.0), Point(1100.0, 1514.0), Point(100.0, 1514.0))
        assertNull(PageSize.aspectRatio(q, w, h))
        assertEquals(1001 to 1415, PageSize.of(q, w, h))
    }
}
