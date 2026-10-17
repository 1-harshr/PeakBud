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

    /** Lowest and highest angles worth fitting on screen: the skyline in view and the peaks that may be labelled. */
    data class VerticalWindow(val lowDeg: Double, val highDeg: Double)

    fun window(samples: List<HorizonSample>, peakAngles: List<Pair<Double, Double>>, heading: Double, horizontalFov: Double): VerticalWindow? {
        val inView = { bearing: Double -> abs(Geography.wrap180(bearing - heading)) <= horizontalFov / 2 }
        val skyline = samples.filter { it.complete && inView(it.bearingDeg) }.mapNotNull { it.elevationDeg?.takeIf(Double::isFinite) }
        if (skyline.size < 3) return null
        val peaks = peakAngles.filter { inView(it.first) && it.second.isFinite() }.map { it.second }
        return VerticalWindow(skyline.min(), maxOf(skyline.max(), peaks.maxOrNull() ?: Double.NEGATIVE_INFINITY))
    }

    /**
     * Vertical scale (pixels per degree) that fits [window] between [topY] and [bottomY], never flatter than true angles
     * and at most [maxStretch] times steeper.
     */
    fun fittedPxPerDeg(window: VerticalWindow, topY: Float, bottomY: Float, horizontalFov: Double, width: Float, maxStretch: Double = 8.0): Double {
        val truePx = width / horizontalFov
        val relief = (window.highDeg - window.lowDeg).coerceAtLeast(.05)
        return ((bottomY - topY) / relief).coerceIn(truePx, truePx * maxStretch)
    }
}
