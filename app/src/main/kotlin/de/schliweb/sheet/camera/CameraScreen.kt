// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

@file:androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)

package de.schliweb.sheet.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import de.schliweb.sheet.core.document.Document
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.schliweb.sheet.R
import de.schliweb.sheet.SheetViewModel
import de.schliweb.sheet.ui.isLandscape

@Composable
fun CameraScreen(vm: SheetViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.camera_needed))
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.allow_camera)) }
        }
        return
    }
    Viewfinder(vm)
}

@Composable
private fun Viewfinder(vm: SheetViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val live by vm.live.collectAsState()
    val busy by vm.busy.collectAsState()
    val document by vm.document.collectAsState()
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val controller = remember {
        fun fourThree(preferred: android.util.Size? = null) = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .apply { if (preferred != null) setResolutionStrategy(ResolutionStrategy(preferred, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)) }
            .build()
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            previewResolutionSelector = fourThree()
            imageCaptureResolutionSelector = fourThree(android.util.Size(4032, 3024))
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            imageAnalysisResolutionSelector = fourThree(android.util.Size(1280, 960))
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
            setImageAnalysisAnalyzer(vm.analysisExecutor, vm.analyzer)
        }
    }
    val takePicture = {
        controller.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) = vm.onCaptured(image)
                override fun onError(exception: ImageCaptureException) = vm.onCaptureFailed()
            },
        )
    }
    LaunchedEffect(vm) { vm.shutter.collect { takePicture() } }
    DisposableEffect(owner) {
        controller.bindToLifecycle(owner)
        previewView.controller = controller
        onDispose { controller.unbind() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val outline = live ?: return@Canvas
            val target = previewView.outputTransform ?: return@Canvas
            val pts = outline.quad.corners.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }.toFloatArray()
            CoordinateTransform(outline.transform, target).mapPoints(pts)
            val path = Path().apply {
                moveTo(pts[0], pts[1])
                for (i in 1 until 4) lineTo(pts[2 * i], pts[2 * i + 1])
                close()
            }
            drawPath(path, Color(0x3300C853))
            drawPath(path, Color(0xFF00C853), style = Stroke(width = 3.dp.toPx()))
        }
        val landscape = isLandscape()
        val controls = if (landscape) {
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().safeDrawingPadding().padding(end = 48.dp)
        } else {
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(bottom = 32.dp)
        }
        Box(controls) {
            if (document.pages.isNotEmpty()) {
                val place = if (landscape) Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp) else Modifier.align(Alignment.CenterStart).padding(start = 24.dp)
                PagesButton(vm, document, place)
            }
            Box(Modifier.align(Alignment.Center)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(72.dp))
                } else {
                    val label = stringResource(R.string.take_photo)
                    FilledIconButton(
                        onClick = takePicture,
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp).semantics { contentDescription = label },
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun PagesButton(vm: SheetViewModel, document: Document, modifier: Modifier) {
    val last = document.pages.last()
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(last) { thumb = vm.thumbnail(last)?.asImageBitmap() }
    val label = pluralStringResource(R.plurals.pages, document.pages.size, document.pages.size)
    Box(
        modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .clickable(onClickLabel = label) { vm.showPages() }
            .semantics { contentDescription = label },
    ) {
        thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Badge(Modifier.align(Alignment.TopEnd).padding(2.dp)) { Text(document.pages.size.toString()) }
    }
}
