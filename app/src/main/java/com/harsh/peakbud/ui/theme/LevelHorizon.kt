package com.harsh.peakbud.ui.theme

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.harsh.peakbud.core.projection.ScreenLine

/** A quiet, outlined dashed reference, visible over sky, dark terrain and live camera imagery. */
internal fun DrawScope.drawLevelHorizon(line: ScreenLine, color: Color, backing: Color, label: String, textSize: Float) {
    val start = Offset(line.start.x, line.start.y); val end = Offset(line.end.x, line.end.y)
    val dash = PathEffect.dashPathEffect(floatArrayOf(TrailSpace.md.toPx(), TrailSpace.sm.toPx()))
    drawLine(backing, start, end, TrailStroke.heavy.toPx(), pathEffect = dash)
    drawLine(color.copy(alpha = .85f), start, end, TrailStroke.selected.toPx(), pathEffect = dash)
    val centre = (start + end) / 2f
    drawLine(backing, centre - Offset(0f, TrailSpace.xs.toPx()), centre + Offset(0f, TrailSpace.xs.toPx()), TrailStroke.heavy.toPx())
    drawLine(color, centre - Offset(0f, TrailSpace.xs.toPx()), centre + Offset(0f, TrailSpace.xs.toPx()), TrailStroke.thin.toPx())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize; this.color = color.toArgb()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val padding = TrailSpace.xs.toPx()
    val available = size.width - TrailSpace.lg.toPx() * 2 - padding * 2
    if (available <= 0) return
    val text = android.text.TextUtils.ellipsize(label, android.text.TextPaint(paint), available, android.text.TextUtils.TruncateAt.END).toString()
    val metrics = paint.fontMetrics
    val width = paint.measureText(text) + padding * 2
    val height = metrics.descent - metrics.ascent + padding * 2
    val left = (centre.x - width / 2).coerceIn(TrailSpace.sm.toPx(), maxOf(TrailSpace.sm.toPx(), size.width - width - TrailSpace.sm.toPx()))
    val top = centre.y - height - TrailSpace.sm.toPx()
    // Do not move the horizon to keep its caption on-screen: a clipped caption is simply omitted.
    if (top < padding || top + height > size.height - padding) return
    drawRoundRect(backing, Offset(left, top), Size(width, height), CornerRadius(TrailRadius.small.toPx()))
    drawContext.canvas.nativeCanvas.drawText(text, left + padding, top + padding - metrics.ascent, paint)
}
