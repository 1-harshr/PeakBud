package com.harsh.peakbud.core.projection

import com.harsh.peakbud.core.model.PeakView
import com.harsh.peakbud.core.model.Visibility

/** High skyline summits can be distant; nearby minor hills must not consume every label slot. */
fun Iterable<PeakView>.inLabelPriority(): List<PeakView> = sortedWith(
    compareBy<PeakView> { it.visibility != Visibility.TERRAIN_VISIBLE }
        .thenByDescending { it.elevationDeg ?: -90.0 }
        .thenBy { it.distanceM }
)
