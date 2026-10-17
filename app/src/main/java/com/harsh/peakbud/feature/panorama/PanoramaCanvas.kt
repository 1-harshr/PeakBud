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
        val compassBand = 40 * density
        val elevationLabels = mutableListOf<Pair<Float, String>>()
        for (angle in -20..40 step 10) {
            val y = project(heading, angle.toDouble()).y
            if (y in (compassBand + 14 * density)..(h - 28 * density)) {
                drawLine(Color(0x18516B5A), Offset(0f, y), Offset(w, y), 1f)
                elevationLabels += y to "$angle°"
            }
        }
        for (b in 0 until 360 step 15) {
            val x = project(b.toDouble(), 0.0).x
            if (x !in 0f..w) continue
            drawLine(Color(0xFF8AA194), Offset(x, 0f), Offset(x, 10 * density), 1f)
            val label = when (b) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> "$b°" }
            val half = paint.measureText(label) / 2
            if (x - half < 4 * density || x + half > w - 4 * density) continue
            drawContext.canvas.nativeCanvas.drawText(label, x - half, 26 * density, paint)
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
        val occupied = mutableListOf<RectF>()
        val cardHeight = 41 * density
        val peaks = state.scene?.peaks.orEmpty().filter {
            it.visibility == Visibility.TERRAIN_VISIBLE || state.preferences.showUncertain && it.visibility == Visibility.UNCERTAIN
        }.mapNotNull { peak -> peak.elevationDeg?.let { peak to project(peak.bearingDeg, it) } }
            .filter { it.second.x in 8f..(w - 8f) && it.second.y in compassBand..h }.sortedBy { it.first.distanceM }
        val maxCards = 5
        for ((peak, point) in peaks) {
            if (occupied.size >= maxCards) {
                drawCircle(Color(0xCCF8F7E9), 3 * density, Offset(point.x, point.y))
                continue
            }
            paint.textSize = 11 * density
            val label = peak.peak.name + if (peak.visibility == Visibility.UNCERTAIN) " ?" else ""
            val heightText = peak.peak.publishedHeightM?.let { height(it, state) }
                ?: peak.geometryHeightM?.let { "~${height(it, state)}" } ?: "Height unknown"
            val detail = "$heightText · ${distance(peak.distanceM, state)}"
            val labelWidth = min(max(paint.measureText(label), paint.measureText(detail)) + 20 * density, w - 16 * density)
            val left = (point.x - labelWidth / 2).coerceIn(8 * density, max(8 * density, w - labelWidth - 8 * density))
            // Prefer rows above the summit (below the compass band); fall back to rows beneath it.
            val tops = (0..3).map { point.y - (22 + it * 48) * density - cardHeight }.filter { it >= compassBand + 4 * density } +
                (0..3).map { point.y + (22 + it * 48) * density }.filter { it + cardHeight <= h - 28 * density }
            val card = tops.map { RectF(left, it, left + labelWidth, it + cardHeight) }
                .firstOrNull { c -> occupied.none { RectF.intersects(it, c) } } ?: continue
            occupied += card
            drawLine(Color(0x997D917B), Offset(point.x, point.y), Offset(card.centerX(), if (card.bottom <= point.y) card.bottom else card.top), density)
            drawCircle(Color(0xFFF8F7E9), 4 * density, Offset(point.x, point.y))
            drawCircle(Color(0xFF315647), 4 * density, Offset(point.x, point.y), style = Stroke(density))
            drawRoundRect(Color(0xF2FFFDF4), Offset(card.left, card.top), androidx.compose.ui.geometry.Size(card.width(), card.height()), androidx.compose.ui.geometry.CornerRadius(5 * density))
            paint.color = 0xFF234F43.toInt()
            var display = label
            while (paint.measureText(display) > labelWidth - 16 * density && display.length > 2) display = display.dropLast(2) + "…"
            drawContext.canvas.nativeCanvas.drawText(display, card.left + 8 * density, card.top + 16 * density, paint)
            paint.textSize = 9 * density
            paint.color = 0xFF647A6A.toInt()
            drawContext.canvas.nativeCanvas.drawText(detail, card.left + 8 * density, card.top + 32 * density, paint)
            hits += card to peak
        }
        paint.textSize = 11 * density
        paint.color = 0xFF526A60.toInt()
        // Labels sit against the sky only: skip any covered by a card or by the terrain at the left edge.
        val skylineAtEdge = samples.filter { it.first.complete }.minByOrNull { abs(it.second.x - 14 * density) }?.second?.y ?: h
        for ((y, text) in elevationLabels) {
            val box = RectF(6 * density, y - 14 * density, 10 * density + paint.measureText(text), y)
            if (y > skylineAtEdge - 2 * density) continue
            if (occupied.none { RectF.intersects(it, box) }) drawContext.canvas.nativeCanvas.drawText(text, 10 * density, y - 4 * density, paint)
        }
        paint.textSize = 10 * density
        paint.color = 0xFF566F60.toInt()
        val bottom = if (state.isDemo) "FICTIONAL LANDSCAPE · DRAG TO LOOK AROUND" else "TERRAIN MODEL · ${(state.scene?.radiusM ?: state.activePack?.radiusM ?: 0.0).toInt() / 1000} KM · DRAG TO LOOK AROUND"
        drawContext.canvas.nativeCanvas.drawText(bottom, 14 * density, h - 14 * density, paint)
    }
}
