// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.image

import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Enhance {
    private const val BLOCKS = 40
    private const val MIN_BLOCK = 16

    private const val PAPER_SHARE = 0.25

    private const val PAPER_MIN = 0.7
    private const val SHADOW_MIN = 0.3
    private const val FLAT = 6f

    private const val PAPER_LIFT = 1.05f

    private const val MAX_GAIN = 3.0
    private const val BLACK_SHARE = 0.005
    private const val MAX_BLACK = 64

    private const val INK = 0.6

    private val KERNEL = intArrayOf(1, 6, 15, 20, 15, 6, 1)
    private const val KERNEL_SUM = 64
    private const val AMOUNT = 0.8
    private const val THRESHOLD = 3

    private const val BAND = 64

    fun apply(argb: IntArray, width: Int, height: Int) {
        val n = width * height
        require(argb.size >= n) { "${argb.size} pixels for $width x $height" }
        if (n == 0) return
        whitenPaper(argb, width, height)
        blackenInk(argb, width, height)
        sharpen(argb, width, height)
    }

    private fun inBands(count: Int, rows: (Int, Int) -> Unit) {
        val bands = (count + BAND - 1) / BAND
        IntStream.range(0, bands).parallel().forEach { b -> rows(b * BAND, min(count, (b + 1) * BAND)) }
    }

    private fun luma(r: Int, g: Int, b: Int): Int = (77 * r + 150 * g + 29 * b) shr 8

    private fun luma(p: Int): Int = luma(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)

    private fun whitenPaper(argb: IntArray, width: Int, height: Int) {
        val size = max(MIN_BLOCK, (max(width, height) + BLOCKS - 1) / BLOCKS)
        val bw = (width + size - 1) / size
        val bh = (height + size - 1) / size
        val paper = Array(3) { FloatArray(bw * bh) }
        val lumas = FloatArray(bw * bh)
        val flat = BooleanArray(bw * bh)
        IntStream.range(0, bh).parallel().forEach { by ->
            val histogram = IntArray(256)
            for (bx in 0 until bw) paperOfBlock(argb, width, height, size, bx, by, bw, histogram, paper, lumas, flat)
        }
        val typical = lumas.sorted()[lumas.size / 2]
        val valid = BooleanArray(bw * bh) { lumas[it] >= typical * PAPER_MIN || (flat[it] && lumas[it] >= typical * SHADOW_MIN) }
        fillFromNeighbours(paper, valid, bw, bh, typical)
        repeat(2) { for (c in paper) boxBlur(c, bw, bh) }

        val gains = Array(3) { c -> FloatArray(bw * bh) { (PAPER_LIFT * 255f / max(1f, paper[c][it])).coerceIn(1f, MAX_GAIN.toFloat()) } }
        val left = IntArray(width)
        val right = IntArray(width)
        val towards = FloatArray(width)
        for (x in 0 until width) {
            val fx = ((x + 0.5f) / size - 0.5f).coerceIn(0f, (bw - 1).toFloat())
            left[x] = fx.toInt()
            right[x] = min(bw - 1, left[x] + 1)
            towards[x] = fx - left[x]
        }
        inBands(height) { from, until ->
            val rowR = FloatArray(bw)
            val rowG = FloatArray(bw)
            val rowB = FloatArray(bw)
            for (y in from until until) {
                val fy = ((y + 0.5f) / size - 0.5f).coerceIn(0f, (bh - 1).toFloat())
                val top = fy.toInt() * bw
                val bottom = min(bh - 1, fy.toInt() + 1) * bw
                val ty = fy - fy.toInt()
                gainRow(gains[0], top, bottom, ty, rowR)
                gainRow(gains[1], top, bottom, ty, rowG)
                gainRow(gains[2], top, bottom, ty, rowB)
                whitenRow(argb, y * width, width, left, right, towards, rowR, rowG, rowB)
            }
        }
    }

    private fun paperOfBlock(argb: IntArray, width: Int, height: Int, size: Int, bx: Int, by: Int, bw: Int, histogram: IntArray, paper: Array<FloatArray>, lumas: FloatArray, flat: BooleanArray) {
        val x0 = bx * size
        val y0 = by * size
        val x1 = min(width, x0 + size)
        val y1 = min(height, y0 + size)
        histogram.fill(0)
        for (y in y0 until y1) {
            var i = y * width + x0
            for (x in x0 until x1) histogram[luma(argb[i++])]++
        }
        val count = (x1 - x0) * (y1 - y0)
        var median = 0
        var below = histogram[0]
        while (median < 255 && below < (count + 1) / 2) below += histogram[++median]
        var cut = 255
        var above = histogram[255]
        while (cut > 0 && above < count * PAPER_SHARE) above += histogram[--cut]
        var r = 0L
        var g = 0L
        var b = 0L
        var k = 0
        for (y in y0 until y1) {
            var i = y * width + x0
            for (x in x0 until x1) {
                val p = argb[i++]
                if (luma(p) < cut) continue
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
                k++
            }
        }
        val i = by * bw + bx
        paper[0][i] = r.toFloat() / k
        paper[1][i] = g.toFloat() / k
        paper[2][i] = b.toFloat() / k
        lumas[i] = luma(paper[0][i].roundToInt(), paper[1][i].roundToInt(), paper[2][i].roundToInt()).toFloat()
        flat[i] = lumas[i] - median <= FLAT
    }

    private fun gainRow(gains: FloatArray, top: Int, bottom: Int, ty: Float, out: FloatArray) {
        for (bx in out.indices) out[bx] = gains[top + bx] + (gains[bottom + bx] - gains[top + bx]) * ty
    }

    private fun whitenRow(argb: IntArray, start: Int, width: Int, left: IntArray, right: IntArray, towards: FloatArray, rowR: FloatArray, rowG: FloatArray, rowB: FloatArray) {
        var i = start
        for (x in 0 until width) {
            val a = left[x]
            val b = right[x]
            val t = towards[x]
            val p = argb[i]
            val r = min(255, ((p shr 16 and 0xFF) * (rowR[a] + (rowR[b] - rowR[a]) * t) + 0.5f).toInt())
            val g = min(255, ((p shr 8 and 0xFF) * (rowG[a] + (rowG[b] - rowG[a]) * t) + 0.5f).toInt())
            val bl = min(255, ((p and 0xFF) * (rowB[a] + (rowB[b] - rowB[a]) * t) + 0.5f).toInt())
            argb[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or bl
            i++
        }
    }

    private fun fillFromNeighbours(paper: Array<FloatArray>, valid: BooleanArray, bw: Int, bh: Int, typical: Float) {
        if (valid.none { it }) {
            for (c in paper) c.fill(typical)
            return
        }
        while (!valid.all { it }) {
            val next = valid.copyOf()
            for (i in valid.indices) {
                if (valid[i]) continue
                val x = i % bw
                val y = i / bw
                val sum = FloatArray(3)
                var k = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until bw || ny !in 0 until bh || !valid[ny * bw + nx]) continue
                    for (c in 0..2) sum[c] += paper[c][ny * bw + nx]
                    k++
                }
                if (k == 0) continue
                for (c in 0..2) paper[c][i] = sum[c] / k
                next[i] = true
            }
            next.copyInto(valid)
        }
    }

    private fun boxBlur(m: FloatArray, w: Int, h: Int) {
        val src = m.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var k = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    s += src[ny * w + nx]
                    k++
                }
                m[y * w + x] = s / k
            }
        }
    }

    private fun blackenInk(argb: IntArray, width: Int, height: Int) {
        val n = width * height
        val histogram = IntArray(256)
        inBands(height) { from, until ->
            val local = IntArray(256)
            for (y in from until until) lumaHistogram(argb, y * width, width, local)
            synchronized(histogram) { for (v in 0..255) histogram[v] += local[v] }
        }
        var black = 0
        var count = 0
        while (black < 255 && count + histogram[black] <= n * BLACK_SHARE) count += histogram[black++]
        black = black.coerceAtMost(MAX_BLACK)
        val map = IntArray(256) {
            val t = (it - black).coerceAtLeast(0) / (255.0 - black)
            (255.0 * (t - INK * t * (1 - t) * (1 - t))).roundToInt().coerceIn(0, 255)
        }
        inBands(height) { from, until -> for (y in from until until) mapRow(argb, y * width, width, map) }
    }

    private fun lumaHistogram(argb: IntArray, start: Int, width: Int, histogram: IntArray) {
        for (i in start until start + width) histogram[luma(argb[i])]++
    }

    private fun mapRow(argb: IntArray, start: Int, width: Int, map: IntArray) {
        for (i in start until start + width) {
            val p = argb[i]
            argb[i] = (p and 0xFF000000.toInt()) or (map[p shr 16 and 0xFF] shl 16) or (map[p shr 8 and 0xFF] shl 8) or map[p and 0xFF]
        }
    }

    private fun sharpen(argb: IntArray, width: Int, height: Int) {
        val n = width * height
        val lum = ByteArray(n)
        val tmp = ByteArray(n)
        inBands(height) { from, until ->
            for (y in from until until) {
                lumaRow(argb, lum, y * width, width)
                blurRow(lum, tmp, y * width, width)
            }
        }
        inBands(height) { from, until -> for (y in from until until) sharpenRow(argb, lum, tmp, y, width, height) }
    }

    private fun lumaRow(argb: IntArray, lum: ByteArray, start: Int, width: Int) {
        for (i in start until start + width) lum[i] = luma(argb[i]).toByte()
    }

    private fun blurRow(lum: ByteArray, tmp: ByteArray, row: Int, width: Int) {
        for (x in 0 until width) tmp[row + x] = blur(lum, row, x, 1, width).toByte()
    }

    private fun sharpenRow(argb: IntArray, lum: ByteArray, tmp: ByteArray, y: Int, width: Int, height: Int) {
        val row = y * width
        for (x in 0 until width) {
            val i = row + x
            val diff = (lum[i].toInt() and 0xFF) - blur(tmp, x, y, width, height)
            if (diff < THRESHOLD && diff > -THRESHOLD) continue
            val d = (AMOUNT * diff).roundToInt()
            val p = argb[i]
            val red = ((p shr 16 and 0xFF) + d).coerceIn(0, 255)
            val green = ((p shr 8 and 0xFF) + d).coerceIn(0, 255)
            val blue = ((p and 0xFF) + d).coerceIn(0, 255)
            argb[i] = (p and 0xFF000000.toInt()) or (red shl 16) or (green shl 8) or blue
        }
    }

    private fun blur(v: ByteArray, start: Int, at: Int, stride: Int, count: Int): Int {
        val c = start + at * stride
        val s = if (at >= 3 && at < count - 3) {
            (v[c - 3 * stride].toInt() and 0xFF) + (v[c + 3 * stride].toInt() and 0xFF) +
                6 * ((v[c - 2 * stride].toInt() and 0xFF) + (v[c + 2 * stride].toInt() and 0xFF)) +
                15 * ((v[c - stride].toInt() and 0xFF) + (v[c + stride].toInt() and 0xFF)) +
                20 * (v[c].toInt() and 0xFF)
        } else {
            var sum = 0
            for (k in -3..3) sum += KERNEL[k + 3] * (v[start + (at + k).coerceIn(0, count - 1) * stride].toInt() and 0xFF)
            sum
        }
        return (s + KERNEL_SUM / 2) / KERNEL_SUM
    }
}
