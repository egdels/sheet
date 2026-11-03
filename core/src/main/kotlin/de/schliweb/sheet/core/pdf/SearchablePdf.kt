// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.pdf

import de.schliweb.sheet.core.ocr.OcrPage
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PdfPage(val jpeg: ByteArray, val text: OcrPage?, val turns: Int = 0, val textTurns: Int = turns)

object SearchablePdf {
    private val PAPERS = listOf(595.28 to 841.89, 612.0 to 792.0)
    private const val PAPER_TOLERANCE = 0.03

    fun write(out: OutputStream, pageCount: Int, pages: (Int) -> PdfPage, created: ZonedDateTime = ZonedDateTime.now()) {
        val w = PdfOutput(out)
        w.raw("%PDF-1.7\n%âãÏÓ\n")
        val firstPage = 9
        w.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        val kids = (0 until pageCount).joinToString(" ") { "${firstPage + 3 * it} 0 R" }
        w.obj(2, "<< /Type /Pages /Kids [$kids] /Count $pageCount >>")
        w.obj(3, "<< /Type /Font /Subtype /Type0 /BaseFont /${GlyphlessFont.NAME} /Encoding /Identity-H /DescendantFonts [4 0 R] /ToUnicode 7 0 R >>")
        w.obj(4, "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /${GlyphlessFont.NAME} /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /FontDescriptor 5 0 R /DW ${GlyphlessFont.ADVANCE} /CIDToGIDMap /Identity >>")
        w.obj(5, "<< /Type /FontDescriptor /FontName /${GlyphlessFont.NAME} /Flags 5 /FontBBox [0 0 ${GlyphlessFont.ADVANCE} 1000] /ItalicAngle 0 /Ascent 1000 /Descent -1 /CapHeight 1000 /StemV 80 /FontFile2 6 0 R >>")
        w.stream(6, "/Length1 ${GlyphlessFont.bytes.size}", GlyphlessFont.bytes)
        w.obj(8, "<< /Producer (Sheet) /CreationDate (${pdfDate(created)}) >>")
        val astral = LinkedHashMap<Int, Int>()
        for (i in 0 until pageCount) {
            val page = pages(i)
            val (pw, ph) = Jpeg.size(page.jpeg)
            val drawn = if (page.text != null) page.textTurns else page.turns
            val sideways = drawn.mod(2) == 1
            val (width, height) = if (sideways) paperSize(ph, pw) else paperSize(pw, ph)
            val rotate = 90 * (page.turns - drawn).mod(4)
            val n = firstPage + 3 * i
            w.obj(n, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(width)} ${num(height)}]${if (rotate != 0) " /Rotate $rotate" else ""} /Resources << /XObject << /Im0 ${n + 2} 0 R >> /Font << /F1 3 0 R >> >> /Contents ${n + 1} 0 R >>")
            w.stream(n + 1, "", content(page.text, drawn, width, height, astral).toByteArray(Charsets.US_ASCII))
            w.stream(n + 2, "/Type /XObject /Subtype /Image /Width $pw /Height $ph /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", page.jpeg)
        }
        w.stream(7, "", toUnicode(astral).toByteArray(Charsets.US_ASCII))
        w.finish(rootObj = 1, infoObj = 8)
    }

    internal fun paperSize(pw: Int, ph: Int): Pair<Double, Double> {
        val ratio = min(pw, ph).toDouble() / max(pw, ph)
        val landscape = pw > ph
        for ((short, long) in PAPERS) {
            if (abs(ratio - short / long) <= PAPER_TOLERANCE) return if (landscape) long to short else short to long
        }
        val long = PAPERS[0].second
        val short = long * ratio
        return if (landscape) long to short else short to long
    }

    internal fun imageMatrix(turns: Int, width: Double, height: Double): DoubleArray = when (turns.mod(4)) {
        0 -> doubleArrayOf(width, 0.0, 0.0, height, 0.0, 0.0)
        1 -> doubleArrayOf(0.0, -height, width, 0.0, 0.0, height)
        2 -> doubleArrayOf(-width, 0.0, 0.0, -height, width, height)
        else -> doubleArrayOf(0.0, height, -width, 0.0, width, 0.0)
    }

    private fun content(text: OcrPage?, turns: Int, width: Double, height: Double, astral: MutableMap<Int, Int>): String {
        val s = StringBuilder()
        s.append("q ${imageMatrix(turns, width, height).joinToString(" ") { num(it) }} cm /Im0 Do Q\n")
        if (text == null || text.words.isEmpty() || text.width <= 0 || text.height <= 0) return s.toString()
        val sx = width / text.width
        val sy = height / text.height
        s.append("BT 3 Tr\n")
        for (line in text.lines) {
            val words = line.inReadingOrder
            for ((k, word) in words.withIndex()) {
                val chars = word.text.codePointCount(0, word.text.length)
                if (chars == 0) continue
                val b = word.box
                val size = max(1.0, b.height * sy)
                val boxWidth = max(0.1, b.width * sx)
                val scale = 100.0 * boxWidth / (chars * size * GlyphlessFont.ADVANCE / 1000.0)
                val shown = if (k < words.size - 1) word.text + " " else word.text
                s.append("/F1 ${num(size)} Tf ${num(scale)} Tz 1 0 0 1 ${num(b.left * sx)} ${num(height - b.bottom * sy)} Tm <")
                shown.codePoints().forEach { cp -> s.append(String.format(Locale.ROOT, "%04X", code(cp, astral))) }
                s.append("> Tj\n")
            }
        }
        s.append("ET\n")
        return s.toString()
    }

    private fun code(cp: Int, astral: MutableMap<Int, Int>): Int = when {
        cp < 0x10000 -> if (cp in 0xD800..0xDFFF) 0xFFFD else cp
        else -> astral[cp] ?: if (astral.size < ASTRAL_CODES) (0xD800 + astral.size).also { astral[cp] = it } else 0xFFFD
    }

    private const val ASTRAL_CODES = 0x800

    private fun num(v: Double): String {
        require(v.isFinite()) { "not a number: $v" }
        val r = String.format(Locale.ROOT, "%.2f", v)
        return r.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    private fun pdfDate(t: ZonedDateTime): String {
        val offset = t.offset.totalSeconds / 60
        val sign = if (offset < 0) "-" else "+"
        return String.format(Locale.ROOT, "D:%04d%02d%02d%02d%02d%02d%s%02d'%02d'", t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second, sign, abs(offset) / 60, abs(offset) % 60)
    }

    private fun toUnicode(astral: Map<Int, Int>): String {
        val s = StringBuilder()
        s.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        s.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        s.append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        s.append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        s.append("2 beginbfrange\n<0000> <D7FF> <0000>\n<E000> <FFFF> <E000>\nendbfrange\n")
        for (chunk in astral.entries.chunked(100)) {
            s.append("${chunk.size} beginbfchar\n")
            for ((cp, code) in chunk) {
                val utf16 = String(Character.toChars(cp)).map { String.format(Locale.ROOT, "%04X", it.code) }.joinToString("")
                s.append(String.format(Locale.ROOT, "<%04X> <%s>\n", code, utf16))
            }
            s.append("endbfchar\n")
        }
        s.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
        return s.toString()
    }
}

private class PdfOutput(out: OutputStream) {
    private val counter = object : FilterOutputStream(out) {
        var count = 0L

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
    private val offsets = sortedMapOf<Int, Long>()

    fun raw(s: String) = counter.write(s.toByteArray(Charsets.ISO_8859_1))

    fun obj(n: Int, body: String) {
        offsets[n] = counter.count
        raw("$n 0 obj\n$body\nendobj\n")
    }

    fun stream(n: Int, dict: String, data: ByteArray) {
        offsets[n] = counter.count
        raw("$n 0 obj\n<< $dict /Length ${data.size} >>\nstream\n")
        counter.write(data)
        raw("\nendstream\nendobj\n")
    }

    fun finish(rootObj: Int, infoObj: Int) {
        val size = offsets.lastKey() + 1
        val xref = counter.count
        raw("xref\n0 $size\n0000000000 65535 f \n")
        for (n in 1 until size) raw(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[n] ?: error("object $n missing")))
        raw("trailer\n<< /Size $size /Root $rootObj 0 R /Info $infoObj 0 R >>\nstartxref\n$xref\n%%EOF\n")
        counter.flush()
    }
}
