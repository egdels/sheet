// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.ocr

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CtcDecoderTest {
    private val vocab = Vocab.fromLines(sequenceOf("a", "b", " "))

    private fun frames(vararg classes: Int): FloatArray {
        val out = FloatArray(classes.size * vocab.size)
        classes.forEachIndexed { t, c -> out[t * vocab.size + c] = 1f }
        return out
    }

    @Test
    fun dropsBlanksAndRepeatsAndSplitsAtSpaces() {
        val d = CtcDecoder.decode(frames(1, 1, 0, 1, 2, 0, 3, 2), 8, vocab)
        assertEquals("aab b", d.text)
        assertEquals(listOf("aab", "b"), d.tokens.map { it.text })
        assertEquals(0 to 4, d.tokens[0].frameStart to d.tokens[0].frameEnd)
        assertEquals(listOf(0, 3, 4, 6, 7), d.charFrames.toList())
        assertEquals(1f, d.confidence)
    }

    @Test
    fun logitsGoThroughSoftmax() {
        val logits = floatArrayOf(0f, 2f, 0f, 0f)
        val d = CtcDecoder.decode(logits, 1, vocab)
        assertEquals("a", d.text)
        val expected = Math.exp(2.0) / (Math.exp(2.0) + 3)
        assertEquals(expected, d.confidence.toDouble(), 1e-6)
    }
}

class DbPostprocessTest {
    private fun map(w: Int, h: Int, inside: (Int, Int) -> Boolean) = FloatArray(w * h) { if (inside(it % w, it / w)) 0.9f else 0f }

    @Test
    fun straightLineGetsUnclippedAxisAlignedBox() {
        val m = map(200, 40) { x, y -> x in 20 until 180 && y in 15 until 25 }
        val box = DbPostprocess.process(m, 200, 40).single()
        val d = 160.0 * 10 * 1.5 / 340
        assertEquals(Quad(Point(20 - d, 15 - d), Point(180 + d, 15 - d), Point(180 + d, 25 + d), Point(20 - d, 25 + d)), box.quad)
    }

    @Test
    fun tiltedLineGetsTiltedBox() {
        val angle = Math.toRadians(3.0)
        val m = map(400, 120) { x, y ->
            val dx = x + 0.5 - 200
            val dy = y + 0.5 - 60
            abs(dx * cos(angle) + dy * sin(angle)) < 150 && abs(-dx * sin(angle) + dy * cos(angle)) < 4
        }
        val q = DbPostprocess.process(m, 400, 120).single().quad
        val topAngle = Math.toDegrees(atan2(q.topRight.y - q.topLeft.y, q.topRight.x - q.topLeft.x))
        assertEquals(3.0, topAngle, 0.2)
        val h = Math.hypot(q.bottomLeft.x - q.topLeft.x, q.bottomLeft.y - q.topLeft.y)
        assertTrue(h < 25, "height $h")
    }

    @Test
    fun weakOrTinyComponentsAreDropped() {
        val weak = FloatArray(100 * 30) { i -> if (i % 100 in 10 until 90 && i / 100 in 10 until 20) 0.4f else 0f }
        assertTrue(DbPostprocess.process(weak, 100, 30).isEmpty())
        val tiny = map(100, 30) { x, y -> x in 10 until 13 && y in 10 until 13 }
        assertTrue(DbPostprocess.process(tiny, 100, 30).isEmpty())
    }
}

class LayoutPartsTest {
    @Test
    fun linesGroupByCentreAndSortLeftToRight() {
        val boxes = listOf(Box(10.0, 50.0, 200.0, 70.0), Box(300.0, 10.0, 400.0, 30.0), Box(10.0, 12.0, 200.0, 32.0))
        assertEquals(listOf(listOf(2, 1), listOf(0)), Lines.group(boxes))
    }

    @Test
    fun bidiKeepsEmbeddedNumbersReadable() {
        val visual = "123 با"
        assertEquals("اب 123", Bidi.visualToLogical(visual, rtlBase = true))
        assertTrue(Bidi.isRtl("ابت a"))
    }

    @Test
    fun wordGapsSplitSegments() {
        val profile = IntArray(60)
        fun ink(from: Int, to: Int) { for (x in from until to) profile[x] = 2000 }
        ink(0, 6); ink(8, 14); ink(16, 22); ink(32, 38); ink(40, 46)
        assertEquals(listOf(0 to 21, 32 to 45), WordSplitter.segments(profile, 20))
        assertNull(WordSplitter.segments(IntArray(60) { if (it < 40) 2000 else 0 }, 20))
    }

    @Test
    fun detLetterboxIsStrideAligned() {
        val lb = DetLetterbox.of(3000, 4000, 1920)
        assertEquals(1440, lb.width)
        assertEquals(1920, lb.height)
        val small = DetLetterbox.of(500, 300, 1920)
        assertEquals(512 to 320, small.width to small.height)
        assertEquals(1.0, small.scale)
    }

    @Test
    fun languageFromLocale() {
        assertEquals(RecModel.LATIN, RecModel.forLocale(Locale.GERMANY))
        assertEquals(RecModel.EN, RecModel.forLocale(Locale.US))
        assertEquals(RecModel.ESLAV, RecModel.forLocale(Locale.forLanguageTag("ru")))
        assertEquals(RecModel.LATIN, RecModel.forLocale(Locale.forLanguageTag("sr-Latn")))
        assertEquals(RecModel.CYRILLIC, RecModel.forLocale(Locale.forLanguageTag("sr")))
        assertEquals(RecModel.MULTI, RecModel.forLocale(Locale.JAPAN))
        assertEquals(RecModel.MULTI, RecModel.forLocale(Locale.forLanguageTag("xx")))
    }

    @Test
    fun quarterTurnsMovePixelsClockwise() {
        class Img(override val width: Int, override val height: Int, val marked: Pair<Double, Double>) : de.schliweb.sheet.core.corners.PageImage {
            override fun render(width: Int, height: Int, affine: DoubleArray, background: Int): de.schliweb.sheet.core.corners.PageImage {
                val (x, y) = marked.first + 0.5 to marked.second + 0.5
                return Img(width, height, (affine[0] * x + affine[1] * y + affine[2] - 0.5) to (affine[3] * x + affine[4] * y + affine[5] - 0.5))
            }
            override fun letterboxed(letterbox: de.schliweb.sheet.core.corners.Letterbox) = throw UnsupportedOperationException()
            override fun scaled(width: Int, height: Int) = throw UnsupportedOperationException()
            override fun argb() = throw UnsupportedOperationException()
            override fun rotated(frame: de.schliweb.sheet.core.corners.RotatedFrame) = throw UnsupportedOperationException()
        }
        val img = Img(3, 2, 0.0 to 0.0)
        assertEquals(Triple(2, 3, 1.0 to 0.0), (img.turned(90) as Img).let { Triple(it.width, it.height, it.marked) })
        assertEquals(2.0 to 1.0, (img.turned(180) as Img).marked)
        assertEquals(Triple(2, 3, 0.0 to 2.0), (img.turned(270) as Img).let { Triple(it.width, it.height, it.marked) })
    }
}
