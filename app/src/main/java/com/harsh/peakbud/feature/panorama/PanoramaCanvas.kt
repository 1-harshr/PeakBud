package com.harsh.peakbud.feature.panorama

import com.harsh.peakbud.ui.theme.*
import com.harsh.peakbud.ui.text.*
import com.harsh.peakbud.R
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.projection.PanoramaProjector
import com.harsh.peakbud.core.projection.inLabelPriority
import kotlin.math.*

@Composable
fun PanoramaCanvas(state: PanoramaState, modifier: Modifier, onPan: (Double) -> Unit, onPeak: (PeakView) -> Unit, onZoom: (Float) -> Unit = {},
                   compassStartInset: androidx.compose.ui.unit.Dp = TrailSpace.none) {
    val strings = rememberTrailStrings()
    val palette = LocalTrailSceneColors.current
    val type = peakLabelTypography()
    val hits = remember { mutableListOf<Pair<RectF, PeakView>>() }
    val currentState by rememberUpdatedState(state)
    val pan by rememberUpdatedState(onPan)
    val select by rememberUpdatedState(onPeak)
    val zoom by rememberUpdatedState(onZoom)
    // The vertical axis follows the terrain in view: the lowest and highest skyline points (and labelled peaks) are
    // fitted to the frame, easing as you pan so the view never jumps.
    val target = remember(state.scene, state.heading, state.fov, state.preferences.showUncertain) {
        PanoramaProjector.window(state.scene?.horizon.orEmpty(),
            state.scene?.peaks.orEmpty().filter { it.visibility == Visibility.TERRAIN_VISIBLE || state.preferences.showUncertain && it.visibility == Visibility.UNCERTAIN }
                .mapNotNull { p -> p.elevationDeg?.let { p.bearingDeg to it } }, state.heading, state.fov)
    }
    val windowLow by androidx.compose.animation.core.animateFloatAsState((target?.lowDeg ?: 0.0).toFloat(), androidx.compose.animation.core.tween(450), label = "low")
    val windowHigh by androidx.compose.animation.core.animateFloatAsState((target?.highDeg ?: 10.0).toFloat(), androidx.compose.animation.core.tween(450), label = "high")
    Canvas(modifier.clipToBounds()
        .semantics { contentDescription = strings(R.string.mountain_panorama_drag_horizontally_to_look_around_peak_details_are_also_ava) }
        // One finger turns the view; two fingers pinch to zoom.
        .pointerInput(Unit) { detectTransformGestures { _, panBy, zoomBy, _ ->
            if (panBy.x != 0f && size.width > 0) pan(-panBy.x / size.width * currentState.fov)
            if (zoomBy != 1f) zoom(zoomBy)
        } }
        .pointerInput(Unit) { detectTapGestures { point -> hits.lastOrNull { it.first.contains(point.x, point.y) }?.let { select(it.second) } } }
    ) {
        val w = size.width
        val h = size.height
        hits.clear()
        if (w <= 0 || h <= 0) return@Canvas
        drawRect(Brush.verticalGradient(listOf(palette.skyTop, palette.skyMiddle, palette.skyBottom)))
        // Without a landscape the compass strip, angle lines and range caption mean nothing; show only the sky.
        if (state.scene == null && !state.calculating) return@Canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = type.axisPx; color = palette.axis.toArgb() }
        val heading = state.heading
        // Highest point in view sits at 45% of the height (room above for pins), lowest at 75% (ground below).
        val window = PanoramaProjector.VerticalWindow(windowLow.toDouble(), windowHigh.toDouble())
        val topY = h * .45f; val bottomY = h * .75f
        val pxPerDeg = PanoramaProjector.fittedPxPerDeg(window, topY, bottomY, state.fov, w)
        val verticalScale = pxPerDeg / (w / state.fov)
        val centreDeg = (window.lowDeg + window.highDeg) / 2
        fun project(b: Double, e: Double) = PanoramaProjector.project(b, 0.0, heading, 0.0, w, h, state.fov).let {
            com.harsh.peakbud.core.projection.ScreenPoint(it.x, ((topY + bottomY) / 2 - (e - centreDeg) * pxPerDeg).toFloat())
        }
        val axisMetrics = paint.fontMetrics
        val axisHeight = axisMetrics.descent - axisMetrics.ascent
        val axisBaseline = TrailSpace.md.toPx() + TrailSpace.xs.toPx() - axisMetrics.ascent
        val compassBand = max(TrailCanvas.compassBand.toPx(), axisBaseline + axisMetrics.descent + TrailSpace.xs.toPx())
        val footerHeight = axisHeight + TrailSpace.sm.toPx() * 2
        val footerClearance = max(TrailCanvas.footerClearance.toPx(), footerHeight + TrailSpace.md.toPx())
        val compassLabels = mutableListOf<RectF>()
        val elevationLabels = mutableListOf<Pair<Float, String>>()
        // Finer angle lines when heights are stretched, so they keep marking true angles at a readable spacing.
        // Pick the smallest of 2/5/10/20° steps that keeps lines at least ~56 dp apart on screen.
        val gridPxPerDeg = abs(project(heading, 1.0).y - project(heading, 0.0).y)
        val stepDeg = listOf(.5, 1.0, 2.0, 5.0, 10.0, 20.0).firstOrNull { it * gridPxPerDeg >= TrailSpace.section.toPx() + TrailSpace.sm.toPx() } ?: 20.0
        var angleIndex = (-20 / stepDeg).toInt()
        while (angleIndex * stepDeg <= 40) {
            val angle = angleIndex * stepDeg; angleIndex++
            val y = project(heading, angle).y
            if (y in (compassBand + (TrailSpace.md).toPx())..(h - footerClearance)) {
                drawLine(palette.grid, Offset(0f, y), Offset(w, y), 1f)
                elevationLabels += y to strings(R.string.angle_degrees , if (stepDeg < 1) String.format(java.util.Locale.US, "%.1f", angle) else angle.toInt().toString())
            }
        }
        for (b in 0 until 360 step 15) {
            val x = project(b.toDouble(), 0.0).x
            if (x !in compassStartInset.toPx()..w) continue
            drawLine(palette.axis, Offset(x, 0f), Offset(x, (TrailSpace.md).toPx()), 1f)
            val label = when (b) { 0 -> strings(R.string.n); 90 -> strings(R.string.e); 180 -> strings(R.string.s); 270 -> strings(R.string.w); else -> strings(R.string.angle_degrees , b) }
            val half = paint.measureText(label) / 2
            if (x - half < compassStartInset.toPx() + TrailCanvas.marker.toPx() || x + half > w - TrailCanvas.marker.toPx()) continue
            val bounds = RectF(x - half, axisBaseline + axisMetrics.ascent, x + half, axisBaseline + axisMetrics.descent)
            if (compassLabels.any { RectF.intersects(it, bounds) }) continue
            compassLabels += RectF(bounds).apply { inset(-TrailSpace.xs.toPx(), 0f) }
            drawContext.canvas.nativeCanvas.drawText(label, x - half, axisBaseline, paint)
        }
        val segments = PanoramaProjector.horizonSegments(state.scene?.horizon.orEmpty(), heading, state.fov)
            .map { segment -> segment.map { sample -> sample to project(sample.bearingDeg, sample.elevationDeg!!) } }
        for (segment in segments) {
            if (segment.size < 2) continue
            val path = Path()
            path.moveTo(segment.first().second.x, segment.first().second.y)
            segment.drop(1).forEach { (_, point) -> path.lineTo(point.x, point.y) }
            path.lineTo(segment.last().second.x, h); path.lineTo(segment.first().second.x, h); path.close()
            drawPath(path, Brush.verticalGradient(listOf(palette.ridgeTop, palette.ridgeBottom), startY = h * .3f, endY = h))
        }
        if (w <= TrailCanvas.labelPadding.toPx() * 2) return@Canvas
        drawLine(palette.axis, Offset(w / 2, (TrailSpace.xxl).toPx()), Offset(w / 2, (TrailCanvas.compassBand + TrailSpace.sm).toPx()), (TrailSpace.xxs).toPx())
        val occupied = mutableListOf<RectF>()
        val candidates = state.scene?.peaks.orEmpty().filter {
            it.visibility == Visibility.TERRAIN_VISIBLE || state.preferences.showUncertain && it.visibility == Visibility.UNCERTAIN
        }.inLabelPriority().mapNotNull { peak -> peak.elevationDeg?.let { e -> project(peak.bearingDeg, e).let { p ->
            PinCandidate(peak, p.x, p.y, peak.peak.name + if (peak.visibility == Visibility.UNCERTAIN) " ?" else "",
                peak.peak.publishedHeightM?.let { height(it, state, strings) } ?: peak.geometryHeightM?.let { strings(R.string.approximate_height, height(it, state, strings)) })
        } } }.filter { it.x in TrailCanvas.labelMargin.toPx()..(w - TrailCanvas.labelMargin.toPx()) && it.y in compassBand..(h - footerClearance) }
        // Same still pins as the camera: dot on the summit, vertical stick, tilted name/height pill.
        val style = PinStyle(stick = palette.leader, dot = palette.marker, nameSurface = palette.labelSurface, nameText = palette.labelText,
            heightSurface = TrailCameraColors.pinHeight, heightText = Color.White)
        val textSize = type.detailPx * 1.05f; val pinTop = compassBand + TrailSpace.xs.toPx()
        for (pin in PeakPins.place(this, candidates, textSize, pinTop)) {
            val hit = PeakPins.draw(this, pin.x, pin.y, pin.name, pin.height, style, textSize, pinTop)
            occupied += hit
            hits += hit to pin.item
        }
        paint.textSize = type.axisPx
        paint.color = palette.axis.toArgb()
        // Labels sit against the sky only: skip any covered by a card or by the terrain at the left edge.
        val edgeX = TrailSpace.md.toPx()
        val skylineAtEdge = segments.firstOrNull { it.first().second.x <= edgeX && it.last().second.x >= edgeX }
            ?.minByOrNull { abs(it.second.x - edgeX) }?.second?.y ?: h
        for ((y, text) in elevationLabels) {
            val baseline = y - TrailSpace.xs.toPx()
            val box = RectF(TrailSpace.xs.toPx(), baseline + axisMetrics.ascent, TrailSpace.md.toPx() + paint.measureText(text), baseline + axisMetrics.descent)
            if (y > skylineAtEdge - (TrailSpace.xxs).toPx()) continue
            if (occupied.none { RectF.intersects(it, box) }) drawContext.canvas.nativeCanvas.drawText(text, (TrailSpace.md).toPx(), y - (TrailCanvas.marker).toPx(), paint)
        }
        paint.textSize = type.axisPx
        paint.color = palette.labelText.toArgb()
        val baseCaption = if (state.isDemo) strings(R.string.demo_landscape_drag_to_look_around) else strings(R.string.showing_peaks_up_to_km_drag_to_look_around , distance(state.scene?.radiusM ?: state.activePack?.radiusM ?: 0.0, state, strings))
        val bottom = if (verticalScale > 1.2) strings(R.string.heights_stretched_caption, baseCaption, String.format(java.util.Locale.US, "%.1f", verticalScale)) else baseCaption
        val footer = android.text.TextUtils.ellipsize(bottom, android.text.TextPaint(paint), w - TrailCanvas.labelPadding.toPx() * 2, android.text.TextUtils.TruncateAt.END).toString()
        val footerTop = h - footerHeight - TrailSpace.xs.toPx()
        drawRoundRect(palette.labelSurface, Offset(TrailSpace.sm.toPx(), footerTop),
            androidx.compose.ui.geometry.Size(w - TrailSpace.lg.toPx(), footerHeight), androidx.compose.ui.geometry.CornerRadius(TrailRadius.small.toPx()))
        drawContext.canvas.nativeCanvas.drawText(footer, TrailSpace.lg.toPx(), footerTop + TrailSpace.sm.toPx() - axisMetrics.ascent, paint)
    }
}
