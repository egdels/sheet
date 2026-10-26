// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.ocr

import kotlin.math.exp

object CtcDecoder {
    data class Token(val text: String, val confidence: Float, val frameStart: Int, val frameEnd: Int)

    data class Decoded(
        val text: String,
        val confidence: Float,
        val tokens: List<Token>,
        val frameCount: Int,
        val charFrames: IntArray,
    )

    fun decode(probs: FloatArray, frames: Int, vocab: Vocab): Decoded {
        val classes = vocab.size
        require(probs.size == frames * classes) { "output ${probs.size} != $frames x $classes" }
        val text = StringBuilder()
        val charFrames = ArrayList<Int>()
        val tokens = mutableListOf<Token>()
        var confSum = 0.0
        var confCount = 0
        var prev = -1
        val word = StringBuilder()
        var wordConf = 0.0
        var wordCount = 0
        var wordStart = -1
        var wordEnd = -1
        fun flush() {
            if (word.isNotEmpty() && wordCount > 0) tokens += Token(word.toString(), (wordConf / wordCount).toFloat(), wordStart, wordEnd)
            word.setLength(0)
            wordConf = 0.0
            wordCount = 0
            wordStart = -1
        }
        for (t in 0 until frames) {
            val row = t * classes
            var best = 0
            var bestValue = probs[row]
            for (c in 1 until classes) if (probs[row + c] > bestValue) {
                bestValue = probs[row + c]
                best = c
            }
            if (best != 0 && best != prev) {
                val p = probability(probs, row, classes, bestValue)
                confSum += p
                confCount++
                val s = vocab[best]
                text.append(s)
                repeat(s.length) { charFrames += t }
                if (s.isNotEmpty() && s.isBlank()) {
                    flush()
                } else if (s.isNotEmpty()) {
                    if (wordStart < 0) wordStart = t
                    wordEnd = t
                    word.append(s)
                    wordConf += p
                    wordCount++
                }
            }
            prev = best
        }
        flush()
        return Decoded(text.toString(), if (confCount == 0) 0f else (confSum / confCount).toFloat(), tokens, frames, charFrames.toIntArray())
    }

    private fun probability(probs: FloatArray, row: Int, classes: Int, max: Float): Double {
        if (max in 0f..1.0001f) {
            var sum = 0.0
            for (c in 0 until classes) sum += probs[row + c]
            if (sum > 0.99 && sum < 1.01) return max.toDouble()
        }
        var sumExp = 0.0
        for (c in 0 until classes) sumExp += exp((probs[row + c] - max).toDouble())
        return 1.0 / sumExp
    }
}

class Vocab(private val entries: List<String>) {
    val size: Int get() = entries.size

    operator fun get(i: Int): String = entries[i]

    companion object {
        fun fromLines(lines: Sequence<String>): Vocab = Vocab(listOf("") + lines.filter { it.isNotEmpty() })
    }
}
