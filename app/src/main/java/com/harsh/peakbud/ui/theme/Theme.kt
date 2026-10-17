package com.harsh.peakbud.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TrailColors = lightColorScheme(
    primary = Color(0xFF234F43), onPrimary = Color.White,
    primaryContainer = Color(0xFFDFEBDD), onPrimaryContainer = Color(0xFF18392F),
    secondary = Color(0xFFA45D36), secondaryContainer = Color(0xFFF8E3D4),
    background = Color(0xFFF7F5EE), onBackground = Color(0xFF20332D),
    surface = Color(0xFFFFFDF7), onSurface = Color(0xFF20332D),
    surfaceVariant = Color(0xFFEAEDE3), onSurfaceVariant = Color(0xFF586960),
    outline = Color(0xFFB9C5BA),
)

@Composable
fun PeakBudTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TrailColors, typography = Typography, content = content)
}
