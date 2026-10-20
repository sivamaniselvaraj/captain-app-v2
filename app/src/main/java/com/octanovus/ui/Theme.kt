package com.octanovus.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val Ink = Color(0xFF1F1F1F)
internal val Muted = Color(0xFF5C5C5C)
internal val Pale = Color(0xFFF2F2F2)
internal val Dot = Color(0xFFBDBDBD)
internal val OkGreen = Color(0xFF1E8449)
internal val BadRed = Color(0xFFC0392B)

internal val Canvas = Color(0xFFF7F7F5)

private val CaptainColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    error = BadRed,
)

@Composable
fun CaptainTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CaptainColors, content = content)
}
