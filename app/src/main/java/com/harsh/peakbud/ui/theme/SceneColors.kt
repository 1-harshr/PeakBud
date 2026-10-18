package com.harsh.peakbud.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Semantic map illustration colors; camera imagery keeps a separate high-contrast treatment. */
data class TrailSceneColors(val skyTop: Color, val skyMiddle: Color, val skyBottom: Color,
    val ridgeTop: Color, val ridgeBottom: Color, val grid: Color, val axis: Color,
    val labelSurface: Color, val labelText: Color, val labelDetail: Color, val leader: Color, val marker: Color)
internal val TrailDayScene = TrailSceneColors(Color(0xFFD9E7E1),Color(0xFFF5F0DD),Color(0xFFE9EDDD),
    Color(0xFF547E68),Color(0xFF193E32),Color(0x33516B5A),Color(0xFF405D50),
    Color(0xFFFFFDF4),Color(0xFF234F43),Color(0xFF485E50),Color(0xFF6B8171),Color(0xFFF8F7E9))
internal val TrailNightScene = TrailSceneColors(Color(0xFF10201D),Color(0xFF182A25),Color(0xFF22362C),
    Color(0xFF3F6B55),Color(0xFF0C2118),Color(0x447B9B88),Color(0xFFC2D6C7),
    Color(0xFF22382C),Color(0xFFE1F1E5),Color(0xFFBDCFBF),Color(0xFFA0BEA8),Color(0xFFE1F1E5))
val LocalTrailSceneColors = staticCompositionLocalOf { TrailDayScene }
object TrailCameraColors {
    val content = Color.White
    val scrim = Color.Black.copy(alpha = .72f)
    val labelSurface = Color(0xE6101C16)
    val warningSurface = Color(0xFF633D14)
    val steady = Color(0xFF8DE0AD)
    /** Ring on label anchors placed by camera alignment rather than the compass estimate. */
    val alignedRing = steady
    /** Height half of camera peak pins. */
    val pinHeight = Color(0xFF2E8B6A)
    val uncertain = Color(0xFFFFC078)
}

/** The sun on the drawn skyline. */
object TrailSun {
    val disc = Color(0xFFFFC94D)
    val glow = Color(0x33FFC94D)
}
