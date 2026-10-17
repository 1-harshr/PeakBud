package com.harsh.peakbud.core.terrain

import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import kotlin.math.*

/** Blocking, cancellable calculation. Call on a worker dispatcher, never the UI thread. */
class TerrainEngine {
    fun calculate(
        observer: Observer,
        terrain: Terrain,
        peaks: List<Peak>,
        settings: CalculationSettings = CalculationSettings(),
        checkpoint: () -> Unit = {},
    ): MountainScene {
        require(observer.datum == terrain.datum) { "Your height and the map use different references, so this is approximate." }
        require(terrain.resolutionM > 0 && terrain.resolutionM.isFinite())
        val count = ceil(360 / settings.angularStepDeg).toInt()
        val horizon = List(count) { index ->
            checkpoint()
            val bearing = index * 360.0 / count
            var highest = Double.NEGATIVE_INFINITY
            var distance: Double? = null
            var complete = true
            var d = terrain.resolutionM / 2
            while (d <= settings.radiusM) {
                if ((d / terrain.resolutionM).toInt() % 128 == 0) checkpoint()
                when (val sample = terrain.sample(Geography.destination(observer.point, bearing, d))) {
                    is TerrainSample.Valid -> {
                        val angle = Geography.elevationAngle(observer.heightM, sample.heightM, d)
                        if (angle > highest) { highest = angle; distance = d }
                    }
                    else -> complete = false
                }
                // Draft skyline only. Named targets use a finer, independent ray.
                d += max(terrain.resolutionM, d * 0.004)
            }
            HorizonSample(bearing, highest.takeIf { it.isFinite() }, distance, complete)
        }
        val views = peaks.filter { Geography.distance(observer.point, it.point) in 1.0..settings.radiusM }
            .map { peak -> checkpoint(); visibility(observer, peak, terrain, checkpoint) }
            .sortedBy { it.bearingDeg }
        return MountainScene(observer, horizon, views, settings.radiusM)
    }

    fun visibility(observer: Observer, peak: Peak, terrain: Terrain, checkpoint: () -> Unit = {}): PeakView {
        require(observer.datum == terrain.datum)
        val distance = Geography.distance(observer.point, peak.point)
        val bearing = Geography.bearing(observer.point, peak.point)
        val summit = terrain.sample(peak.point) as? TerrainSample.Valid
            ?: return PeakView(peak, distance, bearing, null, null, Visibility.UNKNOWN, reason = "There's no map data at the summit.")
        val angle = Geography.elevationAngle(observer.heightM, summit.heightM, distance)
        val step = (terrain.resolutionM / 3).coerceAtLeast(1.0)
        var maximum = Double.NEGATIVE_INFINITY
        var blocker: Double? = null
        var missing = false
        var d = step
        // Last half-cell is the summit's own DEM footprint, not a separate ridge.
        // Borderline approach geometry remains UNCERTAIN rather than self-occluding.
        while (d < distance - terrain.resolutionM / 2) {
            if ((d / step).toInt() % 128 == 0) checkpoint()
            when (val sample = terrain.sample(Geography.destination(observer.point, bearing, d))) {
                is TerrainSample.Valid -> {
                    val local = Geography.elevationAngle(observer.heightM, sample.heightM, d)
                    if (local > maximum) { maximum = local; blocker = d }
                }
                else -> missing = true
            }
            d += step
        }
        val targetError = Math.toDegrees(atan2(terrain.heightErrorM + observer.heightUncertaintyM, max(distance, 1.0)))
        val angularPositionError = Math.toDegrees(atan2(observer.horizontalAccuracyM + terrain.resolutionM / 2, max(distance, 1.0)))
        // Error budget at the limiting ridge and summit, rather than treating a
        // coherent DEM height offset as a new near-field wall at every sample.
        val ridgeError = Math.toDegrees(atan2(terrain.heightErrorM + observer.heightUncertaintyM, max(blocker ?: distance, 1.0)))
        val margin = targetError + ridgeError + angularPositionError + 0.02
        val geometricState = when {
            maximum > angle + margin -> Visibility.OCCLUDED
            missing -> Visibility.UNKNOWN
            distance < terrain.resolutionM * 2 -> Visibility.UNCERTAIN
            maximum < angle - margin -> Visibility.TERRAIN_VISIBLE
            else -> Visibility.UNCERTAIN
        }
        val heightDifference = peak.publishedHeightM?.let { abs(it - summit.heightM) }
        // A large discrepancy may mean a clipped summit, bad catalogue coordinates,
        // or a bad published height. Do not silently raise terrain to match a name.
        val inconsistentHeight = heightDifference != null && heightDifference > max(100.0, terrain.heightErrorM * 3)
        val state = if (inconsistentHeight && geometricState != Visibility.UNKNOWN) Visibility.UNCERTAIN else geometricState
        val reason = if (inconsistentHeight && state != Visibility.UNKNOWN)
            "The official height and the map differ by ${heightDifference!!.toInt()} m, so the summit position may be off."
        else when (state) {
            Visibility.TERRAIN_VISIBLE -> "Nothing in the landscape blocks it, though clouds or trees still might."
            Visibility.OCCLUDED -> "A closer ridge blocks your view of the summit."
            Visibility.UNCERTAIN -> "It sits close to the skyline, so small errors could hide it."
            Visibility.UNKNOWN -> "Map data between you and the peak is missing."
        }
        return PeakView(peak, distance, bearing, angle, summit.heightM, state,
            (angle - maximum).takeIf { it.isFinite() }, blocker, reason)
    }
}
