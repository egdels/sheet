// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.eval

import de.schliweb.sheet.core.PageSize
import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import de.schliweb.sheet.core.corners.CornerDetector
import de.schliweb.sheet.core.corners.EdgeSnap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.exitProcess

private const val USAGE = """usage: sheet-eval [--model <file.ort>] [--samples <out.tsv>] <dataset>...
       sheet-eval ocr [--max-side <px>] [--models <dir>] [--text <out dir>] <dir with *.gt.txt>...

A dataset is a directory with images/ and labels/*.json (fields image,
width, height, corners_px as TL, TR, BR, BL in stored-pixel coordinates,
no EXIF rotation). Prints one table per dataset: "plain" is one model pass
(the live preview), "still" adds the rotation passes (a taken photo),
"snap" moves that outline onto the paper's edges (the crop screen).
Errors are relative to the image diagonal."""

private const val GOOD_MAX_REL = 0.01
private const val MATCH_IOU = 0.90

private class Stage(val name: String) {
    val errors = mutableListOf<QuadError>()
    var fails = 0
    var millis = 0L

    fun row(): String {
        val mean = errors.map { it.meanRel }
        val maxes = errors.map { it.maxRel }
        fun pct(v: Double) = if (v.isNaN()) "-" else String.format(Locale.ROOT, "%.2f%%", v * 100)
        return String.format(
            Locale.ROOT,
            "| %s | %d | %d | %s | %s | %s | %s | %.3f | %d | %d | %d | %.0f |",
            name,
            errors.size,
            fails,
            pct(mean.average()),
            pct(percentile(mean, 0.5)),
            pct(percentile(maxes, 0.9)),
            pct(maxes.maxOrNull() ?: Double.NaN),
            errors.map { it.iou }.average(),
            errors.count { it.maxRel <= GOOD_MAX_REL },
            errors.count { it.iou >= MATCH_IOU },
            errors.count { it.orderShift != 0 },
            millis.toDouble() / max(1, errors.size + fails),
        )
    }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "ocr") {
        OcrEval.run(args.drop(1))
        return
    }
    var model = File("app/src/main/assets/docquad/docquadnet256.ort")
    var samplesOut: File? = null
    val datasets = mutableListOf<File>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--model" -> model = File(args[++i])
            "--samples" -> samplesOut = File(args[++i])
            "-h", "--help" -> {
                println(USAGE)
                return
            }
            else -> datasets += File(args[i])
        }
        i++
    }
    if (datasets.isEmpty()) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    val samples = StringBuilder("dataset\timage\tstage\tmean_rel\tmax_rel\tiou\tconfidence\tangle\tquad\n")
    OrtDocQuadModel(model).use { ort ->
        val detector = CornerDetector(ort)
        println("model: ${model.path}\n")
        for (dataset in datasets) evaluate(dataset, detector, samples)
    }
    samplesOut?.writeText(samples.toString())
}

private fun evaluate(dataset: File, detector: CornerDetector, samples: StringBuilder) {
    val plain = Stage("plain")
    val still = Stage("still")
    val snap = Stage("snap")
    var skipped = 0
    var shapeTruth = 0
    var shapeSnap = 0
    val labels = File(dataset, "labels").listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
    for (labelFile in labels) {
        val label = Json.parseToJsonElement(labelFile.readText()).jsonObject
        val name = label["image"]!!.jsonPrimitive.content
        val image = ImageIO.read(File(dataset, "images/$name"))
        val c = label["corners_px"]!!.jsonArray.map { p -> p.jsonArray.let { Point(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) } }
        val truth = Quad(c[0], c[1], c[2], c[3])
        val w = image?.width ?: 0
        val h = image?.height ?: 0
        val sizeMatches = label["width"] == null || (label["width"]!!.jsonPrimitive.int == w && label["height"]!!.jsonPrimitive.int == h)
        val tol = 0.02 * max(w, h)
        if (image == null || !sizeMatches || truth.corners.any { it.x < -tol || it.x > w + tol || it.y < -tol || it.y > h + tol }) {
            System.err.println("skip $name: unreadable, size mismatch or label outside the image")
            skipped++
            continue
        }
        val page = AwtPageImage(image)

        val s = min(1.0, CornerDetector.DETECTION_MAX_EDGE / max(w, h).toDouble())
        val work = if (s < 1.0) page.scaled((w * s).roundToInt(), (h * s).roundToInt()) else page
        var t0 = System.nanoTime()
        val d = detector.detectFrame(work)
        plain.millis += (System.nanoTime() - t0) / 1_000_000
        val plainQuad = d.quad.takeIf { it.isPlausibleIn(work.width, work.height) }?.map { Point(it.x / s, it.y / s) }
        record(plain, samples, dataset.name, name, plainQuad, d.confidence, 0, truth, w, h)

        t0 = System.nanoTime()
        val outline = detector.detectStill(page)
        still.millis += (System.nanoTime() - t0) / 1_000_000
        record(still, samples, dataset.name, name, outline?.quad, outline?.confidence ?: Double.NaN, outline?.angle ?: 0, truth, w, h)

        t0 = System.nanoTime()
        val snapped = outline?.let { EdgeSnap.refine(page, it.quad.clampedTo(w, h)) }
        snap.millis += (System.nanoTime() - t0) / 1_000_000
        record(snap, samples, dataset.name, name, snapped, outline?.confidence ?: Double.NaN, outline?.angle ?: 0, truth, w, h)
        if (isPaperShape(truth, w, h)) shapeTruth++
        if (snapped != null && isPaperShape(snapped, w, h)) shapeSnap++
    }
    println("## ${dataset.name}\n")
    println("samples=${labels.size} skipped=$skipped\n")
    println("| stage | n | fail | mean err | median err | p90 max err | worst max err | mean IoU | good (max<=1%) | match (IoU>=0.90) | order shifted | ms/image |")
    println("|---|---|---|---|---|---|---|---|---|---|---|---|")
    println(plain.row())
    println(still.row())
    println(snap.row())
    println()
    println("page shape within 0.03 of A4 or Letter: labels $shapeTruth/${labels.size - skipped}, snap $shapeSnap/${labels.size - skipped}")
    println()
}

private fun isPaperShape(quad: Quad, w: Int, h: Int): Boolean {
    val (pw, ph) = PageSize.of(quad, w, h)
    val r = min(pw, ph).toDouble() / max(pw, ph)
    return min(kotlin.math.abs(r - 210.0 / 297.0), kotlin.math.abs(r - 8.5 / 11.0)) < 0.03
}

private fun record(
    stage: Stage,
    samples: StringBuilder,
    dataset: String,
    image: String,
    quad: Quad?,
    confidence: Double,
    angle: Int,
    truth: Quad,
    w: Int,
    h: Int,
) {
    if (quad == null) {
        stage.fails++
        samples.append("$dataset\t$image\t${stage.name}\t\t\t\t\t\t\n")
        return
    }
    val e = QuadError.of(quad, truth, w, h)
    stage.errors += e
    val corners = quad.corners.joinToString(" ") { String.format(Locale.ROOT, "%.1f %.1f", it.x, it.y) }
    samples.append(String.format(Locale.ROOT, "%s\t%s\t%s\t%.5f\t%.5f\t%.4f\t%.4f\t%d\t%s\n", dataset, image, stage.name, e.meanRel, e.maxRel, e.iou, confidence, angle, corners))
}
