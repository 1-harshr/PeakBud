package com.harsh.peakbud.core.projection

import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.HorizonSample
import kotlin.math.abs

data class ScreenPoint(val x: Float, val y: Float)

object PanoramaProjector {
    /** Keep missing samples in the ordering so a filled ridge never bridges an unknown sector. */
    fun horizonSegments(samples: List<HorizonSample>, heading: Double, horizontalFov: Double): List<List<HorizonSample>> {
        val ordered = samples.filter { abs(Geography.wrap180(it.bearingDeg - heading)) <= horizontalFov / 2 + 2 }
            .sortedBy { Geography.wrap180(it.bearingDeg - heading) }
        val segments = mutableListOf<List<HorizonSample>>()
        var current = mutableListOf<HorizonSample>()
        for (sample in ordered) {
            if (!sample.complete || sample.elevationDeg?.isFinite() != true) {
                if (current.isNotEmpty()) segments += current
                current = mutableListOf()
            } else current += sample
        }
        if (current.isNotEmpty()) segments += current
        return segments
    }

    /** Cylindrical panorama, intentionally independent of a camera's perspective. */
    /**
     * [verticalScale] stretches heights (vertical exaggeration) so low, distant ridges are readable; 1 = true angles.
     * Angle labels must be drawn with the same projection so they stay truthful.
     */
    fun project(bearing: Double, elevation: Double, heading: Double, pitch: Double,
                width: Float, height: Float, horizontalFov: Double = 100.0, verticalScale: Double = 1.0): ScreenPoint {
        require(width > 0 && height > 0 && horizontalFov in 10.0..180.0)
        val verticalFov = horizontalFov * height / width
        return ScreenPoint(
            (width / 2 + Geography.wrap180(bearing - heading) / horizontalFov * width).toFloat(),
            (height * 0.58 - (elevation - pitch) * verticalScale / verticalFov * height).toFloat(),
        )
    }

    /**
     * Exaggeration that makes the skyline's height range (relief, in degrees) fill about an eighth of the view's
     * vertical angle, between 1× (true) and 3×. Steep Himalayan views stay true; plateau hills get stretched.
     */
    fun autoScale(reliefDeg: Double, horizontalFov: Double, width: Float, height: Float): Double {
        if (!reliefDeg.isFinite() || reliefDeg <= 0) return 1.0
        val verticalFov = horizontalFov * height / width
        return (verticalFov * .125 / reliefDeg).coerceIn(1.0, 3.0)
    }
}
