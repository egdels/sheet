// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.image

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class GradientsTest {
    @Test
    fun sameFloatsAsFilteringTheWholeImage() {
        val w = 37
        val h = 23
        val random = Random(7)
        val argb = IntArray(w * h) { random.nextInt() or (0xFF shl 24) }
        val blurred = GrayImage.fromArgb(w, h, argb).blurred()
        val gx = blurred.sobelX()
        val gy = blurred.sobelY()
        val g = Gradients(w, h, argb)
        for (i in (0 until w * h).shuffled(random)) {
            val x = i % w
            val y = i / w
            assertEquals(gx[x, y].toRawBits(), g.x(x, y).toRawBits(), "x at $x,$y")
            assertEquals(gy[x, y].toRawBits(), g.y(x, y).toRawBits(), "y at $x,$y")
        }
    }
}
