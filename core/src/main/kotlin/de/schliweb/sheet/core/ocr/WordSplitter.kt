// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.ocr

import de.schliweb.sheet.core.Quad

import kotlin.math.max
import kotlin.math.roundToInt

object WordSplitter {
    private const val GAP_INK_RATIO = 0.04
    private const val WORD_GAP_MEDIAN_FACTOR = 1.8
    private const val MIN_WORD_GAP_PX = 3
    private const val MIN_WORD_GAP_INK_RUN_FACTOR = 0.6
    private const val MIN_WORD_GAP_HEIGHT_RATIO = 0.18
    private const val MIN_SEG_RATIO = 0.25

    class Geometry(val frameCount: Int, val scaledWidth: Int, val paddedWidth: Int, val charFrames: IntArray)

    fun split(quad: Quad, pixels: IntArray, width: Int, height: Int, text: String, confidence: Float, geometry: Geometry?): List<OcrWord>? {
        val trimmed = text.trim()
        if (trimmed.length < 2 || ' ' in trimmed || width < 8 || height < 4) return null
        val segments = segments(inkProfile(pixels, width, height), height) ?: return null
        val ranges = charPositions(text, trimmed, geometry, width)?.let { assignByPosition(it, segments) }
            ?: distribute(segments, trimmed.length)
        val words = segments.indices.mapNotNull { i ->
            val wt = trimmed.substring(ranges[i].first, ranges[i].last + 1).takeIf { ranges[i].last >= ranges[i].first } ?: return@mapNotNull null
            val (s0, s1) = segments[i]
            val uEnd = max((s1 + 1).toDouble() / width, s0.toDouble() / width + 1e-3)
            OcrWord(wt, quad.strip(s0.toDouble() / width, uEnd), confidence)
        }
        return words.takeIf { it.size >= 2 }
    }

    internal fun inkProfile(pixels: IntArray, width: Int, height: Int): IntArray = IntArray(width) { x ->
        var sum = 0
        for (y in 0 until height) {
            val p = pixels[y * width + x]
            sum += 255 - (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        sum
    }

    internal fun segments(profile: IntArray, cropHeight: Int): List<Pair<Int, Int>>? {
        val gapThresh = (GAP_INK_RATIO * cropHeight * 255.0).roundToInt()
        val minSeg = max(2, (MIN_SEG_RATIO * cropHeight).roundToInt())
        val runs = mutableListOf<IntArray>()
        var s = -1
        for (x in profile.indices) {
            val ink = profile[x] > gapThresh
            if (ink && s < 0) {
                s = x
            } else if (!ink && s >= 0) {
                runs += intArrayOf(s, x - 1)
                s = -1
            }
        }
        if (s >= 0) runs += intArrayOf(s, profile.size - 1)
        if (runs.size < 2) return null
        val gaps = (1 until runs.size).map { runs[it][0] - runs[it - 1][1] - 1 }.sorted()
        val median = gaps[gaps.size / 2]
        val minGap = gaps.first()
        val maxGap = gaps.last()
        var wordGap = if (maxGap >= median * WORD_GAP_MEDIAN_FACTOR && maxGap > minGap + 1) (median + maxGap) / 2 else MIN_WORD_GAP_PX
        if (runs.size >= 4) {
            val runWidths = runs.map { it[1] - it[0] + 1 }.sorted()
            wordGap = max(wordGap, (MIN_WORD_GAP_INK_RUN_FACTOR * runWidths[runWidths.size / 2]).roundToInt())
        }
        wordGap = max(wordGap, max(MIN_WORD_GAP_PX, (MIN_WORD_GAP_HEIGHT_RATIO * cropHeight).roundToInt()))
        val merged = mutableListOf(runs[0].clone())
        for (r in runs.drop(1)) {
            if (r[0] - merged.last()[1] - 1 < wordGap) merged.last()[1] = r[1] else merged += r.clone()
        }
        val kept = mutableListOf<IntArray>()
        for (r in merged) {
            if (r[1] - r[0] + 1 >= minSeg || kept.isEmpty()) kept += r else kept.last()[1] = r[1]
        }
        return kept.takeIf { it.size >= 2 }?.map { it[0] to it[1] }
    }

    private fun charPositions(text: String, trimmed: String, g: Geometry?, cropWidth: Int): DoubleArray? {
        if (g == null || g.charFrames.size != text.length || g.frameCount <= 0 || g.scaledWidth <= 0 || g.paddedWidth <= 0) return null
        val offset = text.indexOf(trimmed)
        if (offset < 0) return null
        val frameW = g.paddedWidth.toDouble() / g.frameCount
        return DoubleArray(trimmed.length) { (g.charFrames[offset + it] + 0.5) * frameW / g.scaledWidth * cropWidth }
    }

    internal fun assignByPosition(charX: DoubleArray, segments: List<Pair<Int, Int>>): List<IntRange> {
        val segOf = IntArray(charX.size)
        var prev = 0
        for (i in charX.indices) {
            val x = charX[i]
            var best = segments.indices.minBy { s ->
                val (a, b) = segments[s]
                if (x < a) a - x else if (x > b) x - b else 0.0
            }
            if (best < prev) best = prev
            segOf[i] = best
            prev = best
        }
        var cursor = 0
        return segments.indices.map { s ->
            val start = cursor
            while (cursor < charX.size && segOf[cursor] == s) cursor++
            start until cursor
        }
    }

    internal fun distribute(segments: List<Pair<Int, Int>>, n: Int): List<IntRange> {
        val total = segments.sumOf { it.second - it.first + 1 }
        var cursor = 0
        var seen = 0
        return segments.mapIndexed { i, (a, b) ->
            seen += b - a + 1
            var end = if (i == segments.size - 1) n else (seen.toDouble() * n / total).roundToInt()
            if (end <= cursor) end = minOf(n, cursor + 1)
            end = minOf(end, n)
            val r = cursor until end
            cursor = end
            r
        }
    }
}
