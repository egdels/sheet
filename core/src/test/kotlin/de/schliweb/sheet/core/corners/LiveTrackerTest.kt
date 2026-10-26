// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.corners

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTrackerTest {
    private val w = 720
    private val h = 960
    private val page = Quad(Point(100.0, 120.0), Point(600.0, 110.0), Point(620.0, 820.0), Point(90.0, 840.0))

    private fun det(q: Quad) = Detection(q, q, false, 0.9)
    private fun shifted(dx: Double) = page.map { Point(it.x + dx, it.y) }

    @Test
    fun showsAfterTwoFramesAndHidesAfterThreeMisses() {
        val t = LiveTracker()
        assertNull(t.update(det(page), w, h, 0))
        assertEquals(page, t.update(det(page), w, h, 200))
        assertNotNull(t.update(null, w, h, 400))
        assertNotNull(t.update(null, w, h, 600))
        assertNull(t.update(null, w, h, 800))
        assertNull(t.update(det(page), w, h, 1000))
    }

    @Test
    fun implausibleCountsAsMiss() {
        val crossed = Quad(page.topLeft, page.bottomRight, page.topRight, page.bottomLeft)
        val t = LiveTracker()
        t.update(det(page), w, h, 0)
        t.update(det(page), w, h, 200)
        repeat(3) { t.update(det(crossed), w, h, 400L + 200 * it) }
        assertNull(t.update(det(crossed), w, h, 1000))
    }

    @Test
    fun unconfidentCountsAsMiss() {
        val noise = Detection(page, page, false, 0.0)
        val t = LiveTracker()
        repeat(5) { assertNull(t.update(noise, w, h, 200L * it)) }
        t.update(det(page), w, h, 1000)
        assertNotNull(t.update(det(page), w, h, 1200))
        repeat(2) { assertNotNull(t.update(noise, w, h, 1400L + 200 * it)) }
        assertNull(t.update(noise, w, h, 1800))
    }

    @Test
    fun jitterIsDamped() {
        val t = LiveTracker()
        t.update(det(page), w, h, 0)
        t.update(det(page), w, h, 200)
        val shown = t.update(det(shifted(6.0)), w, h, 400)!!
        assertTrue(shown.topLeft.x - page.topLeft.x in 0.5..2.5, "moved ${shown.topLeft.x - page.topLeft.x}")
    }

    @Test
    fun steadyMoveIsFollowed() {
        val t = LiveTracker()
        var shown: Quad? = null
        for (i in 0..20) shown = t.update(det(shifted(i * 5.0)), w, h, i * 200L)
        assertTrue(100.0 - (shown!!.topLeft.x - page.topLeft.x) < 10.0, "lag ${100.0 - (shown.topLeft.x - page.topLeft.x)}")
    }

    @Test
    fun bigJumpRestarts() {
        val t = LiveTracker()
        t.update(det(page), w, h, 0)
        t.update(det(page), w, h, 200)
        val other = Quad(Point(10.0, 10.0), Point(300.0, 12.0), Point(305.0, 400.0), Point(12.0, 398.0))
        assertEquals(other, t.update(det(other), w, h, 400))
    }
}
