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

/**
 * Peaks to label, most important first: the focused peak always leads (even when hidden, so it can be found), then the
 * visible ones and, if wanted, the uncertain ones.
 */
fun Iterable<PeakView>.toLabel(showUncertain: Boolean, focusedId: String?): List<PeakView> {
    val focused = firstOrNull { it.peak.id == focusedId && it.elevationDeg != null }
    val rest = filter { it.peak.id != focusedId && (it.visibility == Visibility.TERRAIN_VISIBLE || showUncertain && it.visibility == Visibility.UNCERTAIN) }
    return listOfNotNull(focused) + rest.inLabelPriority()
}
