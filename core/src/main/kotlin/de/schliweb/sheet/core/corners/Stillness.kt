// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.corners

import kotlin.math.sqrt

class Stillness(
    private val maxRadPerSecond: Double = 0.05,
    private val windowNanos: Long = 200_000_000L,
) {
    private var since = Long.MIN_VALUE

    fun update(timeNanos: Long, x: Float, y: Float, z: Float) {
        val speed = sqrt((x * x + y * y + z * z).toDouble())
        if (since == Long.MIN_VALUE || speed > maxRadPerSecond) since = timeNanos
    }

    fun isStill(timeNanos: Long): Boolean = since != Long.MIN_VALUE && timeNanos - since >= windowNanos

    fun reset() {
        since = Long.MIN_VALUE
    }
}
