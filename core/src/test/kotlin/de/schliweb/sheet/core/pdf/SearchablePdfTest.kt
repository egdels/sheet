// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.pdf

import de.schliweb.sheet.core.ocr.Box
import de.schliweb.sheet.core.ocr.OcrLine
import de.schliweb.sheet.core.ocr.OcrPage
import de.schliweb.sheet.core.ocr.OcrWord
import org.apache.fontbox.ttf.TTFParser
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchablePdfTest {
    private fun jpeg(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, w, h)
            color = Color.DARK_GRAY
            fillRect(w / 10, h / 10, w / 2, h / 40)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun word(text: String, l: Double, t: Double, r: Double, b: Double) = OcrWord(text, Box(l, t, r, b), 0.9f)

    @Test
    fun fontIsValidTrueType() {
        val font = TTFParser().parse(RandomAccessReadBuffer(GlyphlessFont.bytes))
        assertEquals(2, font.numberOfGlyphs)
        assertEquals(1000, font.unitsPerEm)
        assertEquals(GlyphlessFont.ADVANCE, font.getAdvanceWidth(1))
        assertEquals(GlyphlessFont.NAME, font.name)
        assertEquals(0xB1B0AFBAL, GlyphlessFont.checksum(GlyphlessFont.bytes))
    }

    @Test
    fun pagesImagesAndText() {
        val first = jpeg(1240, 1754)
        val second = jpeg(800, 2400)
        val text = OcrPage(
            1240,
            1754,
            listOf(
                OcrLine(listOf(word("Hallo", 124.0, 175.0, 300.0, 215.0), word("Welt", 320.0, 175.0, 440.0, 215.0)), rtl = false),
                OcrLine(listOf(word("Привет", 124.0, 300.0, 400.0, 340.0)), rtl = false),
                OcrLine(listOf(word("مرحبا", 124.0, 400.0, 400.0, 440.0)), rtl = true),
            ),
        )
        val out = ByteArrayOutputStream()
        SearchablePdf.write(out, 2, { if (it == 0) PdfPage(first, text) else PdfPage(second, null) })
        Loader.loadPDF(out.toByteArray()).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val a4 = doc.getPage(0).mediaBox
            assertEquals(595.28f, a4.width, 0.01f)
            assertEquals(841.89f, a4.height, 0.01f)
            val receipt = doc.getPage(1).mediaBox
            assertEquals(841.89f, receipt.height, 0.01f)
            assertEquals(841.89f / 3, receipt.width, 0.01f)
            val resources = doc.getPage(0).resources
            val image = resources.getXObject(resources.xObjectNames.single()) as PDImageXObject
            assertEquals(1240, image.width)
            assertContentEquals(first, image.cosObject.createRawInputStream().use { it.readBytes() })
            val font = resources.getFont(resources.fontNames.single()) as PDType0Font
            assertTrue(font.fontDescriptor.descent < 0, "descent ${font.fontDescriptor.descent}")
            val extracted = PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(doc)
            assertTrue("Hallo Welt" in extracted, extracted)
            assertTrue("Привет" in extracted, extracted)
            assertTrue("مرحبا" in extracted || "ابحرم" in extracted, extracted)
            val starts = mutableMapOf<String, Float>()
            object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: MutableList<org.apache.pdfbox.text.TextPosition>) {
                    starts[text] = textPositions.first().xDirAdj
                }
            }.apply { startPage = 1; endPage = 1 }.getText(doc)
            val hallo = starts.entries.first { it.key.startsWith("Hallo") }.value
            assertEquals(124 * 595.28f / 1240, hallo, 1f)
        }
    }

    @Test
    fun mixedLineInReadingOrder() {
        val line = OcrLine(
            listOf(word("Hello", 100.0, 100.0, 300.0, 140.0), word("World", 320.0, 100.0, 520.0, 140.0), word("بالعالم", 560.0, 100.0, 800.0, 140.0), word("أهلا", 820.0, 100.0, 1000.0, 140.0)),
            rtl = true,
        )
        val out = ByteArrayOutputStream()
        SearchablePdf.write(out, 1, { PdfPage(jpeg(1240, 1754), OcrPage(1240, 1754, listOf(line))) })
        Loader.loadPDF(out.toByteArray()).use { doc ->
            val shown = mutableListOf<String>()
            object : PDFTextStripper() {
                override fun processTextPosition(text: org.apache.pdfbox.text.TextPosition) {
                    shown += text.unicode
                }
            }.getText(doc)
            assertEquals("أهلا بالعالم Hello World", shown.joinToString(""))
        }
    }

    @Test
    fun jpegSizeFromHeader() {
        assertEquals(321 to 123, Jpeg.size(jpeg(321, 123)))
    }

    @Test
    fun paperSizes() {
        assertEquals(595.28 to 841.89, SearchablePdf.paperSize(2480, 3508))
        assertEquals(841.89 to 595.28, SearchablePdf.paperSize(3508, 2480))
        assertEquals(612.0 to 792.0, SearchablePdf.paperSize(2550, 3300))
    }

    @Test
    fun turnedPagesAreDrawnTurnedWithoutTouchingTheJpeg() {
        val img = BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, 1240, 1754)
            color = Color.BLACK
            fillRect(0, 0, 310, 438)
            dispose()
        }
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
        val out = ByteArrayOutputStream()
        SearchablePdf.write(out, 4, { PdfPage(jpeg, null, turns = it) })
        Loader.loadPDF(out.toByteArray()).use { doc ->
            val renderer = PDFRenderer(doc)
            val corners = listOf(0.1 to 0.1, 0.9 to 0.1, 0.9 to 0.9, 0.1 to 0.9)
            for (turns in 0..3) {
                val box = doc.getPage(turns).mediaBox
                if (turns % 2 == 0) assertEquals(595.28f, box.width, 0.01f) else assertEquals(841.89f, box.width, 0.01f)
                val resources = doc.getPage(turns).resources
                val image = resources.getXObject(resources.xObjectNames.single()) as PDImageXObject
                assertContentEquals(jpeg, image.cosObject.createRawInputStream().use { it.readBytes() })
                val shot = renderer.renderImageWithDPI(turns, 18f)
                for ((k, corner) in corners.withIndex()) {
                    val rgb = shot.getRGB((corner.first * shot.width).toInt(), (corner.second * shot.height).toInt()) and 0xFF
                    if (k == turns) assertTrue(rgb < 64, "turns $turns: block not at corner $k ($rgb)") else assertTrue(rgb > 192, "turns $turns: corner $k is dark ($rgb)")
                }
            }
        }
    }
}
