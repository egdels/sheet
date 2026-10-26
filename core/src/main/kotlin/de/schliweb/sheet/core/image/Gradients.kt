// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.image

class Gradients(val width: Int, val height: Int, private val argb: IntArray) {
    private val blurCache = FloatArray(width * height).apply { fill(Float.NaN) }

    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "bad size ${width}x$height for ${argb.size}" }
    }

    fun x(x: Int, y: Int): Float = GrayImage.convolveAt(GrayImage.DERIVE, GrayImage.SMOOTH, 1f, x, y, width, height) { px, py -> blurred(px, py) }

    fun y(x: Int, y: Int): Float = GrayImage.convolveAt(GrayImage.SMOOTH, GrayImage.DERIVE, 1f, x, y, width, height) { px, py -> blurred(px, py) }

    private fun blurred(x: Int, y: Int): Float {
        val i = y * width + x
        val cached = blurCache[i]
        if (!cached.isNaN()) return cached
        return GrayImage.convolveAt(GrayImage.SMOOTH, GrayImage.SMOOTH, 1f / 16f, x, y, width, height) { px, py -> luma(px, py) }.also { blurCache[i] = it }
    }

    private fun luma(x: Int, y: Int): Float = GrayImage.luma(argb[y * width + x])
}
