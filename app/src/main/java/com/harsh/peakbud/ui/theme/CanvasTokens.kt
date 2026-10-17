package com.harsh.peakbud.ui.theme

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

object TrailCanvas {
    val compassBand = 40.dp
    val footerClearance = 32.dp
    val anchorGap = 20.dp
    val labelMargin = 8.dp
    val labelPadding = 12.dp
    val labelCorner = 8.dp
    val marker = 4.dp
    val markerHalo = 6.dp
    val secondaryMarker = 3.dp
    val shadowRadius = 2.dp
}

data class PeakLabelTypography(val titlePx: Float, val detailPx: Float, val axisPx: Float,
    val titleBaseline: Float, val detailBaseline: Float, val cardHeight: Float)

/** Use the same scalable roles as Compose, then measure native baselines before laying out labels. */
@Composable
fun peakLabelTypography(): PeakLabelTypography {
    val type = MaterialTheme.typography
    val density = LocalDensity.current
    return with(density) {
        val title = type.titleSmall.fontSize.toPx()
        val detail = type.bodySmall.fontSize.toPx()
        val titleLine = type.titleSmall.lineHeight.toPx()
        val detailLine = type.bodySmall.lineHeight.toPx()
        val padding = TrailSpace.xs.toPx()
        val gap = TrailSpace.xs.toPx()
        val paint = Paint().apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        paint.textSize = title
        val titleMetrics = paint.fontMetrics
        paint.textSize = detail
        val detailMetrics = paint.fontMetrics
        PeakLabelTypography(title, detail, type.labelMedium.fontSize.toPx(),
            padding + (titleLine - (titleMetrics.descent - titleMetrics.ascent)) / 2 - titleMetrics.ascent,
            padding + titleLine + gap + (detailLine - (detailMetrics.descent - detailMetrics.ascent)) / 2 - detailMetrics.ascent,
            padding * 2 + titleLine + gap + detailLine)
    }
}
