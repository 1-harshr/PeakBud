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
        /** Receives partial scenes (skyline plus the peaks finished so far) about every [progressMs]; results are unchanged. */
        onProgress: ((MountainScene) -> Unit)? = null,
        progressMs: Long = 1_500,
        checkpoint: () -> Unit = {},
    ): MountainScene {
        require(observer.datum == terrain.datum) { "Your height and the map use different references, so this is approximate." }
        require(terrain.resolutionM > 0 && terrain.resolutionM.isFinite())
        val count = ceil(360 / settings.angularStepDeg).toInt()
        val horizon = List(count) { index ->
            checkpoint()
            val bearing = index * 360.0 / count
            val ray = TerrainRay(observer, bearing)
            var highest = Double.NEGATIVE_INFINITY
            var distance: Double? = null
            var complete = true
            var d = terrain.resolutionM / 2
            while (d <= settings.radiusM) {
                if ((d / terrain.resolutionM).toInt() % 128 == 0) checkpoint()
                when (val sample = terrain.sample(ray.pointAt(d))) {
                    is TerrainSample.Valid -> {
                        val slope = ray.slope(sample.heightM)
                        if (slope > highest) { highest = slope; distance = d }
                    }
                    else -> complete = false
                }
                // Draft skyline only. Named targets use a finer, independent ray.
                d += max(terrain.resolutionM, d * 0.004)
            }
            HorizonSample(bearing, highest.takeIf { it.isFinite() }?.let { Math.toDegrees(atan(it)) }, distance, complete)
        }
        // Nearest peaks first, so progressive results label the closest (usually most prominent) summits early.
        val candidates = peaks.map { it to Geography.distance(observer.point, it.point) }
            .filter { it.second in 1.0..settings.radiusM }.sortedBy { it.second }.map { it.first }
        val views = ArrayList<PeakView>(candidates.size)
        var lastProgress = System.nanoTime()
        for (peak in candidates) {
            checkpoint()
            views += visibility(observer, peak, terrain, checkpoint)
            if (onProgress != null && System.nanoTime() - lastProgress > progressMs * 1_000_000) {
                lastProgress = System.nanoTime()
                onProgress(MountainScene(observer, horizon, views.sortedBy { it.bearingDeg }, settings.radiusM))
            }
        }
        return MountainScene(observer, horizon, views.sortedBy { it.bearingDeg }, settings.radiusM)
    }

    fun visibility(observer: Observer, peak: Peak, terrain: Terrain, checkpoint: () -> Unit = {}): PeakView {
        require(observer.datum == terrain.datum)
        val distance = Geography.distance(observer.point, peak.point)
        val bearing = Geography.bearing(observer.point, peak.point)
        val ray = TerrainRay(observer, bearing)
        val summit = terrain.sample(peak.point) as? TerrainSample.Valid
            ?: return PeakView(peak, distance, bearing, null, null, Visibility.UNKNOWN, reason = PeakReason.NO_SUMMIT_DATA)
        val angle = Geography.elevationAngle(observer.heightM, summit.heightM, distance)
        val step = (terrain.resolutionM / 3).coerceAtLeast(1.0)
        var maximum = Double.NEGATIVE_INFINITY
        var maximumSlope = Double.NEGATIVE_INFINITY
        var blocker: Double? = null
        var missing = false
        var stoppedAtBlocker = false
        val targetError = Math.toDegrees(atan2(terrain.heightErrorM + observer.heightUncertaintyM, max(distance, 1.0)))
        val angularPositionError = Math.toDegrees(atan2(observer.horizontalAccuracyM + terrain.resolutionM / 2, max(distance, 1.0)))
        var d = step
        // Last half-cell is the summit's own DEM footprint, not a separate ridge.
        // Borderline approach geometry remains UNCERTAIN rather than self-occluding.
        while (d < distance - terrain.resolutionM / 2) {
            if ((d / step).toInt() % 128 == 0) checkpoint()
            when (val sample = terrain.sample(ray.pointAt(d))) {
                is TerrainSample.Valid -> {
                    val slope = ray.slope(sample.heightM)
                    if (slope > maximumSlope) {
                        maximumSlope = slope
                        maximum = Math.toDegrees(atan(slope))
                        blocker = d
                    }
                    // A proven blocker cannot be undone by more distant terrain. Preserve the
                    // same error budget as the final classification, without scanning the rest
                    // of a 250 km ray for an already hidden summit.
                    if (blocker == d) {
                        val ridgeError = Math.toDegrees(atan2(terrain.heightErrorM + observer.heightUncertaintyM, max(d, 1.0)))
                        if (maximum > angle + targetError + angularPositionError + ridgeError + 0.02) {
                            stoppedAtBlocker = true
                            break
                        }
                    }
                }
                else -> missing = true
            }
            d += step
        }
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
            PeakReason.HEIGHT_MISMATCH
        else when (state) {
            Visibility.TERRAIN_VISIBLE -> PeakReason.VISIBLE
            Visibility.OCCLUDED -> PeakReason.OCCLUDED
            Visibility.UNCERTAIN -> PeakReason.BORDERLINE
            Visibility.UNKNOWN -> PeakReason.MISSING_TERRAIN
        }
        return PeakView(peak, distance, bearing, angle, summit.heightM, state,
            (angle - maximum).takeIf { !stoppedAtBlocker && it.isFinite() }, blocker, reason, heightDifference?.toInt())
    }
}

/** Per-ray constants and arc trig are shared by destination and height projection.
 * Compare slopes before converting to angles; the 300 km domain has a positive horizontal component.
 * The cursor is private to one synchronous ray, never shared between calculations.
 */
private class TerrainRay(observer: Observer, bearingDeg: Double) {
    private val latitude = Math.toRadians(observer.point.latitude)
    private val longitude = Math.toRadians(observer.point.longitude)
    private val sinLatitude = sin(latitude)
    private val cosLatitude = cos(latitude)
    private val sinBearing = sin(Math.toRadians(bearingDeg))
    private val cosBearing = cos(Math.toRadians(bearingDeg))
    private val observerRadius = Geography.EARTH_RADIUS_M + observer.heightM
    private var sinArc = 0.0
    private var cosArc = 1.0
    fun pointAt(distanceM: Double): GeoPoint {
        val arc = distanceM / Geography.EARTH_RADIUS_M
        sinArc = sin(arc); cosArc = cos(arc)
        val sinDestination = (sinLatitude * cosArc + cosLatitude * sinArc * cosBearing).coerceIn(-1.0, 1.0)
        val destinationLatitude = asin(sinDestination)
        val destinationLongitude = longitude + atan2(sinBearing * sinArc * cosLatitude, cosArc - sinLatitude * sinDestination)
        return GeoPoint(Math.toDegrees(destinationLatitude), Geography.wrap180(Math.toDegrees(destinationLongitude)))
    }
    fun slope(heightM: Double): Double {
        val radius = Geography.EARTH_RADIUS_M + heightM
        return (radius * cosArc - observerRadius) / (radius * sinArc)
    }
}
