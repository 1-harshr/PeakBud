package com.harsh.peakbud.core.alignment

import com.harsh.peakbud.core.model.HorizonSample
import com.harsh.peakbud.core.projection.*
import kotlin.math.*

data class AlignmentCorrection(val heading: Double, val pitch: Double) {
    fun apply(pose: CameraPose) = pose.copy(heading = (pose.heading + heading + 360) % 360, pitch = pose.pitch + pitch)
}
data class SkylineMatch(val correction: AlignmentCorrection, val errorDeg: Double, val support: Double, val competingErrorDeg: Double)
enum class MatchFailure { INVALID_VIEW, INCOMPLETE_TERRAIN, INSUFFICIENT_SHAPE, NO_FIT, AMBIGUOUS, SEARCH_LIMIT }
data class MatchResult(val match: SkylineMatch?, val failure: MatchFailure?)

/** Immutable periodic DEM interpolation. Missing/incomplete samples are never bridged. */
class TerrainSkyline(samples: List<HorizonSample>) {
    private val ordered = samples.sortedBy { it.bearingDeg }
    fun elevation(bearing: Double): Double? {
        if (ordered.size < 3) return null
        val b = (bearing % 360 + 360) % 360
        val insertion = ordered.binarySearch { it.bearingDeg.compareTo(b) }
        if (insertion >= 0) return ordered[insertion].takeIf { it.complete }?.elevationDeg?.takeIf { it.isFinite() }
        val right = -insertion - 1
        val a = ordered[(right - 1 + ordered.size) % ordered.size]
        val c = ordered[right % ordered.size]
        val span = (c.bearingDeg - a.bearingDeg + 360) % 360
        if (!a.complete || !c.complete || span <= 0 || span > 2) return null
        val ea = a.elevationDeg?.takeIf { it.isFinite() } ?: return null
        val ec = c.elevationDeg?.takeIf { it.isFinite() } ?: return null
        return ea + (ec - ea) * ((b - a.bearingDeg + 360) % 360) / span
    }
}

/** Bounded robust geometric registration, independent of Android and sensor state.
 * Thresholds are engineering gates, not recognition probabilities or outdoor accuracy claims.
 */
object SkylineMatcher {
    private data class Fit(val yaw: Double, val pitch: Double, val error: Double, val support: Double, val relief: Double)
    fun match(points: List<BoundaryPoint>, lens: CameraLens, pose: CameraPose, terrain: TerrainSkyline,
              checkpoint: () -> Unit = {}): MatchResult {
        fun fail(reason: MatchFailure) = MatchResult(null, reason)
        if (points.size < 40 || !listOf(pose.heading, pose.pitch, pose.roll).all { it.isFinite() } || abs(pose.pitch) > 45 || abs(pose.roll) > 45)
            return fail(MatchFailure.INVALID_VIEW)
        val rays = points.map { CameraRays.world(it.x, it.y, lens, pose) }
        val headings = rays.map { ((it.bearing - pose.heading + 540) % 360) - 180 }
        if (headings.max() - headings.min() < 12) return fail(MatchFailure.INSUFFICIENT_SHAPE)
        fun fit(yaw: Double, pitch: Double): Fit? {
            val adjusted = CameraPose(pose.heading + yaw, pose.pitch + pitch, pose.roll)
            val residuals = ArrayList<Double>(points.size)
            val expected = ArrayList<Double>(points.size)
            var minBearing = Double.POSITIVE_INFINITY; var maxBearing = Double.NEGATIVE_INFINITY
            for (point in points) {
                val ray = CameraRays.world(point.x, point.y, lens, adjusted)
                // Columns without a trustworthy prediction (missing terrain, or nearby ground that would block the far
                // skyline) are skipped rather than guessed. Enough of the view must remain.
                val elevation = terrain.elevation(ray.bearing) ?: continue
                residuals += ray.elevation - elevation
                expected += elevation
                val relative = ((ray.bearing - pose.heading + 540) % 360) - 180
                minBearing = min(minBearing, relative); maxBearing = max(maxBearing, relative)
            }
            if (residuals.size < max(30, points.size * 6 / 10) || maxBearing - minBearing < 12) return null
            val ordered = residuals.map { abs(it) }.sorted()
            // Inliers are counted against every observed column, so skipped columns cannot inflate support.
            val inliers = ordered.count { it <= .45 }.toDouble() / points.size
            // Trim at most a quarter of columns; foreground outliers cannot define the pose.
            val retained = ordered.take(max(1, ordered.size * 3 / 4))
            val error = sqrt(retained.sumOf { it * it } / retained.size)
            expected.sort()
            return Fit(yaw, pitch, error, inliers, expected[expected.size * 9 / 10] - expected[expected.size / 10])
        }
        fun bestPitch(yaw: Double): Fit? {
            val residuals = points.mapNotNull { point ->
                val ray = CameraRays.world(point.x, point.y, lens, pose.copy(heading = pose.heading + yaw))
                terrain.elevation(ray.bearing)?.let { ray.elevation - it }
            }.sorted()
            if (residuals.size < max(30, points.size * 6 / 10)) return null
            val estimate = -residuals[residuals.size / 2]
            var best: Fit? = null
            for (i in -5..5) {
                val pitch = (estimate + i * .2).coerceIn(-10.0, 10.0)
                val candidate = fit(yaw, pitch) ?: continue
                if (best == null || candidate.error < best.error) best = candidate
            }
            return best
        }
        val candidates = (-40..40).mapNotNull { checkpoint(); bestPitch(it * .5) }
        if (candidates.isEmpty()) return fail(MatchFailure.INCOMPLETE_TERRAIN)
        var best = candidates.minBy { it.error }
        val initial = best.yaw
        for (i in -10..10) {
            checkpoint()
            val candidate = bestPitch(initial + i * .05) ?: continue
            if (candidate.error < best.error) best = candidate
        }
        val competitor = candidates.filter { abs(it.yaw - best.yaw) >= 3 }.minOfOrNull { it.error } ?: Double.POSITIVE_INFINITY
        if (best.relief < .7) return fail(MatchFailure.INSUFFICIENT_SHAPE)
        if (best.error > .25 || best.support < .72) return fail(MatchFailure.NO_FIT)
        if (competitor - best.error < .12 || competitor < best.error * 1.4) return fail(MatchFailure.AMBIGUOUS)
        if (abs(best.yaw) >= 19.5 || abs(best.pitch) >= 9.5) return fail(MatchFailure.SEARCH_LIMIT)
        return MatchResult(SkylineMatch(AlignmentCorrection(best.yaw, best.pitch), best.error, best.support, competitor), null)
    }
}
