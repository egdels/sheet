// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.corners

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RotationTtaTest {
    private val w = 1600
    private val h = 1200
    private val page = Quad(Point(200.0, 150.0), Point(1400.0, 180.0), Point(1380.0, 1050.0), Point(220.0, 1020.0))

    private fun detection(q: Quad, confidence: Double) = Detection(q, q, false, confidence)

    @Test
    fun frameIsScaledAndGrown() {
        val f = RotationTta.frame(w, h, 90)
        assertEquals(720, f.scaledWidth)
        assertEquals(540, f.scaledHeight)
        assertEquals(540, f.width)
        assertEquals(720, f.height)
        val f30 = RotationTta.frame(w, h, 30)
        assertTrue(f30.width > 720 && f30.height > 540)
    }

    @Test
    fun backUndoesForward() {
        for (angle in RotationTta.ANGLES) {
            val f = RotationTta.frame(w, h, angle)
            val p = Point(123.0, 456.0)
            val back = f.back(f.forward(p))
            assertEquals(p.x, back.x, 1e-9)
            assertEquals(p.y, back.y, 1e-9)
        }
    }

    @Test
    fun affineIsForward() {
        for (angle in RotationTta.ANGLES) {
            val f = RotationTta.frame(w, h, angle)
            val a = f.affine
            val p = Point(321.0, 87.0)
            val q = f.forward(p)
            assertEquals(q.x, a[0] * p.x + a[1] * p.y + a[2], 1e-9)
            assertEquals(q.y, a[3] * p.x + a[4] * p.y + a[5], 1e-9)
        }
    }

    @Test
    fun confidentPlainPassSkipsRotation() {
        var rotatedCalls = 0
        val r = RotationTta.detect(w, h, { detection(page, 0.8) }, { rotatedCalls++; null })
        assertEquals(0, rotatedCalls)
        assertEquals(PageOutline(page, 0.8, 0), r)
    }

    private fun rotatedDetection(f: RotatedFrame, confidence: Double): Detection {
        val s = f.scaledWidth.toDouble() / w
        val onCanvas = page.corners.map { f.forward(Point(it.x * s, it.y * s)) }
        return detection(Quad.of(onCanvas), confidence)
    }

    @Test
    fun clearlyBetterRotationWinsAndMapsBack() {
        val seen = mutableListOf<Int>()
        val r = RotationTta.detect(w, h, { detection(page, 0.3) }) { f ->
            seen += f.angle
            rotatedDetection(f, if (f.angle == 30) 0.75 else 0.2)
        }
        assertEquals(listOf(90, -30, 30), seen)
        assertEquals(30, r!!.angle)
        assertEquals(0.75, r.confidence)
        for ((e, a) in page.corners.zip(r.quad.corners)) {
            assertEquals(e.x, a.x, 1e-6)
            assertEquals(e.y, a.y, 1e-6)
        }
    }

    @Test
    fun slightlyBetterRotationKeepsThePlainPass() {
        val r = RotationTta.detect(w, h, { detection(page, 0.3) }) { f -> rotatedDetection(f, 0.4) }
        assertEquals(0, r!!.angle)
    }

    @Test
    fun nothingPlausibleIsNull() {
        val crossed = Quad(page.topLeft, page.bottomRight, page.topRight, page.bottomLeft)
        assertNull(RotationTta.detect(w, h, { detection(crossed, 0.9) }, { null }))
    }

    @Test
    fun acceptanceRules() {
        assertTrue(RotationTta.acceptsRotated(-1.0, 0.1))
        assertFalse(RotationTta.acceptsRotated(-1.0, 0.09))
        assertTrue(RotationTta.acceptsRotated(0.3, 0.45))
        assertFalse(RotationTta.acceptsRotated(0.3, 0.44))
    }
}
