package com.harsh.peakbud.core.projection

import com.harsh.peakbud.core.model.PeakView
import com.harsh.peakbud.core.model.Visibility

/**
 * Taller summits first, so a distant giant wins over a nearby hill that merely looks higher in the frame.
 * Then clearly visible before uncertain, then how high it appears, then nearer first.
 */
fun Iterable<PeakView>.inLabelPriority(): List<PeakView> = sortedWith(
    compareByDescending<PeakView> { it.peak.publishedHeightM ?: it.geometryHeightM ?: Double.NEGATIVE_INFINITY }
        .thenBy { it.visibility != Visibility.TERRAIN_VISIBLE }
        .thenByDescending { it.elevationDeg ?: -90.0 }
        .thenBy { it.distanceM }
)
