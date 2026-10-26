// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import de.schliweb.sheet.ui.isLandscape
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.magnifier
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import de.schliweb.sheet.R
import de.schliweb.sheet.Screen
import de.schliweb.sheet.core.Point
import kotlin.math.hypot
import kotlin.math.min

private val OUTLINE = Color(0xFF00C853)

@Composable
fun CropScreen(
    crop: Screen.Crop,
    busy: Boolean,
    onMove: (Int, Point) -> Unit,
    onUse: () -> Unit,
    onRetake: () -> Unit,
) {
    val retake: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedButton(onClick = onRetake, enabled = !busy, modifier = modifier) {
            Text(stringResource(if (crop.editing != null) R.string.cancel else R.string.retake))
        }
    }
    val use: @Composable (Modifier) -> Unit = { modifier ->
        if (busy) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onUse, enabled = crop.quad.isPlausibleIn(crop.photo.width, crop.photo.height), modifier = modifier) {
                Text(stringResource(R.string.use_page))
            }
        }
    }
    if (isLandscape()) {
        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            CropArea(crop, onMove, Modifier.weight(1f).fillMaxHeight().padding(16.dp))
            Column(
                Modifier.fillMaxHeight().width(IntrinsicSize.Max).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                use(Modifier.fillMaxWidth())
                retake(Modifier.fillMaxWidth())
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        CropArea(crop, onMove, Modifier.weight(1f).fillMaxWidth().padding(16.dp))
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            retake(Modifier)
            use(Modifier)
        }
    }
}

@Composable
private fun CropArea(crop: Screen.Crop, onMove: (Int, Point) -> Unit, modifier: Modifier) {
    val photo = remember(crop.photo) { crop.photo.asImageBitmap() }
    val latest by rememberUpdatedState(crop)
    val grab = with(LocalDensity.current) { 48.dp.toPx() }
    var dragging by remember { mutableIntStateOf(-1) }
    var finger by remember { mutableStateOf(Offset.Unspecified) }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val s = min(boxW / crop.photo.width, boxH / crop.photo.height)
        val left = (boxW - crop.photo.width * s) / 2
        val top = (boxH - crop.photo.height * s) / 2
        fun toBox(p: Point) = Offset(left + p.x.toFloat() * s, top + p.y.toFloat() * s)
        fun toPhoto(o: Offset) = Point(((o.x - left) / s).toDouble(), ((o.y - top) / s).toDouble())

        Box(
            Modifier
                .fillMaxSize()
                .magnifier(
                    sourceCenter = { if (dragging >= 0) finger else Offset.Unspecified },
                    magnifierCenter = { if (dragging >= 0) finger - Offset(0f, 2.5f * grab) else Offset.Unspecified },
                    zoom = 2.5f,
                    size = DpSize(120.dp, 120.dp),
                    cornerRadius = 60.dp,
                )
                .pointerInput(s, left, top) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val corners = latest.quad.corners.map(::toBox)
                            val nearest = corners.indices.minBy { hypot(corners[it].x - start.x, corners[it].y - start.y) }
                            val c = corners[nearest]
                            dragging = if (hypot(c.x - start.x, c.y - start.y) <= grab) nearest else -1
                            finger = c
                        },
                        onDragEnd = { dragging = -1 },
                        onDragCancel = { dragging = -1 },
                        onDrag = { change, delta ->
                            if (dragging < 0) return@detectDragGestures
                            change.consume()
                            finger += delta
                            onMove(dragging, toPhoto(finger))
                        },
                    )
                },
        ) {
            Image(photo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val pts = crop.quad.corners.map(::toBox)
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (p in pts.drop(1)) lineTo(p.x, p.y)
                    close()
                }
                drawPath(path, OUTLINE.copy(alpha = 0.15f))
                drawPath(path, OUTLINE, style = Stroke(width = 2.dp.toPx()))
                for ((i, p) in pts.withIndex()) {
                    if (i == dragging) {
                        drawCircle(OUTLINE, radius = 2.dp.toPx(), center = p)
                    } else {
                        drawCircle(Color.White.copy(alpha = 0.6f), radius = 14.dp.toPx(), center = p)
                        drawCircle(OUTLINE, radius = 14.dp.toPx(), center = p, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
        }
    }
}
