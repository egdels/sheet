// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.eval

import de.schliweb.sheet.core.image.Enhance
import de.schliweb.sheet.core.ocr.OcrEngine
import de.schliweb.sheet.core.ocr.RecModel
import de.schliweb.sheet.core.ocr.turned
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.min

object OcrEval {
    fun run(args: List<String>) {
        var models = File("app/src/main/assets/paddleocr")
        var maxSide = 1920
        var out: File? = null
        var orientation = false
        var enhance = false
        var upright = false
        var locales: List<RecModel>? = null
        var probe = true
        var v6 = true
        val dirs = mutableListOf<File>()
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--models" -> models = File(args[++i])
                "--max-side" -> maxSide = args[++i].toInt()
                "--text" -> out = File(args[++i])
                "--orientation" -> orientation = true
                "--enhance" -> enhance = true
                "--upright" -> upright = true
                "--locale" -> {
                    locales = RecModel.forLocales(args[++i].split(',').map { Locale.forLanguageTag(it) })
                    upright = true
                }
                "--no-probe" -> probe = false
                "--no-v6" -> v6 = false
                else -> dirs += File(args[i])
            }
            i++
        }
        out?.mkdirs()
        fun load(file: File): BufferedImage {
            val img = ImageIO.read(file)
            if (!enhance) return img
            val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also { it.createGraphics().apply { drawImage(img, 0, 0, null); dispose() } }
            val px = rgb.getRGB(0, 0, rgb.width, rgb.height, null, 0, rgb.width)
            Enhance.apply(px, rgb.width, rgb.height)
            rgb.setRGB(0, 0, rgb.width, rgb.height, px, 0, rgb.width)
            return rgb
        }
        OrtOcrModels(models).use { m ->
            val engine = OcrEngine(m, maxSide, probeScripts = probe, withV6 = v6)
            if (orientation) {
                orientations(engine, dirs, locales)
                return
            }
            println("detection max side $maxSide" + (locales?.let { ", device languages $it" } ?: "") + (if (upright) ", upright" else "") + (if (probe) "" else ", no probe") + "\n")
            for (dir in dirs) {
                println("## ${dir.name}\n")
                println("| sample | model | CER | WER | lines found | words found | words | ms |")
                println("|---|---|---|---|---|---|---|---|")
                var cerSum = 0.0
                var werSum = 0.0
                var n = 0
                var found = 0
                var total = 0
                var wordsFound = 0
                var wordsTotal = 0
                for (gt in dir.listFiles { f -> f.name.endsWith(".gt.txt") }.orEmpty().sortedBy { it.name }) {
                    val name = gt.name.removeSuffix(".gt.txt")
                    val image = listOf("jpg", "png").map { File(dir, "$name.$it") }.firstOrNull { it.isFile } ?: continue
                    val t0 = System.nanoTime()
                    var model = modelFor(name)
                    val page = if (upright) {
                        engine.recognizeUpright(AwtPageImage(load(image)), locales ?: listOf(model)).also { model = it.model }.text
                    } else {
                        engine.recognize(AwtPageImage(load(image)), model)
                    }
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val truth = normalize(gt.readText())
                    val text = normalize(page.text)
                    out?.let { File(it, "$name.txt").writeText(page.text) }
                    val cer = cer(truth, text)
                    val wer = wer(truth, text)
                    val lines = truth.lines().filter { compact(it).length >= 3 }
                    val hits = lines.count { similarity(compact(it), compact(text)) >= 0.75 }
                    cerSum += cer
                    werSum += wer
                    found += hits
                    total += lines.size
                    val words = wordsFound(truth, text)
                    wordsFound += words
                    val truthWords = truth.split(Regex("\\s+")).count { it.isNotEmpty() }
                    wordsTotal += truthWords
                    n++
                    println(String.format(Locale.ROOT, "| %s | %s | %.3f | %.3f | %d/%d | %d/%d | %d | %d |", name, model.name.lowercase(), cer, wer, hits, lines.size, words, truthWords, page.words.size, ms))
                }
                if (n > 0) println(String.format(Locale.ROOT, "| **mean** | | **%.3f** | **%.3f** | %d/%d | %d/%d | | |", cerSum / n, werSum / n, found, total, wordsFound, wordsTotal))
                println()
            }
        }
    }

    private fun orientations(engine: OcrEngine, dirs: List<File>, locales: List<RecModel>?) {
        println("| sample | 0 | 90 | 180 | 270 | ms |")
        println("|---|---|---|---|---|---|")
        val right = IntArray(4)
        var n = 0
        for (dir in dirs) {
            for (gt in dir.listFiles { f -> f.name.endsWith(".gt.txt") }.orEmpty().sortedBy { it.name }) {
                val name = gt.name.removeSuffix(".gt.txt")
                val image = listOf("jpg", "png").map { File(dir, "$name.$it") }.firstOrNull { it.isFile } ?: continue
                val page = AwtPageImage(ImageIO.read(image))
                val model = modelFor(name)
                val t0 = System.nanoTime()
                val answers = (0 until 4).map { k -> engine.orientation(page.turned(90 * k), locales ?: listOf(model)) }
                val ms = (System.nanoTime() - t0) / 1_000_000 / 4
                val marks = answers.mapIndexed { k, a ->
                    val ok = (90 * k + a) % 360 == 0
                    if (ok) right[k]++
                    if (ok) "ok" else "**$a**"
                }
                n++
                println("| $name | ${marks.joinToString(" | ")} | $ms |")
            }
        }
        println("| **right** | ${right.joinToString(" | ") { "$it/$n" }} | |")
    }

    private val TESSERACT = mapOf(
        "ara" to RecModel.ARABIC, "fas" to RecModel.ARABIC, "el" to RecModel.GREEK,
        "hin" to RecModel.DEVANAGARI, "rus" to RecModel.ESLAV, "ukr" to RecModel.ESLAV, "bul" to RecModel.CYRILLIC, "tha" to RecModel.THAI,
        "kor" to RecModel.KOREAN, "chi_sim" to RecModel.MULTI, "chi_tra" to RecModel.MULTI, "jpn" to RecModel.MULTI,
    )

    private fun modelFor(name: String): RecModel = when {
        name.startsWith("language_") -> name.removePrefix("language_").let { code ->
            TESSERACT.entries.firstOrNull { code == it.key || code.startsWith(it.key + "_") }?.value ?: RecModel.LATIN
        }
        "jpn" in name -> RecModel.MULTI
        else -> RecModel.LATIN
    }

    private fun normalize(s: String) = s.replace("\r\n", "\n").replace('\r', '\n').trim()

    private fun compact(s: String) = s.lowercase(Locale.ROOT).filterNot { it.isWhitespace() }

    fun cer(truth: String, text: String): Double {
        val a = truth.codePoints().toArray()
        val b = text.codePoints().toArray()
        if (a.isEmpty()) return if (b.isEmpty()) 0.0 else 1.0
        return levenshtein(a.size, b.size) { i, j -> a[i] == b[j] }.toDouble() / a.size
    }

    fun wordsFound(truth: String, text: String): Int {
        val have = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.groupingBy { it }.eachCount().toMutableMap()
        return truth.split(Regex("\\s+")).filter { it.isNotEmpty() }.count { w ->
            val left = have[w] ?: 0
            if (left > 0) have[w] = left - 1
            left > 0
        }
    }

    fun wer(truth: String, text: String): Double {
        val a = truth.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val b = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (a.isEmpty()) return if (b.isEmpty()) 0.0 else 1.0
        return levenshtein(a.size, b.size) { i, j -> a[i] == b[j] }.toDouble() / a.size
    }

    private inline fun levenshtein(n: Int, m: Int, same: (Int, Int) -> Boolean): Int {
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            for (j in 1..m) cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + if (same(i - 1, j - 1)) 0 else 1)
            val t = prev
            prev = cur
            cur = t
        }
        return prev[m]
    }

    fun similarity(line: String, text: String): Double {
        if (line.isEmpty()) return 1.0
        var prev = IntArray(text.length + 1)
        var cur = IntArray(text.length + 1)
        for (i in 1..line.length) {
            cur[0] = i
            for (j in 1..text.length) cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + if (line[i - 1] == text[j - 1]) 0 else 1)
            val t = prev
            prev = cur
            cur = t
        }
        return 1.0 - prev.min().toDouble() / line.length
    }
}
