// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.ocr

import kotlin.math.abs
import kotlin.math.max

object Lines {
    private const val TOLERANCE = 0.6
    private const val TALL_FACTOR = 1.5

    fun group(boxes: List<Box>): List<List<Int>> {
        val n = boxes.size
        if (n == 0) return emptyList()
        val heights = boxes.map { max(1.0, it.height) }
        val median = heights.sorted()[n / 2]
        val tall = TALL_FACTOR * median
        val anchors = DoubleArray(n) { if (heights[it] > tall) boxes[it].top + 0.5 * median else boxes[it].centerY }
        val lineHeights = DoubleArray(n) { if (heights[it] > tall) median else heights[it] }
        val order = (0 until n).sortedWith(compareBy<Int>({ anchors[it] }, { boxes[it].left }, { it }))
        val lines = mutableListOf<MutableList<Int>>()
        var refAnchor = 0.0
        var refHeight = 0.0
        for (i in order) {
            if (lines.isNotEmpty() && abs(anchors[i] - refAnchor) <= TOLERANCE * max(refHeight, lineHeights[i])) {
                lines.last() += i
                refHeight = max(refHeight, lineHeights[i])
            } else {
                lines += mutableListOf(i)
                refAnchor = anchors[i]
                refHeight = lineHeights[i]
            }
        }
        return lines.map { line -> line.sortedWith(compareBy<Int>({ boxes[it].left }, { it })) }
    }
}

object Bidi {
    private const val L = 1
    private const val R = 2
    private const val N = 0

    private fun dirClass(cp: Int): Int = when (Character.getDirectionality(cp)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> R
        Character.DIRECTIONALITY_LEFT_TO_RIGHT, Character.DIRECTIONALITY_EUROPEAN_NUMBER, Character.DIRECTIONALITY_ARABIC_NUMBER -> L
        else -> N
    }

    fun isRtl(text: String): Boolean {
        var rtl = 0
        var ltr = 0
        text.codePoints().forEach { cp ->
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING, Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
                -> rtl++
                Character.DIRECTIONALITY_LEFT_TO_RIGHT, Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE,
                -> ltr++
            }
        }
        return rtl > ltr
    }

    fun containsRtl(text: String): Boolean = text.codePoints().anyMatch { dirClass(it) == R }

    fun visualToLogical(visual: String, rtlBase: Boolean): String {
        if (visual.length < 2) return visual
        val cps = visual.codePoints().toArray()
        if (rtlBase) cps.reverseRange(0, cps.size - 1)
        val embedded = if (rtlBase) L else R
        var i = 0
        while (i < cps.size) {
            if (dirClass(cps[i]) != embedded) {
                i++
                continue
            }
            var end = i
            for (j in i + 1 until cps.size) {
                val c = dirClass(cps[j])
                if (c == embedded) end = j else if (c != N) break
            }
            cps.reverseRange(i, end)
            i = end + 1
        }
        return String(cps, 0, cps.size)
    }

    private fun IntArray.reverseRange(from: Int, to: Int) {
        var a = from
        var b = to
        while (a < b) {
            val t = this[a]
            this[a++] = this[b]
            this[b--] = t
        }
    }
}
