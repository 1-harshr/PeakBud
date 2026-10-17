package com.harsh.peakbud.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Native system sans; sizes are scalable text units, including labels drawn on canvases. */
private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)
val Typography = Typography(
    displayLarge = style(57,64), displayMedium = style(45,52), displaySmall = style(36,44),
    headlineLarge = style(32,40), headlineMedium = style(28,36), headlineSmall = style(24,32,FontWeight.SemiBold),
    titleLarge = style(22,28,FontWeight.Bold), titleMedium = style(16,24,FontWeight.SemiBold), titleSmall = style(14,20,FontWeight.SemiBold),
    bodyLarge = style(16,24), bodyMedium = style(14,20), bodySmall = style(12,18),
    labelLarge = style(14,20,FontWeight.SemiBold), labelMedium = style(12,16,FontWeight.Medium), labelSmall = style(12,16,FontWeight.Medium),
)
