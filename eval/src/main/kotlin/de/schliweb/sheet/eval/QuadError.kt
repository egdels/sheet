// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.eval

import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sign

data class QuadError(val meanRel: Double, val maxRel: Double, val iou: Double, val orderShift: Int) {
    companion object {
        fun of(predicted: Quad, label: Quad, width: Int, height: Int): QuadError {
            val diag = hypot(width.toDouble(), height.toDouble())
            val p = predicted.corners
            val g = label.corners
            var bestMean = Double.POSITIVE_INFINITY
            var bestMax = Double.POSITIVE_INFINITY
            var bestShift = 0
            for (shift in 0 until 4) {
                var sum = 0.0
                var max = 0.0
                for (i in 0 until 4) {
                    val a = p[(i + shift) % 4]
                    val d = hypot(a.x - g[i].x, a.y - g[i].y)
                    sum += d
                    if (d > max) max = d
                }
                if (sum / 4 < bestMean) {
                    bestMean = sum / 4
                    bestMax = max
                    bestShift = shift
                }
            }
            return QuadError(bestMean / diag, bestMax / diag, iou(p, g), bestShift)
        }

        fun iou(a: List<Point>, b: List<Point>): Double {
            val areaA = abs(signedArea(a))
            val areaB = abs(signedArea(b))
            if (!(areaA > 0.0) || !(areaB > 0.0)) return 0.0
            val inter = abs(signedArea(clip(a, b)))
            val union = areaA + areaB - inter
            return if (union > 0.0) (inter / union).coerceIn(0.0, 1.0) else 0.0
        }

        private fun clip(subject: List<Point>, clip: List<Point>): List<Point> {
            val orientation = sign(signedArea(clip))
            var out = subject
            for (i in clip.indices) {
                if (out.isEmpty()) break
                val c0 = clip[i]
                val c1 = clip[(i + 1) % clip.size]
                val input = out
                out = buildList {
                    for (j in input.indices) {
                        val p = input[j]
                        val q = input[(j + 1) % input.size]
                        val sp = orientation * side(c0, c1, p)
                        val sq = orientation * side(c0, c1, q)
                        if (sp >= 0) add(p)
                        if ((sp >= 0) != (sq >= 0)) {
                            val t = sp / (sp - sq)
                            add(Point(p.x + t * (q.x - p.x), p.y + t * (q.y - p.y)))
                        }
                    }
                }
            }
            return out
        }

        private fun side(a: Point, b: Point, p: Point) = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

        private fun signedArea(poly: List<Point>): Double {
            var s = 0.0
            for (i in poly.indices) {
                val p = poly[i]
                val q = poly[(i + 1) % poly.size]
                s += p.x * q.y - q.x * p.y
            }
            return 0.5 * s
        }
    }
}

fun percentile(values: List<Double>, p: Double): Double {
    if (values.isEmpty()) return Double.NaN
    val sorted = values.sorted()
    val idx = ceil(p * sorted.size).toInt() - 1
    return sorted[idx.coerceIn(0, sorted.size - 1)]
}
