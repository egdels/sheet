// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.ocr

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import de.schliweb.sheet.core.corners.PageImage
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
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

class OcrEngine(private val models: OcrModels, private val detectionMaxSide: Int = 1920, readers: Int = 1, private val probeScripts: Boolean = true, private val withV6: Boolean = true) {
    private val pool: ExecutorService? = if (readers > 1) {
        Executors.newFixedThreadPool(readers) { r -> Thread(r, "ocr-reader").apply { isDaemon = true } }
    } else {
        null
    }

    private fun <T> inParallel(count: Int, cost: (Int) -> Double = { 0.0 }, f: (Int) -> T): List<T> {
        val p = pool ?: return List(count, f)
        val futures = arrayOfNulls<Future<T>>(count)
        for (i in (0 until count).sortedByDescending(cost)) futures[i] = p.submit(Callable { f(i) })
        return futures.map { future ->
            try {
                future!!.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    fun recognize(page: PageImage, language: RecModel): OcrPage = recognize(page, detect(page), language, emptyMap())

    fun recognize(page: PageImage, languages: List<RecModel>): Read {
        val boxes = detect(page)
        val lines = longestLines(boxes)
        val crops = inParallel(lines.size) { k -> straighten(page, boxes[lines[k]].quad) }
        val choice = choose(crops, languages, bothWays = false)
        val reader = readerOf(choice.model)
        val done = if (reader != choice.model) emptyMap() else lines.indices.associate { k -> lines[k] to Crop(crops[k], choice.asIs[k]) }
        return Read(reader, recognize(page, boxes, reader, done, partnerOf(reader, choice.model)))
    }

    class Read(val model: RecModel, val text: OcrPage)

    fun recognizeUpright(page: PageImage, language: RecModel): Upright = recognizeUpright(page, listOf(language))

    fun recognizeUpright(page: PageImage, languages: List<RecModel>): Upright {
        val o = orient(page, languages)
        return Upright(o.degrees, recognize(o.page, o.boxes, o.model, o.done, partnerOf(o.model, o.script)), o.model)
    }

    class Upright(val degrees: Int, val text: OcrPage, val model: RecModel)

    private class Crop(val image: PageImage, val reading: Reading)

    private fun readerOf(script: RecModel): RecModel = if (withV6 && (script == RecModel.LATIN || script == RecModel.MULTI)) RecModel.V6 else script

    private fun partnerOf(model: RecModel, script: RecModel): RecModel? = if (model == RecModel.V6 && script != RecModel.V6) script else null

    private fun recognize(page: PageImage, boxes: List<TextBox>, language: RecModel, done: Map<Int, Crop>, partner: RecModel? = null): OcrPage {
        val read = inParallel(boxes.size, { boxes[it].bounds.let { b -> b.width / max(1.0, b.height) } }) { i -> recognizeBox(page, boxes[i].quad, language, done[i], partner) }
        val bounds = boxes.map { it.bounds }
        val rtl = Bidi.isRtl(read.flatten().joinToString("") { it.text })
        val segments = ColumnLayout.segments(bounds, rtl).ifEmpty { listOf(bounds.indices.toList()) }
        val lines = segments.flatMap { segment ->
            Lines.group(segment.map { bounds[it] }).map { row ->
                val words = row.flatMap { read[segment[it]] }
                OcrLine(words, Bidi.isRtl(words.joinToString("") { it.text }))
            }
        }
        val text = OcrPage(page.width, page.height, lines.filter { it.words.isNotEmpty() })
        return if (language == RecModel.ARABIC && LetterForms.isPersian(text)) LetterForms.persian(text) else text
    }

    fun orientation(page: PageImage, language: RecModel): Int = orientation(page, listOf(language))

    fun orientation(page: PageImage, languages: List<RecModel>): Int = orient(page, languages).degrees

    private class Oriented(val degrees: Int, val page: PageImage, val boxes: List<TextBox>, val done: Map<Int, Crop>, val model: RecModel, val script: RecModel = model)

    private fun orient(page: PageImage, languages: List<RecModel>): Oriented {
        val boxes = detect(page)
        val elongated = boxes.map { it.bounds }.filter { max(it.width, it.height) >= ELONGATED * min(it.width, it.height) }
        val tall = elongated.count { it.height > it.width }
        if (tall < MIN_LINES || tall <= 2 * (elongated.size - tall)) return judge(page, boxes, languages, 0)
        val turned = page.turned(90)
        val o = judge(turned, detect(turned), languages, 90)
        if (o.script != RecModel.MULTI) return o
        val columns = boxes.indices.filter { i -> boxes[i].bounds.let { it.height >= ELONGATED * it.width } }
            .sortedByDescending { boxes[it].bounds.height }.take(ORIENTATION_LINES)
        val cells = inParallel(columns.size) { k ->
            val crop = straighten(page, boxes[columns[k]].quad)
            readCells(crop, RecModel.MULTI).decoded.confidence to readCells(crop.turned(180), RecModel.MULTI).decoded.confidence
        }
        val up = cells.sumOf { it.first.toDouble() } / cells.size
        val down = cells.sumOf { it.second.toDouble() } / cells.size
        if (max(up, down) < NOT_COLUMNS) return o
        if (max(up, down) < COLUMNS_FROM) return if (languages.first() == RecModel.MULTI) judge(page, boxes, languages, 0) else o
        if (down <= up + FLIP_MARGIN) return Oriented(0, page, boxes, emptyMap(), RecModel.MULTI)
        val flipped = page.turned(180)
        return Oriented(180, flipped, detect(flipped), emptyMap(), RecModel.MULTI)
    }

    private fun judge(upright: PageImage, boxes: List<TextBox>, languages: List<RecModel>, turn: Int): Oriented {
        val lines = longestLines(boxes)
        val crops = inParallel(lines.size) { k -> straighten(upright, boxes[lines[k]].quad) }
        val choice = choose(crops, languages, bothWays = lines.size >= MIN_LINES)
        val flipped = choice.flipped
        val reader = readerOf(choice.model)
        if (flipped == null || mean(flipped) <= mean(choice.asIs) + FLIP_MARGIN) {
            val done = if (reader != choice.model) emptyMap() else lines.indices.associate { k -> lines[k] to Crop(crops[k], choice.asIs[k]) }
            return Oriented(turn, upright, boxes, done, reader, choice.model)
        }
        val flippedPage = upright.turned(180)
        return Oriented((turn + 180) % 360, flippedPage, detect(flippedPage), emptyMap(), reader, choice.model)
    }

    private fun longestLines(boxes: List<TextBox>): List<Int> = boxes.indices.filter { i -> boxes[i].quad.bounds().let { it.width >= ELONGATED * it.height } }
        .sortedByDescending { boxes[it].quad.bounds().width }.take(ORIENTATION_LINES)

    private class Probe(val model: RecModel, val asIs: List<Reading>, val flipped: List<Reading>?) {
        val score: Double = max(mean(asIs), flipped?.let { mean(it) } ?: 0.0)

        val latin: Boolean get() {
            val best = if (flipped != null && mean(flipped) > mean(asIs)) flipped else asIs
            var latin = 0
            var other = 0
            for (r in best) {
                r.decoded.text.codePoints().forEach { cp ->
                    if (Character.isLetter(cp)) if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) latin++ else other++
                }
            }
            return latin > other
        }
    }

    private fun choose(crops: List<PageImage>, languages: List<RecModel>, bothWays: Boolean): Probe {
        fun probe(model: RecModel, turned: Boolean = bothWays): Probe {
            val r = inParallel(crops.size) { k -> read(crops[k], model) to if (turned) read(crops[k].turned(180), model) else null }
            return Probe(model, r.map { it.first }, if (turned) r.map { it.second!! } else null)
        }

        fun lookalikes(latin: Probe): Probe {
            val rival = LOOKALIKES.map { probe(it, turned = false) }.filter { !it.latin && it.score > latin.score }.maxByOrNull { it.score }
                ?: return latin
            return if (bothWays) probe(rival.model) else rival
        }
        val first = probe(languages.first())
        if (!probeScripts || crops.isEmpty()) return first
        var pick = first
        if (first.score < SURE) {
            val others = (languages + RecModel.entries).distinct() - first.model - RecModel.V6
            val best = others.map { probe(it) }.maxBy { it.score }
            if (best.score > first.score + SWITCH_MARGIN) pick = best
        }
        if (pick.model == RecModel.LATIN) return lookalikes(pick)
        if (!pick.latin) return pick
        val latin = probe(RecModel.LATIN)
        return if (latin.score >= pick.score - SWITCH_MARGIN) lookalikes(latin) else pick
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

    private fun recognizeBox(page: PageImage, quad: Quad, language: RecModel, done: Crop?, partner: RecModel? = null): List<OcrWord> {
        val crop = done?.image ?: straighten(page, quad)
        val cropW = crop.width
        val cropH = crop.height
        var reading = done?.reading ?: read(crop, language)
        if (language.hasMultiFallback && reading.decoded.confidence < RETRY_BELOW) {
            val second = read(crop, RecModel.MULTI)
            if (second.decoded.text.isNotEmpty() && second.decoded.confidence >= RETRY_ACCEPT && second.decoded.confidence > reading.decoded.confidence) reading = second
        }
        if (partner != null && (reading.decoded.confidence < RETRY_BELOW || reading.decoded.text.any { it.code > 0x7F })) {
            val other = read(crop, partner)
            if (other.decoded.text.isNotEmpty() && other.decoded.confidence > reading.decoded.confidence) reading = other
        }
        if (language == RecModel.ARABIC) reading = withNumbers(crop, reading)
        val d = reading.decoded
        if (d.text.isBlank()) return emptyList()
        val words = if (Bidi.isRtl(d.text)) {
            null
        } else {
            WordSplitter.split(quad, crop.argb(), cropW, cropH, d.text, d.confidence, WordSplitter.Geometry(d.frameCount, reading.scaledWidth, reading.paddedWidth, d.charFrames))
        } ?: wordsFromTokens(quad, reading)
        return words.map { if (Bidi.containsRtl(it.text)) it.copy(text = Bidi.visualToLogical(it.text, true)) else it }
    }

    private fun withNumbers(crop: PageImage, arabic: Reading): Reading {
        val a = arabic.decoded
        if (a.frameCount <= 0 || a.tokens.isEmpty()) return arabic
        val toX = crop.width.toDouble() * arabic.paddedWidth / (a.frameCount.toDouble() * arabic.scaledWidth)
        val lastFrame = (arabic.scaledWidth.toDouble() * a.frameCount / arabic.paddedWidth).toInt() - 1
        val words = a.tokens.sortedBy { it.frameStart }
        val gaps = buildList {
            add(0 to words.first().frameStart - 1)
            for (k in 1 until words.size) add(words[k - 1].frameEnd + 1 to words[k].frameStart - 1)
            add(words.last().frameEnd + 1 to lastFrame)
        }.filter { (from, to) -> to - from + 1 >= NUMBER_GAP }
        val numbers = gaps.mapNotNull { (from, to) ->
            val x0 = (from * toX).roundToInt().coerceIn(0, crop.width)
            val x1 = ((to + 1) * toX).roundToInt().coerceIn(0, crop.width)
            if (x1 - x0 < crop.height / 2) return@mapNotNull null
            val gap = crop.render(x1 - x0, crop.height, doubleArrayOf(1.0, 0.0, -x0.toDouble(), 0.0, 1.0, 0.0), WHITE)
            val found = read(gap, RecModel.LATIN).decoded.tokens.filter { t ->
                t.confidence >= RETRY_ACCEPT && t.text.any { it.isDigit() } && t.text.all { it.isDigit() || it in NUMBER_MARKS }
            }
            if (found.isEmpty()) return@mapNotNull null
            val visual = found.joinToString(" ") { it.text }
            val number = visual.dropWhile { it == '.' || it == ',' } + visual.takeWhile { it == '.' || it == ',' }
            CtcDecoder.Token(number, found.minOf { it.confidence }, from, to)
        }
        if (numbers.isEmpty()) return arabic
        val tokens = (words + numbers).sortedBy { it.frameStart }
        val text = tokens.joinToString(" ") { it.text }
        return Reading(a.copy(text = text, tokens = tokens, charFrames = IntArray(text.length)), arabic.scaledWidth, arabic.paddedWidth)
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

    private fun readCells(crop: PageImage, model: RecModel): Reading {
        val side = max(1, crop.width)
        val n = max(1, (crop.height.toDouble() / side).roundToInt())
        val cell = REC_HEIGHT
        val scale = cell.toDouble() / side
        val argb = IntArray(cell * cell * n)
        for (k in 0 until n) {
            val px = crop.render(cell, cell, doubleArrayOf(scale, 0.0, 0.0, 0.0, scale, -k * side * scale), WHITE).argb()
            for (y in 0 until cell) System.arraycopy(px, y * cell, argb, y * cell * n + k * cell, cell)
        }
        val out = models.recognizer(model).run(Tensor(rgbNormalized(argb), longArrayOf(1, 3, cell.toLong(), (cell * n).toLong())))
        val frames = out.shape[out.shape.size - 2].toInt()
        return Reading(CtcDecoder.decode(out.data, frames, models.vocab(model)), cell * n, cell * n)
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
        private const val SURE = 0.93
        private const val SWITCH_MARGIN = 0.1
        private val LOOKALIKES = listOf(RecModel.ESLAV, RecModel.CYRILLIC, RecModel.GREEK)
        private const val NUMBER_MARKS = ":.,/-+%"
        private const val NUMBER_GAP = 4
        private const val COLUMNS_FROM = 0.8
        private const val NOT_COLUMNS = 0.7

        private fun mean(r: List<Reading>): Double = if (r.isEmpty()) 0.0 else r.sumOf { it.decoded.confidence.toDouble() } / r.size
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
