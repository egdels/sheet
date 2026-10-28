// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.corners

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StillnessTest {
    private val ms = 1_000_000L

    @Test
    fun stillAfterAQuietWindow() {
        val s = Stillness(maxRadPerSecond = 0.05, windowNanos = 200 * ms)
        assertFalse(s.isStill(0), "no readings yet")
        for (t in 0..300 step 20) s.update(t * ms, 0.01f, -0.02f, 0.01f)
        assertFalse(s.isStill(150 * ms))
        assertTrue(s.isStill(300 * ms))
    }

    @Test
    fun aJoltStartsTheWindowAgain() {
        val s = Stillness(maxRadPerSecond = 0.05, windowNanos = 200 * ms)
        for (t in 0..300 step 20) s.update(t * ms, 0f, 0f, 0f)
        s.update(320 * ms, 0.3f, 0f, 0f)
        assertFalse(s.isStill(400 * ms))
        for (t in 340..540 step 20) s.update(t * ms, 0f, 0.01f, 0f)
        assertTrue(s.isStill(520 * ms))
    }

    @Test
    fun resetForgets() {
        val s = Stillness(windowNanos = 200 * ms)
        for (t in 0..300 step 20) s.update(t * ms, 0f, 0f, 0f)
        s.reset()
        assertFalse(s.isStill(300 * ms))
        s.update(320 * ms, 0f, 0f, 0f)
        assertTrue(s.isStill(520 * ms))
    }
}
