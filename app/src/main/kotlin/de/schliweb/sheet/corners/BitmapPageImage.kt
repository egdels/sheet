// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.corners

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import de.schliweb.sheet.core.corners.DocQuad
import de.schliweb.sheet.core.corners.Letterbox
import de.schliweb.sheet.core.corners.PageImage
import de.schliweb.sheet.core.corners.RotatedFrame

class BitmapPageImage(val bitmap: Bitmap) : PageImage {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun letterboxed(letterbox: Letterbox): IntArray {
        val size = DocQuad.INPUT_SIZE
        val out = draw(size, size) { c ->
            val dst = RectF(letterbox.left.toFloat(), letterbox.top.toFloat(), (letterbox.left + letterbox.width).toFloat(), (letterbox.top + letterbox.height).toFloat())
            c.drawBitmap(bitmap, null, dst, PAINT)
        }
        return IntArray(size * size).also { out.getPixels(it, 0, size, 0, 0, size, size) }
    }

    override fun scaled(width: Int, height: Int): PageImage =
        BitmapPageImage(Bitmap.createScaledBitmap(bitmap, width, height, true))

    override fun render(width: Int, height: Int, affine: DoubleArray, background: Int): PageImage {
        val m = Matrix().apply { setValues(floatArrayOf(affine[0].toFloat(), affine[1].toFloat(), affine[2].toFloat(), affine[3].toFloat(), affine[4].toFloat(), affine[5].toFloat(), 0f, 0f, 1f)) }
        return BitmapPageImage(draw(width, height, background) { c -> c.drawBitmap(bitmap, m, PAINT) })
    }

    override fun argb(): IntArray = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }

    override fun rotated(frame: RotatedFrame): PageImage {
        val source = if (width == frame.scaledWidth && height == frame.scaledHeight) bitmap else Bitmap.createScaledBitmap(bitmap, frame.scaledWidth, frame.scaledHeight, true)
        val a = frame.affine
        val m = Matrix().apply { setValues(floatArrayOf(a[0].toFloat(), a[1].toFloat(), a[2].toFloat(), a[3].toFloat(), a[4].toFloat(), a[5].toFloat(), 0f, 0f, 1f)) }
        return BitmapPageImage(draw(frame.width, frame.height) { c -> c.drawBitmap(source, m, PAINT) })
    }

    private inline fun draw(w: Int, h: Int, background: Int = Color.BLACK, paint: (Canvas) -> Unit): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(background)
        paint(canvas)
        return out
    }

    private companion object {
        val PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    }
}
