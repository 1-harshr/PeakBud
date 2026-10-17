package com.harsh.peakbud.feature.panorama

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.projection.PanoramaProjector
import kotlin.math.*

@Composable
fun PanoramaCanvas(state: PanoramaState, modifier: Modifier, onPan: (Double) -> Unit, onPeak: (PeakView) -> Unit) {
    val hits = remember { mutableListOf<Pair<RectF, PeakView>>() }
    val currentState by rememberUpdatedState(state)
    val pan by rememberUpdatedState(onPan)
    val select by rememberUpdatedState(onPeak)
    Canvas(modifier
        .semantics { contentDescription = "Mountain panorama. Drag horizontally to look around. Peak details are also available in the Peaks tab." }
        .pointerInput(Unit) { detectDragGestures { change, amount -> change.consume(); pan(-amount.x / size.width * currentState.fov) } }
        .pointerInput(Unit) { detectTapGestures { point -> hits.lastOrNull { it.first.contains(point.x, point.y) }?.let { select(it.second) } } }
    ) {
        val w = size.width
        val h = size.height
        hits.clear()
        drawRect(Brush.verticalGradient(listOf(Color(0xFFD9E7E1), Color(0xFFF5F0DD), Color(0xFFE9EDDD))))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 11 * density; color = 0xFF526A60.toInt() }
        val heading = state.heading
        fun project(b: Double, e: Double) = PanoramaProjector.project(b, e, heading, state.pitch, w, h, state.fov)
        for (angle in -20..40 step 10) {
            val y = project(heading, angle.toDouble()).y
            if (y in 50f..h) {
                drawLine(Color(0x18516B5A), Offset(0f, y), Offset(w, y), 1f)
                drawContext.canvas.nativeCanvas.drawText("$angle°", 10 * density, y - 4 * density, paint)
            }
        }
        for (b in 0 until 360 step 15) {
            val x = project(b.toDouble(), 0.0).x
            if (x !in 0f..w) continue
            drawLine(Color(0xFF8AA194), Offset(x, 0f), Offset(x, 10 * density), 1f)
            val label = when (b) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> "$b°" }
            drawContext.canvas.nativeCanvas.drawText(label, x - paint.measureText(label) / 2, 26 * density, paint)
        }
        val samples = state.scene?.horizon.orEmpty().mapNotNull { sample ->
            val e = sample.elevationDeg ?: return@mapNotNull null
            if (abs(Geography.wrap180(sample.bearingDeg - heading)) > state.fov / 2 + 2) null else sample to project(sample.bearingDeg, e)
        }.sortedBy { it.second.x }
        var path = Path()
        var firstX = 0f
        var lastX = 0f
        var active = false
        fun finish() {
            if (!active) return
            path.lineTo(lastX, h); path.lineTo(firstX, h); path.close()
            drawPath(path, Brush.verticalGradient(listOf(Color(0xFF547E68), Color(0xFF193E32)), startY = h * .3f, endY = h))
            path = Path(); active = false
        }
        samples.forEach { (sample, point) ->
            if (!sample.complete) { finish(); return@forEach }
            if (!active) { firstX = point.x; path.moveTo(point.x, point.y); active = true }
            else path.lineTo(point.x, point.y)
            lastX = point.x
        }
        finish()
        drawLine(Color(0x99AE6945), Offset(w / 2, 32 * density), Offset(w / 2, 47 * density), 2 * density)
        val laneEnd = FloatArray(((h / density - 100) / 48).toInt().coerceIn(1, 4)) { Float.NEGATIVE_INFINITY }
        val peaks = state.scene?.peaks.orEmpty().filter {
            it.visibility == Visibility.TERRAIN_VISIBLE || state.preferences.showUncertain && it.visibility == Visibility.UNCERTAIN
        }.mapNotNull { peak -> peak.elevationDeg?.let { peak to project(peak.bearingDeg, it) } }
            .filter { it.second.x in 8f..(w - 8f) }.sortedBy { it.second.x }
        for ((peak, point) in peaks) {
            paint.textSize = 11 * density
            val label = peak.peak.name + if (peak.visibility == Visibility.UNCERTAIN) " ?" else ""
            val heightText = peak.peak.publishedHeightM?.let { height(it, state) }
                ?: peak.geometryHeightM?.let { "~${height(it, state)}" } ?: "Height unknown"
            val detail = "$heightText · ${distance(peak.distanceM, state)}"
            val labelWidth = min(max(paint.measureText(label), paint.measureText(detail)) + 20 * density, w - 16 * density)
            val left = (point.x - labelWidth / 2).coerceIn(8 * density, max(8 * density, w - labelWidth - 8 * density))
            val lane = laneEnd.indexOfFirst { it + 10 * density <= left }
            if (lane < 0) continue
            laneEnd[lane] = left + labelWidth
            val top = (56 + lane * 48) * density
            val rect = RectF(left, top, left + labelWidth, top + 41 * density)
            drawLine(Color(0x997D917B), Offset(point.x, point.y), Offset(left + labelWidth / 2, rect.bottom), density)
            drawCircle(Color(0xFFF8F7E9), 4 * density, Offset(point.x, point.y))
            drawCircle(Color(0xFF315647), 4 * density, Offset(point.x, point.y), style = Stroke(density))
            drawRoundRect(Color(0xF2FFFDF4), Offset(rect.left, rect.top), androidx.compose.ui.geometry.Size(rect.width(), rect.height()), androidx.compose.ui.geometry.CornerRadius(5 * density))
            paint.color = 0xFF234F43.toInt()
            var display = label
            while (paint.measureText(display) > labelWidth - 16 * density && display.length > 2) display = display.dropLast(2) + "…"
            drawContext.canvas.nativeCanvas.drawText(display, left + 8 * density, top + 16 * density, paint)
            paint.textSize = 9 * density
            paint.color = 0xFF647A6A.toInt()
            drawContext.canvas.nativeCanvas.drawText(detail, left + 8 * density, top + 32 * density, paint)
            hits += rect to peak
        }
        paint.textSize = 10 * density
        paint.color = 0xFF566F60.toInt()
        val bottom = if (state.isDemo) "FICTIONAL LANDSCAPE · EXPLORE THE CONTROLS" else "TERRAIN MODEL · ${(state.scene?.radiusM ?: 0.0).toInt() / 1000} KM RANGE"
        drawContext.canvas.nativeCanvas.drawText(bottom, 14 * density, h - 14 * density, paint)
    }
}
