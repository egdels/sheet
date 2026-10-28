// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import android.content.Intent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.view.WindowCompat
import androidx.compose.ui.res.stringResource
import de.schliweb.sheet.R
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import de.schliweb.sheet.Screen
import de.schliweb.sheet.SheetViewModel
import de.schliweb.sheet.camera.CameraScreen
import de.schliweb.sheet.crop.CropScreen

@Composable
fun isLandscape(): Boolean = LocalWindowInfo.current.containerSize.let { it.width > it.height }

@Composable
fun SheetScreen(vm: SheetViewModel = viewModel()) {
    val screen by vm.screen.collectAsState()
    val busy by vm.busy.collectAsState()
    val saved by vm.saved.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val savedText = saved?.let { s ->
        if (s.uri != null) stringResource(R.string.saved_as, s.name, s.folder) else stringResource(R.string.save_failed, s.folder)
    }
    val openLabel = stringResource(R.string.open)
    LaunchedEffect(saved) {
        val s = saved ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(savedText ?: "", actionLabel = if (s.uri != null) openLabel else null, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed && s.uri != null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(view) }
        }
        vm.savedShown()
    }
    val dark = isSystemInDarkTheme()
    val darkIcons = !dark && screen == Screen.Pages
    val view = LocalView.current
    LaunchedEffect(darkIcons) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
    MaterialTheme(colorScheme = sheetColors(dark)) {
        Box(Modifier.fillMaxSize()) {
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                when (val s = screen) {
                    Screen.Camera -> CameraScreen(vm)
                    is Screen.Crop -> {
                        BackHandler { vm.cancelCrop() }
                        MaterialTheme(colorScheme = sheetColors(dark = true)) {
                            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                                CropScreen(s, busy, onMove = vm::moveCorner, onUse = vm::usePage, onRetake = vm::cancelCrop)
                            }
                        }
                    }
                    Screen.Pages -> {
                        BackHandler { if (!busy) vm.backToCamera() }
                        Surface(Modifier.fillMaxSize()) { PagesScreen(vm) }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).safeDrawingPadding())
        }
    }
}
