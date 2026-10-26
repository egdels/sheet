// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.image

class GrayImage(val width: Int, val height: Int, val pixels: FloatArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "bad size ${width}x$height for ${pixels.size}" }
    }

    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]

    fun blurred(): GrayImage = convolve(SMOOTH, SMOOTH, 1f / 16f)

    fun sobelX(): GrayImage = convolve(DERIVE, SMOOTH, 1f)

    fun sobelY(): GrayImage = convolve(SMOOTH, DERIVE, 1f)

    private fun convolve(kx: FloatArray, ky: FloatArray, scale: Float): GrayImage {
        val tmp = FloatArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                tmp[row + x] = kx[0] * pixels[row + reflect(x - 1, width)] + kx[1] * pixels[row + x] + kx[2] * pixels[row + reflect(x + 1, width)]
            }
        }
        val out = FloatArray(pixels.size)
        for (y in 0 until height) {
            val up = reflect(y - 1, height) * width
            val row = y * width
            val down = reflect(y + 1, height) * width
            for (x in 0 until width) {
                out[row + x] = scale * (ky[0] * tmp[up + x] + ky[1] * tmp[row + x] + ky[2] * tmp[down + x])
            }
        }
        return GrayImage(width, height, out)
    }

    companion object {
        internal val SMOOTH = floatArrayOf(1f, 2f, 1f)
        internal val DERIVE = floatArrayOf(-1f, 0f, 1f)

        internal inline fun convolveAt(kx: FloatArray, ky: FloatArray, scale: Float, x: Int, y: Int, width: Int, height: Int, src: (Int, Int) -> Float): Float {
            val left = reflect(x - 1, width)
            val right = reflect(x + 1, width)
            val up = reflect(y - 1, height)
            val down = reflect(y + 1, height)
            val above = kx[0] * src(left, up) + kx[1] * src(x, up) + kx[2] * src(right, up)
            val at = kx[0] * src(left, y) + kx[1] * src(x, y) + kx[2] * src(right, y)
            val below = kx[0] * src(left, down) + kx[1] * src(x, down) + kx[2] * src(right, down)
            return scale * (ky[0] * above + ky[1] * at + ky[2] * below)
        }

        internal fun luma(c: Int): Float = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)

        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage {
            val out = FloatArray(width * height)
            for (i in out.indices) out[i] = luma(argb[i])
            return GrayImage(width, height, out)
        }

        @PublishedApi internal fun reflect(i: Int, n: Int): Int = when {
            n == 1 -> 0
            i < 0 -> -i
            i >= n -> 2 * n - 2 - i
            else -> i
        }
    }
}
