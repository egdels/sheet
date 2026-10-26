// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.corners

import de.schliweb.sheet.core.Point
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

fun interface DocQuadModel {
    fun run(input: FloatArray): DocQuadOutput
}

interface PageImage {
    val width: Int
    val height: Int

    fun letterboxed(letterbox: Letterbox): IntArray

    fun scaled(width: Int, height: Int): PageImage

    fun argb(): IntArray

    fun render(width: Int, height: Int, affine: DoubleArray, background: Int): PageImage

    fun rotated(frame: RotatedFrame): PageImage
}

class CornerDetector(private val model: DocQuadModel) {

    fun detectFrame(image: PageImage): Detection {
        val letterbox = Letterbox.of(image.width, image.height)
        return DocQuad.postprocess(model.run(toInput(image.letterboxed(letterbox))), letterbox)
    }

    fun detectStill(image: PageImage): PageOutline? {
        val s = min(1.0, DETECTION_MAX_EDGE / max(image.width, image.height).toDouble())
        val work = if (s < 1.0) image.scaled((image.width * s).roundToInt(), (image.height * s).roundToInt()) else image
        val outline = RotationTta.detect(
            work.width,
            work.height,
            plain = { detectFrame(work) },
            rotated = { frame -> detectFrame(work.rotated(frame)) },
        ) ?: return null
        if (s >= 1.0) return outline
        return outline.copy(quad = outline.quad.map { Point(it.x / s, it.y / s) })
    }

    companion object {
        const val DETECTION_MAX_EDGE = 720

        fun toInput(argb: IntArray): FloatArray {
            val n = DocQuad.INPUT_SIZE * DocQuad.INPUT_SIZE
            require(argb.size == n) { "expected ${DocQuad.INPUT_SIZE}² pixels, got ${argb.size}" }
            val out = FloatArray(3 * n)
            for (i in 0 until n) {
                val c = argb[i]
                out[i] = ((c shr 16) and 0xFF) / 255.0f
                out[n + i] = ((c shr 8) and 0xFF) / 255.0f
                out[2 * n + i] = (c and 0xFF) / 255.0f
            }
            return out
        }
    }
}
