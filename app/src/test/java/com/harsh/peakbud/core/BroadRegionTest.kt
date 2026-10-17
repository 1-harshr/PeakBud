package com.harsh.peakbud.core

import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.projection.inLabelPriority
import com.harsh.peakbud.core.terrain.TerrainEngine
import org.junit.Assert.*
import org.junit.Test

class BroadRegionTest {
    private val origin = GeoPoint(30.53, 79.57)
    private val summit = Geography.destination(origin, 35.0, 200_000.0)
    private val peak = Peak("far", "Distant cross-border summit", summit, 8000.0, "SYNTHETIC TEST")
    private fun terrain(blocked: Boolean = false, missing: Boolean = false, reads: () -> Unit = {}) = object : Terrain {
        override val datum = VerticalDatum.EGM96
        override val resolutionM = 300.0
        override val heightErrorM = 0.0
        override fun sample(point: GeoPoint): TerrainSample {
            reads()
            val distance = Geography.distance(origin, point)
            return when {
                Geography.distance(summit, point) < 160 -> TerrainSample.Valid(8000.0)
                blocked && distance in 20_000.0..21_000.0 -> TerrainSample.Valid(7000.0)
                missing && distance in 80_000.0..81_000.0 -> TerrainSample.TileMissing
                else -> TerrainSample.Valid(1000.0)
            }
        }
    }
    @Test fun distantSummitIsConsideredBeyondTheOldPackLimit() {
        val scene = TerrainEngine().calculate(Observer(origin, 3000.0, heightUncertaintyM = 0.0), terrain(), listOf(peak),
            CalculationSettings(250_000.0, angularStepDeg = 10.0))
        assertEquals(Visibility.TERRAIN_VISIBLE, scene.peaks.single().visibility)
        assertEquals(200_000.0, scene.peaks.single().distanceM, .01)
    }
    @Test fun distantSightlineStillNeedsInterveningTerrain() {
        assertEquals(Visibility.UNKNOWN, TerrainEngine().visibility(Observer(origin, 3000.0), peak, terrain(missing = true)).visibility)
    }
    @Test fun provenNearBlockerAvoidsScanningTheRemainingLongRay() {
        var samples = 0
        val result = TerrainEngine().visibility(Observer(origin, 3000.0), peak, terrain(blocked = true) { samples++ })
        assertEquals(Visibility.OCCLUDED, result.visibility)
        assertNull(result.clearanceDeg) // A partial ray must not publish an exact whole-path clearance.
        assertTrue("Long blocked ray read $samples cells", samples < 300)
    }
    @Test fun equalHeightSphericalTargetHasHalfArcDepression() {
        val radius = 250_000.0
        assertEquals(-Math.toDegrees(radius / Geography.EARTH_RADIUS_M) / 2,
            Geography.elevationAngle(3000.0, 3000.0, radius), 1e-8)
    }
    @Test fun candidateBoundsContainEveryDirectionAtSearchRadius() {
        val bounds = Geography.viewingBounds(origin, 250_000.0)
        for (bearing in 0 until 360) assertTrue(bounds.contains(Geography.destination(origin, bearing.toDouble(), 250_000.0)))
    }
    @Test fun highDistantVisiblePeakPrecedesNearbyLowAndUncertainPeaks() {
        fun view(id: String, distance: Double, angle: Double, visibility: Visibility) = PeakView(
            peak.copy(id = id), distance, 35.0, angle, 8000.0, visibility, reason = PeakReason.VISIBLE)
        val sorted = listOf(view("near-low", 2000.0, .5, Visibility.TERRAIN_VISIBLE),
            view("distant-high", 200_000.0, 3.0, Visibility.TERRAIN_VISIBLE),
            view("uncertain", 5000.0, 10.0, Visibility.UNCERTAIN)).inLabelPriority()
        assertEquals(listOf("distant-high", "near-low", "uncertain"), sorted.map { it.peak.id })
    }
}
