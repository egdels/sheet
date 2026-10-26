// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.crop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import de.schliweb.sheet.core.PageSize
import de.schliweb.sheet.core.Quad

fun warp(photo: Bitmap, quad: Quad): Bitmap {
    val (w, h) = PageSize.of(quad, photo.width, photo.height)
    val src = quad.corners.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }.toFloatArray()
    val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
    val m = Matrix()
    check(m.setPolyToPoly(src, 0, dst, 0, 4)) { "degenerate outline $quad" }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(photo, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    return out
}
