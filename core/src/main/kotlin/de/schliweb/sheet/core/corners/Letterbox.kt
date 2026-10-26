// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.corners

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import kotlin.math.min

class Letterbox private constructor(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val scale: Double,
    val left: Double,
    val top: Double,
) {
    val width: Double get() = sourceWidth * scale
    val height: Double get() = sourceHeight * scale

    fun toModel(p: Point): Point = Point(p.x * scale + left, p.y * scale + top)

    fun toSource(p: Point): Point = Point((p.x - left) / scale, (p.y - top) / scale)

    fun toSource(q: Quad): Quad = q.map(::toSource)

    companion object {
        fun of(sourceWidth: Int, sourceHeight: Int, size: Int = DocQuad.INPUT_SIZE): Letterbox {
            require(sourceWidth > 0 && sourceHeight > 0) { "empty image ${sourceWidth}x$sourceHeight" }
            val s = min(size.toDouble() / sourceWidth, size.toDouble() / sourceHeight)
            return Letterbox(
                sourceWidth,
                sourceHeight,
                s,
                (size - sourceWidth * s) / 2.0,
                (size - sourceHeight * s) / 2.0,
            )
        }
    }
}
