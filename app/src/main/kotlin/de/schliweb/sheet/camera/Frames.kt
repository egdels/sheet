// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min

class UprightFrame(val bitmap: Bitmap, val scale: Double)

fun ImageProxy.toUpright(maxEdge: Int = Int.MAX_VALUE): UprightFrame {
    val raw = toBitmap()
    val s = min(1.0, maxEdge / max(raw.width, raw.height).toDouble())
    val degrees = imageInfo.rotationDegrees
    if (s >= 1.0 && degrees == 0) return UprightFrame(raw, 1.0)
    val m = Matrix().apply {
        postScale(s.toFloat(), s.toFloat())
        postRotate(degrees.toFloat())
    }
    return UprightFrame(Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true), s)
}
