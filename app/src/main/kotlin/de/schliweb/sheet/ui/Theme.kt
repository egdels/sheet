// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.ui

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val PageOutline = Color(0xFF3DDC84)

private val Light = lightColorScheme(
    primary = Color(0xFF2B6481),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6E7FF),
    onPrimaryContainer = Color(0xFF001E2E),
    secondary = Color(0xFF4E616D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E5F4),
    onSecondaryContainer = Color(0xFF0A1E28),
    background = Color(0xFFF6FAFE),
    onBackground = Color(0xFF171C20),
    surface = Color(0xFFF6FAFE),
    onSurface = Color(0xFF171C20),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF41484D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F4F8),
    surfaceContainer = Color(0xFFEAEEF2),
    surfaceContainerHigh = Color(0xFFE4E9ED),
    surfaceContainerHighest = Color(0xFFDFE3E7),
    outline = Color(0xFF71787E),
    outlineVariant = Color(0xFFC1C7CE),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF97CDEE),
    onPrimary = Color(0xFF00344C),
    primaryContainer = Color(0xFF064C68),
    onPrimaryContainer = Color(0xFFC6E7FF),
    secondary = Color(0xFFB5C9D7),
    onSecondary = Color(0xFF20333E),
    secondaryContainer = Color(0xFF364955),
    onSecondaryContainer = Color(0xFFD1E5F4),
    background = Color(0xFF0F1418),
    onBackground = Color(0xFFDFE3E7),
    surface = Color(0xFF0F1418),
    onSurface = Color(0xFFDFE3E7),
    surfaceVariant = Color(0xFF41484D),
    onSurfaceVariant = Color(0xFFC1C7CE),
    surfaceContainerLowest = Color(0xFF0A0F12),
    surfaceContainerLow = Color(0xFF171C20),
    surfaceContainer = Color(0xFF1B2024),
    surfaceContainerHigh = Color(0xFF252B2F),
    surfaceContainerHighest = Color(0xFF30353A),
    outline = Color(0xFF8B9198),
    outlineVariant = Color(0xFF41484D),
)

@Composable
fun sheetColors(dark: Boolean): ColorScheme {
    val context = LocalContext.current
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
}
