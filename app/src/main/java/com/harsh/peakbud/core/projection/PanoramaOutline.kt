package com.harsh.peakbud.core.projection

import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*

/** A skyline illustration and its pins must describe the same boundary.
 * The coarse panorama rays can miss a narrow summit. Insert its independently sampled DEM position,
 * but never lift a low/hidden peak onto an unrelated foreground ridge just to make a pin fit.
 */
data class PanoramaOutline(val horizon: List<HorizonSample>, val peaks: List<PeakView>, val focusedOffSkyline: Boolean) {
    companion object {
        fun build(horizon: List<HorizonSample>, candidates: List<PeakView>, focusedId: String?): PanoramaOutline {
            val sorted = horizon.sortedBy { it.bearingDeg }
            fun angleAt(bearing: Double): Double? {
                if (sorted.size < 2) return null
                val b = Geography.wrap360(bearing)
                val index = sorted.binarySearch { it.bearingDeg.compareTo(b) }
                if (index >= 0) {
                    val sample = sorted[index]
                    val connected = listOf(sorted[(index - 1 + sorted.size) % sorted.size], sorted[(index + 1) % sorted.size]).any {
                        it.complete && it.elevationDeg?.isFinite() == true && kotlin.math.abs(Geography.wrap180(it.bearingDeg - b)) <= 2
                    }
                    return sample.takeIf { it.complete && connected }?.elevationDeg?.takeIf(Double::isFinite)
                }
                val right = -index - 1
                val a = sorted[(right - 1 + sorted.size) % sorted.size]
                val c = sorted[right % sorted.size]
                val span = Geography.wrap360(c.bearingDeg - a.bearingDeg)
                val low = a.elevationDeg; val high = c.elevationDeg
                if (!a.complete || !c.complete || low == null || high == null || !low.isFinite() || !high.isFinite() || span <= 0 || span > 2) return null
                return low + (high - low) * Geography.wrap360(b - a.bearingDeg) / span
            }
            // A tenth of a degree permits the draft ray's interpolation error, not a large visual relocation.
            val ridgePeaks = candidates.filter { p ->
                val elevation = p.elevationDeg ?: return@filter false
                val outline = angleAt(p.bearingDeg) ?: return@filter false
                (p.visibility == Visibility.TERRAIN_VISIBLE || p.visibility == Visibility.UNCERTAIN) && elevation.isFinite() && elevation >= outline - .1
            }
            val summitNodes = ridgePeaks.groupBy { Geography.wrap360(it.bearingDeg) }.mapValues { (_, peaks) -> peaks.maxBy { it.elevationDeg!! } }
            val merged = sorted.filter { Geography.wrap360(it.bearingDeg) !in summitNodes } + summitNodes.map { (bearing, p) ->
                HorizonSample(bearing, p.elevationDeg, p.distanceM, true)
            }
            val tallestAtBearing = ridgePeaks.filter { summitNodes[Geography.wrap360(it.bearingDeg)]?.elevationDeg == it.elevationDeg }.map { it.peak.id }.toSet()
            val shown = candidates.filter { it.peak.id in tallestAtBearing || it.peak.id == focusedId }
            return PanoramaOutline(merged.sortedBy { it.bearingDeg }, shown, focusedId != null && focusedId !in tallestAtBearing && shown.any { it.peak.id == focusedId })
        }
    }
}
