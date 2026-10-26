// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.ocr

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.schliweb.sheet.core.ocr.OcrEngine
import de.schliweb.sheet.core.ocr.RecModel
import de.schliweb.sheet.corners.BitmapPageImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrOnDeviceTest {
    @Test
    fun readsCleanScan() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testAssets = instrumentation.context.assets
        val bitmap = testAssets.open("ocr/clean_scan.jpg").use { BitmapFactory.decodeStream(it) }
        val truth = testAssets.open("ocr/clean_scan.gt.txt").bufferedReader().readText().trim()
        val page = AssetOcrModels(instrumentation.targetContext).use { models ->
            OcrEngine(models).recognize(BitmapPageImage(bitmap), RecModel.LATIN)
        }
        val cer = levenshtein(truth, page.text.trim()).toDouble() / truth.length
        println("clean_scan: ${page.words.size} words, CER $cer")
        assertTrue("CER $cer\n${page.text}", cer < 0.03)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
