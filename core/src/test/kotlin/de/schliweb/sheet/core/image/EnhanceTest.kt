// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnhanceTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun gray(v: Int) = rgb(v, v, v)

    private fun level(p: Int) = p and 0xFF

    private fun luma(p: Int) = (77 * (p shr 16 and 0xFF) + 150 * (p shr 8 and 0xFF) + 29 * (p and 0xFF)) shr 8

    private fun page(w: Int, h: Int, paper: (Int) -> Int, ink: Int) = IntArray(w * h) { if (it % w % 16 in 7..8) gray(ink) else gray(paper(it % w)) }

    @Test
    fun paperBecomesWhiteAndInkBlack() {
        val w = 64
        val px = page(w, 64, { 200 }, ink = 60)
        Enhance.apply(px, w, 64)
        assertEquals(255, level(px[30 * w + 2]))
        assertTrue(level(px[30 * w + 23]) < 20, "ink ${level(px[30 * w + 23])}")
    }

    @Test
    fun lightFallingOffIsEvenedOut() {
        val w = 320
        val px = page(w, 160, { x -> 150 + 80 * x / w }, ink = 40)
        Enhance.apply(px, w, 160)
        for (x in listOf(3, 100, 202, 315)) assertTrue(level(px[80 * w + x]) >= 242, "paper at $x: ${level(px[80 * w + x])}")
        assertTrue(level(px[80 * w + 7]) < 40, "ink on the dark side ${level(px[80 * w + 7])}")
    }

    @Test
    fun aColourCastIsTakenOut() {
        val w = 64
        val px = IntArray(w * 64) { if (it % w % 16 in 7..8) rgb(30, 30, 40) else rgb(225, 230, 250) }
        Enhance.apply(px, w, 64)
        val p = px[30 * w + 3]
        assertTrue(listOf(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF).all { it >= 250 }, "paper %06X".format(p and 0xFFFFFF))
    }

    @Test
    fun aPictureOnThePageIsNotWashedOut() {
        val w = 320
        val rnd = java.util.Random(1)
        val px = IntArray(w * w) { val x = it % w; val y = it / w; if (x in 96 until 224 && y in 96 until 224) gray(30 + rnd.nextInt(80)) else gray(215) }
        val before = IntArray(px.size) { luma(px[it]) }
        Enhance.apply(px, w, w)
        val inside = (110 until 210).flatMap { y -> (110 until 210).map { x -> y * w + x } }
        assertTrue(inside.map { luma(px[it]) }.average() < 100, "picture ${inside.map { luma(px[it]) }.average()}")
        assertTrue(inside.map { luma(px[it]) }.toSet().size > 40)
        assertTrue(inside.map { before[it] }.average() < 100)
        assertEquals(255, level(px[20 * w + 20]))
    }

    @Test
    fun aShadowOverTextBecomesWhitePaper() {
        val w = 320
        val px = IntArray(w * w) {
            val x = it % w
            val shade = if (x < 128) 0.5 else 1.0
            gray(((if (x % 16 in 7..8) 40 else 220) * shade).toInt())
        }
        Enhance.apply(px, w, w)
        for (x in listOf(20, 60, 100, 202, 300)) assertTrue(level(px[160 * w + x]) >= 240, "paper at $x: ${level(px[160 * w + x])}")
        assertTrue(level(px[160 * w + 23]) < 40, "ink in the shadow ${level(px[160 * w + 23])}")
    }

    @Test
    fun aBlackBarStaysBlack() {
        val w = 320
        val px = IntArray(w * w) { val y = it / w; if (y in 140 until 180) gray(25) else gray(215) }
        Enhance.apply(px, w, w)
        assertTrue(level(px[160 * w + 160]) < 20, "bar ${level(px[160 * w + 160])}")
        assertEquals(255, level(px[40 * w + 160]))
    }

    @Test
    fun aSoftStrokeGetsCrisp() {
        val w = 64
        val profile = intArrayOf(230, 190, 140, 110, 140, 190, 230)
        val px = IntArray(w * 64) { val x = it % w; gray(if (x in 29..35) profile[x - 29] else 230) }
        Enhance.apply(px, w, 64)
        val row = 30 * w
        assertTrue(level(px[row + 32]) < 50, "core ${level(px[row + 32])}")
        assertTrue(level(px[row + 34]) - level(px[row + 32]) > 3 * (190 - 110) * 255 / 230 / 2, "flank ${level(px[row + 34])}")
        assertEquals(255, level(px[row + 10]))
    }

    @Test
    fun coloursKeepTheirHue() {
        val w = 20
        val red = rgb(180, 40, 40)
        val px = IntArray(w * 20) { if (it < w * 10) red else gray(230) }
        Enhance.apply(px, w, 20)
        val p = px[2 * w + 5]
        val r = p shr 16 and 0xFF
        val g = p shr 8 and 0xFF
        val b = p and 0xFF
        assertEquals(g, b)
        assertTrue(r > g + 100, "red $r $g $b")
        assertEquals(px[2 * w + 5], px[3 * w + 15])
    }

    @Test
    fun emptyAndUniformImagesAreFine() {
        Enhance.apply(IntArray(0), 0, 0)
        val px = IntArray(16) { gray(128) }
        Enhance.apply(px, 4, 4)
        assertEquals(1, px.toSet().size)
    }
}
