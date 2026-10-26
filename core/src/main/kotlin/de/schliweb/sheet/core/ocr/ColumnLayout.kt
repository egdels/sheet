// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.ocr

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ColumnLayout {
    private const val FULL_WIDTH_FRACTION = 0.6
    private const val MIN_GAP_FACTOR = 0.5
    private const val VALLEY_COVERAGE_FRACTION = 0.15
    private const val VALLEY_COVERAGE_MAX = 2
    private const val MIN_COLUMN_WIDTH_FRACTION = 0.1
    private const val COVERAGE_BINS = 256
    private const val MIN_LINES_PER_COLUMN = 3
    private const val MIN_COLUMNS = 2
    private const val MIN_LINES_PER_BAND_COLUMN = 2
    private const val MAX_BAND_DEPTH = 2
    private const val WORD_MERGE_GAP_FACTOR = 1.0
    private const val CROSSING_MIN_EXTENT_FACTOR = 0.25
    private const val ALIGNED_ROW_FRACTION = 0.6
    private const val ALIGN_TOLERANCE_FACTOR = 0.5

    fun segments(boxes: List<Box>, rtl: Boolean): List<List<Int>> {
        if (boxes.size < MIN_COLUMNS * MIN_LINES_PER_COLUMN) return emptyList()
        val gutters = valleys(boxes.indices.toList(), boxes, boxes.minOf { it.left }, width(boxes), medianHeight(boxes)).map { (it.first + it.second) / 2 }
        val fragments = fragments(boxes, gutters)
        if (fragments.size < MIN_COLUMNS * MIN_LINES_PER_COLUMN) return emptyList()
        val fragmentBoxes = fragments.map { f -> Box(f.minOf { boxes[it].left }, f.minOf { boxes[it].top }, f.maxOf { boxes[it].right }, f.maxOf { boxes[it].bottom }) }
        val segments = segmentFragments(fragmentBoxes, rtl)
        return segments.map { seg -> seg.flatMap { fragments[it] } }
    }

    private fun width(boxes: List<Box>) = max(1.0, boxes.maxOf { it.right } - boxes.minOf { it.left })

    private fun medianHeight(boxes: List<Box>) = boxes.map { max(1.0, it.height) }.sorted()[boxes.size / 2]

    private fun fragments(boxes: List<Box>, gutters: List<Double>): List<List<Int>> {
        val mergeGap = WORD_MERGE_GAP_FACTOR * medianHeight(boxes)
        val out = mutableListOf<List<Int>>()
        for (row in Lines.group(boxes)) {
            var fragment = mutableListOf<Int>()
            var right = 0.0
            for (i in row) {
                val b = boxes[i]
                if (fragment.isEmpty() || (b.left - right <= mergeGap && gutters.none { it in right..b.left })) {
                    fragment += i
                } else {
                    out += fragment
                    fragment = mutableListOf(i)
                }
                right = max(right, b.right)
            }
            if (fragment.isNotEmpty()) out += fragment
        }
        return out
    }

    private fun segmentFragments(boxes: List<Box>, rtl: Boolean): List<List<Int>> {
        val contentWidth = width(boxes)
        val fullWidth = boxes.map { it.width >= FULL_WIDTH_FRACTION * contentWidth }
        if (fullWidth.count { !it } < MIN_COLUMNS * MIN_LINES_PER_COLUMN) return emptyList()
        val segments = mutableListOf<List<Int>>()
        var fullRun = mutableListOf<Int>()
        var band = mutableListOf<Int>()
        var multiColumn = false
        for (row in rowsOf(boxes.indices.toList(), boxes)) {
            if (row.any { fullWidth[it] }) {
                if (band.isNotEmpty()) {
                    multiColumn = emitBand(band, boxes, rtl, MAX_BAND_DEPTH, segments) || multiColumn
                    band = mutableListOf()
                }
                fullRun += row
            } else {
                if (fullRun.isNotEmpty()) {
                    segments += fullRun
                    fullRun = mutableListOf()
                }
                band += row
            }
        }
        if (fullRun.isNotEmpty()) segments += fullRun
        if (band.isNotEmpty()) multiColumn = emitBand(band, boxes, rtl, MAX_BAND_DEPTH, segments) || multiColumn
        return if (multiColumn) segments else emptyList()
    }

    private fun emitBand(band: List<Int>, boxes: List<Box>, rtl: Boolean, depth: Int, segments: MutableList<List<Int>>): Boolean {
        if (band.size == 1) {
            segments += band
            return false
        }
        val bandBoxes = band.map { boxes[it] }
        val bandLeft = bandBoxes.minOf { it.left }
        val bandWidth = width(bandBoxes)
        val median = medianHeight(bandBoxes)
        val valleys = valleys(band, boxes, bandLeft, bandWidth, median)
        if (depth > 0) {
            val margin = CROSSING_MIN_EXTENT_FACTOR * median
            val separators = band.filter { i ->
                val b = boxes[i]
                b.width >= FULL_WIDTH_FRACTION * bandWidth || valleys.any { (v0, v1) -> b.left <= v0 - margin && b.right >= v1 + margin }
            }.toSet()
            if (separators.isNotEmpty() && separators.size < band.size) {
                var any = false
                var fullRun = mutableListOf<Int>()
                var sub = mutableListOf<Int>()
                for (row in rowsOf(band, boxes)) {
                    if (row.any { it in separators }) {
                        if (sub.isNotEmpty()) {
                            any = emitBand(sub, boxes, rtl, depth - 1, segments) || any
                            sub = mutableListOf()
                        }
                        fullRun += row
                    } else {
                        if (fullRun.isNotEmpty()) {
                            segments += fullRun
                            fullRun = mutableListOf()
                        }
                        sub += row
                    }
                }
                if (fullRun.isNotEmpty()) segments += fullRun
                if (sub.isNotEmpty()) any = emitBand(sub, boxes, rtl, depth - 1, segments) || any
                return any
            }
        }
        val splits = valleys.map { (it.first + it.second) / 2 }
        val columns = splits.size + 1
        val columnOf = band.associateWith { i -> splits.count { boxes[i].centerX > it } }
        val sizes = IntArray(columns).also { s -> columnOf.values.forEach { s[it]++ } }
        if (columns < MIN_COLUMNS || sizes.min() < MIN_LINES_PER_BAND_COLUMN) {
            segments += band
            return false
        }
        for (c in 0 until columns) {
            val column = if (rtl) columns - 1 - c else c
            val members = band.filter { columnOf[it] == column }
            if (members.isNotEmpty()) segments += members
        }
        return sizes.min() >= MIN_LINES_PER_COLUMN
    }

    private fun valleys(band: List<Int>, boxes: List<Box>, bandLeft: Double, bandWidth: Double, median: Double): List<Pair<Double, Double>> {
        val bins = COVERAGE_BINS
        val binWidth = bandWidth / bins
        val coverage = IntArray(bins)
        for (i in band) {
            val i0 = ((boxes[i].left - bandLeft) / bandWidth * (bins - 1)).toInt().coerceIn(0, bins - 1)
            val i1 = ((boxes[i].right - bandLeft) / bandWidth * (bins - 1)).toInt().coerceIn(0, bins - 1)
            for (k in i0..i1) coverage[k]++
        }
        val threshold = min(floor(VALLEY_COVERAGE_FRACTION * coverage.max()).toInt(), VALLEY_COVERAGE_MAX)
        val minBins = max(1, (MIN_GAP_FACTOR * median / binWidth).roundToInt())
        val splits = mutableListOf<Double>()
        val extents = mutableListOf<Pair<Double, Double>>()
        val widths = mutableListOf<Int>()
        var start = -1
        for (k in 0..bins) {
            val valley = k < bins && coverage[k] <= threshold
            if (valley) {
                if (start < 0) start = k
            } else if (start >= 0) {
                if (start > 0 && k < bins && k - start >= minBins) {
                    splits += bandLeft + (start + k) * 0.5 * binWidth
                    extents += (bandLeft + start * binWidth) to (bandLeft + k * binWidth)
                    widths += k - start
                }
                start = -1
            }
        }
        val minColumn = MIN_COLUMN_WIDTH_FRACTION * bandWidth
        var changed = true
        while (changed && splits.isNotEmpty()) {
            changed = false
            var prev = bandLeft
            for (k in 0..splits.size) {
                val next = if (k < splits.size) splits[k] else bandLeft + bandWidth
                if (next - prev < minColumn) {
                    val remove = when (k) {
                        0 -> 0
                        splits.size -> k - 1
                        else -> if (widths[k - 1] <= widths[k]) k - 1 else k
                    }
                    splits.removeAt(remove)
                    extents.removeAt(remove)
                    widths.removeAt(remove)
                    changed = true
                    break
                }
                prev = next
            }
        }
        val kept = alignedGutters(band, boxes, bandLeft, bandWidth, median, splits)
        return splits.indices.filter { splits[it] in kept }.map { extents[it] }
    }

    private fun alignedGutters(band: List<Int>, boxes: List<Box>, bandLeft: Double, bandWidth: Double, median: Double, splits: List<Double>): List<Double> {
        if (splits.isEmpty()) return splits
        val rows = rowsOf(band, boxes)
        val tolerance = ALIGN_TOLERANCE_FACTOR * median
        val points = splits.toMutableList()
        var changed = true
        while (changed && points.isNotEmpty()) {
            changed = false
            for (k in points.indices) {
                val gutter = points[k]
                val from = if (k == 0) bandLeft else points[k - 1]
                val to = if (k + 1 < points.size) points[k + 1] else bandLeft + bandWidth
                val leftEdges = mutableListOf<Double>()
                val rightEdges = mutableListOf<Double>()
                for (row in rows) {
                    row.filter { boxes[it].centerX >= from && boxes[it].centerX < gutter }.maxOfOrNull { boxes[it].right }?.let(leftEdges::add)
                    row.filter { boxes[it].centerX >= gutter && boxes[it].centerX < to }.minOfOrNull { boxes[it].left }?.let(rightEdges::add)
                }
                fun straight(edges: List<Double>) = edges.size >= 2 && edges.maxOf { a -> edges.count { abs(it - a) <= tolerance } } >= ALIGNED_ROW_FRACTION * edges.size
                if (!straight(leftEdges) && !straight(rightEdges)) {
                    points.removeAt(k)
                    changed = true
                    break
                }
            }
        }
        return points
    }

    private fun rowsOf(subset: List<Int>, boxes: List<Box>): List<List<Int>> =
        Lines.group(subset.map { boxes[it] }).map { row -> row.map { subset[it] } }
}
