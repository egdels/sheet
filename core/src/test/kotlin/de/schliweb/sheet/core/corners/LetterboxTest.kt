// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.corners

import de.schliweb.sheet.core.Point
import kotlin.test.Test
import kotlin.test.assertEquals

class LetterboxTest {
    @Test
    fun landscapeIsPaddedTopAndBottom() {
        val lb = Letterbox.of(1920, 1080)
        assertEquals(256.0 / 1920, lb.scale)
        assertEquals(0.0, lb.left)
        assertEquals((256 - 144.0) / 2, lb.top)
        assertEquals(Point(256.0, 200.0), lb.toModel(Point(1920.0, 1080.0)))
    }

    @Test
    fun roundTrip() {
        val lb = Letterbox.of(3000, 4000)
        val p = Point(1234.5, 3210.25)
        val back = lb.toSource(lb.toModel(p))
        assertEquals(p.x, back.x, 1e-9)
        assertEquals(p.y, back.y, 1e-9)
    }
}
