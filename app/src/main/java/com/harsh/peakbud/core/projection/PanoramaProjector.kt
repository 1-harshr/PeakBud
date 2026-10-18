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

    /** Lowest and highest terrain/peak angles in view; optionally extend the fit to include eye level. */
    data class VerticalWindow(val lowDeg: Double, val highDeg: Double)

    fun window(samples: List<HorizonSample>, peakAngles: List<Pair<Double, Double>>, heading: Double, horizontalFov: Double,
               includeHorizon: Boolean = false): VerticalWindow? {
        val inView = { bearing: Double -> abs(Geography.wrap180(bearing - heading)) <= horizontalFov / 2 }
        val skyline = samples.filter { it.complete && inView(it.bearingDeg) }.mapNotNull { it.elevationDeg?.takeIf(Double::isFinite) }
        if (skyline.size < 3) return null
        val peaks = peakAngles.filter { inView(it.first) && it.second.isFinite() }.map { it.second }
        // A labelled peak can sit below the lowest skyline point (a low hill in front of a higher range), so it bounds
        // the window from below as well as above.
        return VerticalWindow(minOf(skyline.min(), peaks.minOrNull() ?: Double.POSITIVE_INFINITY, if (includeHorizon) 0.0 else Double.POSITIVE_INFINITY),
            maxOf(skyline.max(), peaks.maxOrNull() ?: Double.NEGATIVE_INFINITY, if (includeHorizon) 0.0 else Double.NEGATIVE_INFINITY))
    }

    /**
     * Vertical scale (pixels per degree) that fits [window] between [topY] and [bottomY], never flatter than true angles
     * and at most [maxStretch] times steeper. [fitEntireWindow] also allows compression so eye level
     * and a steep ridge can both remain visible in a short landscape viewport.
     */
    fun fittedPxPerDeg(window: VerticalWindow, topY: Float, bottomY: Float, horizontalFov: Double, width: Float, maxStretch: Double = 8.0,
                       fitEntireWindow: Boolean = false): Double {
        val truePx = width / horizontalFov
        val relief = (window.highDeg - window.lowDeg).coerceAtLeast(.05)
        return ((bottomY - topY) / relief).coerceIn(if (fitEntireWindow) 0.0 else truePx, truePx * maxStretch)
    }
}
