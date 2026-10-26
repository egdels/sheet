// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.ocr

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import kotlinx.serialization.Serializable

@Serializable
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2
}

fun Quad.bounds(): Box {
    val c = corners
    return Box(c.minOf { it.x }, c.minOf { it.y }, c.maxOf { it.x }, c.maxOf { it.y })
}

fun Quad.strip(uStart: Double, uEnd: Double): Box {
    val a = uStart.coerceIn(0.0, 1.0)
    val b = uEnd.coerceIn(0.0, 1.0)
    fun along(p: Point, q: Point, u: Double) = Point(p.x + u * (q.x - p.x), p.y + u * (q.y - p.y))
    val pts = listOf(along(topLeft, topRight, a), along(topLeft, topRight, b), along(bottomLeft, bottomRight, a), along(bottomLeft, bottomRight, b))
    return Box(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
}

data class TextBox(val quad: Quad, val score: Double) {
    val bounds: Box get() = quad.bounds()
}

@Serializable
data class OcrWord(val text: String, val box: Box, val confidence: Float)

@Serializable
data class OcrLine(val words: List<OcrWord>, val rtl: Boolean) {
    val text: String get() = (if (rtl) words.asReversed() else words).joinToString(" ") { it.text }
}

@Serializable
data class OcrPage(val width: Int, val height: Int, val lines: List<OcrLine>) {
    val text: String get() = lines.joinToString("\n") { it.text }
    val words: List<OcrWord> get() = lines.flatMap { it.words }
}
