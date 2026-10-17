package com.harsh.peakbud.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Forest greens with a warm paper neutral. Every surface role is set so no Material default tint leaks in. */
private val TrailColors = lightColorScheme(
    primary = Color(0xFF1F4D3A), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E8DC), onPrimaryContainer = Color(0xFF0F2E22),
    secondary = Color(0xFF3F6656), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEAE2), onSecondaryContainer = Color(0xFF16352A),
    tertiary = Color(0xFFA45D36), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF6E0D2), onTertiaryContainer = Color(0xFF3A1A08),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFF9DEDA), onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF6F4EE), onBackground = Color(0xFF1B2B25),
    surface = Color(0xFFFFFEFA), onSurface = Color(0xFF1B2B25),
    surfaceVariant = Color(0xFFE6EBE4), onSurfaceVariant = Color(0xFF55665E),
    surfaceTint = Color(0xFF1F4D3A),
    surfaceBright = Color(0xFFFFFEFA), surfaceDim = Color(0xFFE2E1DB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFAF5),
    surfaceContainer = Color(0xFFF2F2EC),
    surfaceContainerHigh = Color(0xFFECEDE6),
    surfaceContainerHighest = Color(0xFFE6E8E1),
    outline = Color(0xFFADBAB2), outlineVariant = Color(0xFFD7DED8),
    inverseSurface = Color(0xFF2C3A34), inverseOnSurface = Color(0xFFEFF2EC), inversePrimary = Color(0xFF9FD0B5),
    scrim = Color(0xFF000000),
)

private val TrailShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun PeakBudTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TrailColors, typography = Typography, shapes = TrailShapes, content = content)
}
