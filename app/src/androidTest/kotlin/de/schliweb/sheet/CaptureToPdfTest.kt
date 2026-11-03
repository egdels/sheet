// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.media.Image
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.schliweb.sheet.core.ocr.OcrEngine
import de.schliweb.sheet.core.ocr.RecModel
import de.schliweb.sheet.corners.BitmapPageImage
import de.schliweb.sheet.ocr.AssetOcrModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class CaptureToPdfTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Test
    fun everyWayThePhoneIsHeldGivesAnUprightSearchablePage() {
        val testAssets = instrumentation.context.assets
        val scan = testAssets.open("ocr/clean_scan.jpg").use { BitmapFactory.decodeStream(it) }
        val truth = testAssets.open("ocr/clean_scan.gt.txt").bufferedReader().readText()
        val photo = onTable(scan)
        File(app.filesDir, "document").deleteRecursively()
        lateinit var vm: SheetViewModel
        instrumentation.runOnMainSync { vm = SheetViewModel(app, SavedStateHandle()) }
        AssetOcrModels(app, arena = false).use { models ->
            val check = OcrEngine(models, readers = 4)
            for (quarters in 0..3) {
                val held = "page turned ${90 * quarters}"
                val scene = turn(photo, quarters)
                val rotation = listOf(90, 0, 270, 180)[quarters]
                val image = StillImage(turn(scene, -rotation / 90), rotation)
                instrumentation.runOnMainSync { vm.onCaptured(image) }
                val crop = await(60_000, "$held: crop screen") { vm.screen.value as? Screen.Crop }
                assertTrue("$held: page not found", crop.found)
                instrumentation.runOnMainSync { vm.usePage() }
                await(60_000, "$held: page added") { vm.document.value.pages.singleOrNull() }
                await(600_000, "$held: text read") { if (vm.reading.value.isEmpty() && !vm.busy.value) true else null }
                val page = vm.document.value.pages.single()
                assertEquals(held, (4 - quarters) % 4, page.turns)
                assertTrue("$held: text not read", page.id !in vm.unread.value)
                checkPdf(vm, check, truth, held, expectedTurn = 0)
                instrumentation.runOnMainSync { vm.turnPage(page.id) }
                await(30_000, "$held: turned") { if (!vm.busy.value && vm.document.value.pages.single().turns == (page.turns + 1) % 4) true else null }
                checkPdf(vm, check, truth, "$held, turned by hand", expectedTurn = 270)
                instrumentation.runOnMainSync { vm.discardDocument() }
                await(30_000, "$held: discarded") { if (vm.document.value.isEmpty) true else null }
            }
            check.close()
        }
    }

    private fun checkPdf(vm: SheetViewModel, check: OcrEngine, truth: String, what: String, expectedTurn: Int) {
        instrumentation.runOnMainSync { vm.shareShown() }
        instrumentation.runOnMainSync { vm.sharePdf() }
        await(120_000, "$what: shared") { vm.share.value }
        instrumentation.runOnMainSync { vm.shareShown() }
        val pdf = File(app.cacheDir, "share").listFiles()!!.single()
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val read = check.recognizeUpright(BitmapPageImage(bitmap), listOf(RecModel.LATIN))
                    println("$what: the PDF page wants turning by ${read.degrees}")
                    assertEquals("$what: orientation of the PDF page", expectedTurn, read.degrees)
                    if (Build.VERSION.SDK_INT >= 35) {
                        val layer = page.textContents.joinToString(" ") { it.text }
                        val cer = cer(truth, layer)
                        println("$what: text layer CER $cer")
                        assertTrue("$what: text layer CER $cer\n$layer", cer < 0.1)
                    }
                }
            }
        }
    }

    private fun cer(truth: String, read: String): Double {
        val a = truth.filterNot { it.isWhitespace() }
        val b = read.filterNot { it.isWhitespace() }
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length].toDouble() / a.length
    }

    private fun onTable(page: Bitmap): Bitmap {
        val photo = Bitmap.createBitmap(1512, 2016, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(photo)
        canvas.drawColor(Color.rgb(104, 88, 72))
        val from = floatArrayOf(0f, 0f, page.width.toFloat(), 0f, page.width.toFloat(), page.height.toFloat(), 0f, page.height.toFloat())
        val to = floatArrayOf(260f, 250f, 1250f, 230f, 1330f, 1790f, 170f, 1760f)
        val m = Matrix().apply { setPolyToPoly(from, 0, to, 0, 4) }
        canvas.drawBitmap(page, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return photo
    }

    private fun turn(bitmap: Bitmap, quarters: Int): Bitmap {
        if (quarters.mod(4) == 0) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f * quarters.mod(4)) }, true)
    }

    private fun <T : Any> await(timeoutMs: Long, what: String, value: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            value()?.let { return it }
            Thread.sleep(100)
        }
        fail("timed out: $what")
        error("unreachable")
    }

    private class StillImage(private val bitmap: Bitmap, private val rotation: Int) : ImageProxy {
        private val pixels = ByteBuffer.allocateDirect(bitmap.byteCount).also {
            bitmap.copyPixelsToBuffer(it)
            it.rewind()
        }
        private var crop = Rect(0, 0, bitmap.width, bitmap.height)

        override fun close() {}

        override fun getCropRect(): Rect = crop

        override fun setCropRect(rect: Rect?) {
            crop = rect ?: Rect(0, 0, bitmap.width, bitmap.height)
        }

        override fun getFormat(): Int = PixelFormat.RGBA_8888

        override fun getHeight(): Int = bitmap.height

        override fun getWidth(): Int = bitmap.width

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(
            object : ImageProxy.PlaneProxy {
                override fun getRowStride(): Int = bitmap.width * 4

                override fun getPixelStride(): Int = 4

                override fun getBuffer(): ByteBuffer = pixels
            },
        )

        override fun getImageInfo(): ImageInfo = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()

            override fun getTimestamp(): Long = 0

            override fun getRotationDegrees(): Int = rotation

            override fun populateExifData(exifBuilder: ExifData.Builder) {}
        }

        @ExperimentalGetImage
        override fun getImage(): Image? = null
    }
}
