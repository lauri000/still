package com.stillreader.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Paper = Color(0xFFF6F5EF)
val Surface = Color(0xFFFDFAF8)
val Forest = Color(0xFF304D3B)
val Ink = Color(0xFF29332B)
val Muted = Color(0xFF70786B)
val Line = Color(0xFFD6D9CC)
val FocusLetter = Color(0xFFB32E24)

@Composable
fun StillTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Forest, onPrimary = Color.White, primaryContainer = Line, onPrimaryContainer = Forest,
        background = Paper, onBackground = Ink,
        surface = Surface, onSurface = Ink, onSurfaceVariant = Muted,
        outline = Line, secondary = Forest, secondaryContainer = Line, onSecondaryContainer = Forest,
        surfaceContainer = Paper,
    ), content = content)
}
