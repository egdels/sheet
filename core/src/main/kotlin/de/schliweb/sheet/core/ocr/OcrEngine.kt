// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.ocr

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import de.schliweb.sheet.core.corners.PageImage
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Tensor(val data: FloatArray, val shape: LongArray)

fun interface OcrModel {
    fun run(input: Tensor): Tensor
}

interface OcrModels {
    val detector: OcrModel

    fun recognizer(model: RecModel): OcrModel

    fun vocab(model: RecModel): Vocab
}

class OcrEngine(private val models: OcrModels, private val detectionMaxSide: Int = 1920) {

    fun recognize(page: PageImage, language: RecModel): OcrPage = recognize(page, detect(page), language, emptyMap())

    fun recognizeUpright(page: PageImage, language: RecModel): Upright {
        val o = orient(page, language)
        return Upright(o.degrees, recognize(o.page, o.boxes, language, o.done))
    }

    class Upright(val degrees: Int, val text: OcrPage)

    private class Crop(val image: PageImage, val reading: Reading)

    private fun recognize(page: PageImage, boxes: List<TextBox>, language: RecModel, done: Map<Int, Crop>): OcrPage {
        val read = boxes.mapIndexed { i, box -> recognizeBox(page, box.quad, language, done[i]) }
        val bounds = boxes.map { it.bounds }
        val rtl = Bidi.isRtl(read.flatten().joinToString("") { it.text })
        val segments = ColumnLayout.segments(bounds, rtl).ifEmpty { listOf(bounds.indices.toList()) }
        val lines = segments.flatMap { segment ->
            Lines.group(segment.map { bounds[it] }).map { row ->
                val words = row.flatMap { read[segment[it]] }
                OcrLine(words, Bidi.isRtl(words.joinToString("") { it.text }))
            }
        }
        return OcrPage(page.width, page.height, lines.filter { it.words.isNotEmpty() })
    }

    fun orientation(page: PageImage, language: RecModel): Int = orient(page, language).degrees

    private class Oriented(val degrees: Int, val page: PageImage, val boxes: List<TextBox>, val done: Map<Int, Crop>)

    private fun orient(page: PageImage, language: RecModel): Oriented {
        var boxes = detect(page)
        val elongated = boxes.map { it.bounds }.filter { max(it.width, it.height) >= ELONGATED * min(it.width, it.height) }
        val tall = elongated.count { it.height > it.width }
        val sideways = language != RecModel.MULTI && tall >= MIN_LINES && tall > 2 * (elongated.size - tall)
        var upright = page
        if (sideways) {
            upright = page.turned(90)
            boxes = detect(upright)
        }
        val turn = if (sideways) 90 else 0
        val lines = boxes.indices.filter { i -> boxes[i].quad.bounds().let { it.width >= ELONGATED * it.height } }
            .sortedByDescending { boxes[it].quad.bounds().width }.take(ORIENTATION_LINES)
        if (lines.size < MIN_LINES) return Oriented(turn, upright, boxes, emptyMap())
        val done = HashMap<Int, Crop>()
        var asIs = 0.0
        var flipped = 0.0
        for (i in lines) {
            val crop = straighten(upright, boxes[i].quad)
            val reading = read(crop, language)
            done[i] = Crop(crop, reading)
            asIs += reading.decoded.confidence
            flipped += read(crop.turned(180), language).decoded.confidence
        }
        if (flipped / lines.size <= asIs / lines.size + FLIP_MARGIN) return Oriented(turn, upright, boxes, done)
        val flippedPage = upright.turned(180)
        return Oriented((turn + 180) % 360, flippedPage, detect(flippedPage), emptyMap())
    }

    fun detect(page: PageImage, maxSide: Int = detectionMaxSide): List<TextBox> {
        val lb = DetLetterbox.of(page.width, page.height, maxSide)
        val argb = page.render(lb.width, lb.height, doubleArrayOf(lb.scale, 0.0, lb.padX, 0.0, lb.scale, lb.padY), BLACK).argb()
        val out = models.detector.run(Tensor(bgrNormalized(argb), longArrayOf(1, 3, lb.height.toLong(), lb.width.toLong())))
        val mapH = out.shape[out.shape.size - 2].toInt()
        val mapW = out.shape[out.shape.size - 1].toInt()
        return DbPostprocess.process(out.data, mapW, mapH).map { tb ->
            TextBox(
                tb.quad.map { p ->
                    Point(((p.x - lb.padX) / lb.scale).coerceIn(0.0, page.width - 1.0), ((p.y - lb.padY) / lb.scale).coerceIn(0.0, page.height - 1.0))
                },
                tb.score,
            )
        }
    }

    private class Reading(val decoded: CtcDecoder.Decoded, val scaledWidth: Int, val paddedWidth: Int)

    private fun recognizeBox(page: PageImage, quad: Quad, language: RecModel, done: Crop?): List<OcrWord> {
        val crop = done?.image ?: straighten(page, quad)
        val cropW = crop.width
        val cropH = crop.height
        var reading = done?.reading ?: read(crop, language)
        if (language.hasMultiFallback && reading.decoded.confidence < RETRY_BELOW) {
            val second = read(crop, RecModel.MULTI)
            if (second.decoded.text.isNotEmpty() && second.decoded.confidence >= RETRY_ACCEPT && second.decoded.confidence > reading.decoded.confidence) reading = second
        }
        val d = reading.decoded
        if (d.text.isBlank()) return emptyList()
        val words = if (Bidi.isRtl(d.text)) {
            null
        } else {
            WordSplitter.split(quad, crop.argb(), cropW, cropH, d.text, d.confidence, WordSplitter.Geometry(d.frameCount, reading.scaledWidth, reading.paddedWidth, d.charFrames))
        } ?: wordsFromTokens(quad, reading)
        return words.map { if (Bidi.containsRtl(it.text)) it.copy(text = Bidi.visualToLogical(it.text, true)) else it }
    }

    private fun straighten(page: PageImage, quad: Quad): PageImage {
        val tl = quad.topLeft
        val w = hypot(quad.topRight.x - tl.x, quad.topRight.y - tl.y)
        val h = hypot(quad.bottomLeft.x - tl.x, quad.bottomLeft.y - tl.y)
        val cropW = max(1, w.roundToInt())
        val cropH = max(1, h.roundToInt())
        val ax = (quad.topRight.x - tl.x) / max(w, 1e-9)
        val ay = (quad.topRight.y - tl.y) / max(w, 1e-9)
        val bx = (quad.bottomLeft.x - tl.x) / max(h, 1e-9)
        val by = (quad.bottomLeft.y - tl.y) / max(h, 1e-9)
        val sx = cropW / max(w, 1e-9)
        val sy = cropH / max(h, 1e-9)
        val affine = doubleArrayOf(ax * sx, ay * sx, -(tl.x * ax + tl.y * ay) * sx, bx * sy, by * sy, -(tl.x * bx + tl.y * by) * sy)
        return page.render(cropW, cropH, affine, WHITE)
    }

    private fun read(crop: PageImage, model: RecModel): Reading {
        val scaledW = max(REC_MIN_WIDTH, (crop.width * REC_HEIGHT.toDouble() / max(1, crop.height)).roundToInt())
        val paddedW = max(REC_STRIDE, (scaledW + REC_STRIDE - 1) / REC_STRIDE * REC_STRIDE)
        var source = crop
        while (source.height > 2 * REC_HEIGHT && source.width >= 2) source = source.scaled(max(1, source.width / 2), max(1, source.height / 2))
        val sx = scaledW.toDouble() / max(1, source.width)
        val sy = REC_HEIGHT.toDouble() / max(1, source.height)
        val argb = source.render(paddedW, REC_HEIGHT, doubleArrayOf(sx, 0.0, 0.0, 0.0, sy, 0.0), WHITE).argb()
        val out = models.recognizer(model).run(Tensor(rgbNormalized(argb), longArrayOf(1, 3, REC_HEIGHT.toLong(), paddedW.toLong())))
        val frames = out.shape[out.shape.size - 2].toInt()
        return Reading(CtcDecoder.decode(out.data, frames, models.vocab(model)), scaledW, paddedW)
    }

    private fun wordsFromTokens(quad: Quad, r: Reading): List<OcrWord> {
        val d = r.decoded
        if (d.tokens.size < 2 || d.frameCount <= 0) {
            val t = d.tokens.singleOrNull()
            return listOf(OcrWord(t?.text ?: d.text, quad.bounds(), t?.confidence ?: d.confidence))
        }
        return d.tokens.map { tok ->
            val uStart = tok.frameStart.toDouble() * r.paddedWidth / d.frameCount / r.scaledWidth
            val uEnd = (tok.frameEnd + 1).toDouble() * r.paddedWidth / d.frameCount / r.scaledWidth
            OcrWord(tok.text, quad.strip(uStart, max(uEnd, uStart + 1e-3)), tok.confidence)
        }
    }

    companion object {
        private const val ORIENTATION_LINES = 6
        private const val MIN_LINES = 3
        private const val ELONGATED = 2.0
        private const val FLIP_MARGIN = 0.05
        const val RETRY_BELOW = 0.6f
        const val RETRY_ACCEPT = 0.8f
        const val REC_HEIGHT = 48
        private const val REC_STRIDE = 8
        private const val REC_MIN_WIDTH = 16
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        private val MEAN_BGR = floatArrayOf(0.406f * 255f, 0.456f * 255f, 0.485f * 255f)
        private val STD_BGR = floatArrayOf(0.225f * 255f, 0.224f * 255f, 0.229f * 255f)

        internal fun bgrNormalized(argb: IntArray): FloatArray {
            val n = argb.size
            val out = FloatArray(3 * n)
            for (i in 0 until n) {
                val p = argb[i]
                out[i] = ((p and 0xFF) - MEAN_BGR[0]) / STD_BGR[0]
                out[n + i] = (((p shr 8) and 0xFF) - MEAN_BGR[1]) / STD_BGR[1]
                out[2 * n + i] = (((p shr 16) and 0xFF) - MEAN_BGR[2]) / STD_BGR[2]
            }
            return out
        }

        internal fun rgbNormalized(argb: IntArray): FloatArray {
            val n = argb.size
            val out = FloatArray(3 * n)
            for (i in 0 until n) {
                val p = argb[i]
                out[i] = ((p shr 16) and 0xFF) / 127.5f - 1f
                out[n + i] = ((p shr 8) and 0xFF) / 127.5f - 1f
                out[2 * n + i] = (p and 0xFF) / 127.5f - 1f
            }
            return out
        }
    }
}

fun PageImage.turned(degrees: Int): PageImage {
    val w = width.toDouble()
    val h = height.toDouble()
    return when (((degrees % 360) + 360) % 360) {
        0 -> this
        90 -> render(height, width, doubleArrayOf(0.0, -1.0, h, 1.0, 0.0, 0.0), OcrEngine.WHITE)
        180 -> render(width, height, doubleArrayOf(-1.0, 0.0, w, 0.0, -1.0, h), OcrEngine.WHITE)
        270 -> render(height, width, doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, w), OcrEngine.WHITE)
        else -> throw IllegalArgumentException("not a quarter turn: $degrees")
    }
}

class DetLetterbox private constructor(val width: Int, val height: Int, val scale: Double, val padX: Double, val padY: Double) {
    companion object {
        private const val STRIDE = 32

        fun of(srcW: Int, srcH: Int, maxSide: Int): DetLetterbox {
            val side = max(STRIDE, maxSide / STRIDE * STRIDE)
            val scale = min(1.0, side.toDouble() / max(srcW, srcH))
            fun ceilStride(v: Int) = (v + STRIDE - 1) / STRIDE * STRIDE
            val w = ceilStride(max(STRIDE, (srcW * scale).roundToInt()))
            val h = ceilStride(max(STRIDE, (srcH * scale).roundToInt()))
            return DetLetterbox(w, h, scale, (w - srcW * scale) / 2.0, (h - srcH * scale) / 2.0)
        }
    }
}
