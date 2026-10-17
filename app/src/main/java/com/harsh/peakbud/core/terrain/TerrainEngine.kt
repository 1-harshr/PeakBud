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
        // Tallest summits first (unknown heights last), so progressive results show the peaks people look for early.
        val candidates = peaks.map { it to Geography.distance(observer.point, it.point) }
            .filter { it.second in 1.0..settings.radiusM }
            .sortedWith(compareByDescending<Pair<Peak, Double>> { it.first.publishedHeightM ?: Double.NEGATIVE_INFINITY }.thenBy { it.second })
            .map { it.first }
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
        // Gazetteer coordinates are often a few hundred metres off the true top. Use the highest terrain nearby as the
        // summit, so the peak is not judged hidden by its own crest.
        val top = snapToSummit(peak.point, terrain)
        val distance = Geography.distance(observer.point, top?.first ?: peak.point)
        val bearing = Geography.bearing(observer.point, top?.first ?: peak.point)
        val ray = TerrainRay(observer, bearing)
        val summit = top?.second
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
        // Skip the ground at the observer's feet (GPS and 30 m cells make it noise) and the summit's own massif: the last
        // 250 m before the top (the snapping radius) is the peak's flank, not a separate ridge in front of it.
        // Very close peaks keep the original half-cell rule.
        val nearSkip = min(60.0, distance / 4)
        val summitSkip = if (distance > 1_000) 250.0 else terrain.resolutionM / 2
        var d = max(step, nearSkip)
        while (d < distance - summitSkip) {
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
        // 30 m terrain rounds off sharp summits by a few hundred metres; only larger gaps suggest a wrong record.
        val inconsistentHeight = heightDifference != null && heightDifference > max(max(250.0, terrain.heightErrorM * 3), (peak.publishedHeightM ?: 0.0) * .04)
        // Catalogue disagreement may downgrade visibility, but cannot turn a proven blocker
        // into a drawable UNCERTAIN peak at the same (hidden) DEM coordinates.
        val state = if (inconsistentHeight && geometricState == Visibility.TERRAIN_VISIBLE) Visibility.UNCERTAIN else geometricState
        val reason = if (inconsistentHeight && state == Visibility.UNCERTAIN)
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

/**
 * Highest valid terrain within [radiusM] of [point], on a grid at the terrain's resolution. Ties keep the point
 * closest to the original; returns null when there is no terrain there at all.
 */
internal fun snapToSummit(point: GeoPoint, terrain: Terrain, radiusM: Double = 250.0): Pair<GeoPoint, TerrainSample.Valid>? {
    val origin = terrain.sample(point) as? TerrainSample.Valid
    var best: Pair<GeoPoint, TerrainSample.Valid>? = origin?.let { point to it }
    val step = terrain.resolutionM.coerceAtLeast(10.0)
    val n = (radiusM / step).toInt()
    val dLat = step / 111_320.0
    val dLon = step / (111_320.0 * cos(Math.toRadians(point.latitude)).coerceAtLeast(.01))
    for (i in -n..n) for (j in -n..n) {
        if (i == 0 && j == 0 || (i * i + j * j) * step * step > radiusM * radiusM) continue
        val candidate = GeoPoint((point.latitude + i * dLat).coerceIn(-90.0, 90.0), Geography.wrap180(point.longitude + j * dLon))
        val sample = terrain.sample(candidate) as? TerrainSample.Valid ?: continue
        // Move only for a clearly higher cell (1 m), so flat or synthetic terrain keeps the catalogue position.
        if (best == null || sample.heightM > best.second.heightM + 1.0) best = candidate to sample
    }
    return best
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
