package com.harsh.peakbud.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import com.harsh.peakbud.data.settings.ThemeMode
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Forest greens with a warm paper neutral. Every surface role is set so no Material default tint leaks in. */
internal val TrailLightColors = lightColorScheme(
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

internal val TrailDarkColors = darkColorScheme(
    primary = Color(0xFFA1D4B7), onPrimary = Color(0xFF073924),
    primaryContainer = Color(0xFF24513C), onPrimaryContainer = Color(0xFFC0F0D1),
    secondary = Color(0xFFB4CCBE), onSecondary = Color(0xFF20362B),
    secondaryContainer = Color(0xFF374D42), onSecondaryContainer = Color(0xFFD0E8D9),
    tertiary = Color(0xFFF0B38E), onTertiary = Color(0xFF4F250D),
    tertiaryContainer = Color(0xFF693B21), onTertiaryContainer = Color(0xFFFFDBC5),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101713), onBackground = Color(0xFFE1E7DF),
    surface = Color(0xFF141C17), onSurface = Color(0xFFE1E7DF),
    surfaceVariant = Color(0xFF26342B), onSurfaceVariant = Color(0xFFBECBBF),
    surfaceTint = Color(0xFFA1D4B7),
    surfaceBright = Color(0xFF354139), surfaceDim = Color(0xFF101713),
    surfaceContainerLowest = Color(0xFF0B120E), surfaceContainerLow = Color(0xFF17201A),
    surfaceContainer = Color(0xFF1C261F), surfaceContainerHigh = Color(0xFF263129),
    surfaceContainerHighest = Color(0xFF303C33),
    outline = Color(0xFF89998D), outlineVariant = Color(0xFF414F45),
    inverseSurface = Color(0xFFE1E7DF), inverseOnSurface = Color(0xFF263129), inversePrimary = Color(0xFF2B6849),
    scrim = Color.Black,
)

private val TrailShapes = Shapes(
    extraSmall = RoundedCornerShape(TrailRadius.small), small = RoundedCornerShape(TrailRadius.medium),
    medium = RoundedCornerShape(TrailRadius.card), large = RoundedCornerShape(TrailRadius.large), extraLarge = RoundedCornerShape(TrailRadius.sheet),
)

@Composable
fun PeakBudTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (themeMode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    CompositionLocalProvider(LocalTrailSceneColors provides if (dark) TrailNightScene else TrailDayScene) {
        MaterialTheme(colorScheme = if (dark) TrailDarkColors else TrailLightColors, typography = Typography, shapes = TrailShapes) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
        }
    }
}
