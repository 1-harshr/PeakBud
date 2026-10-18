package com.harsh.peakbud.core

import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.projection.*
import org.junit.Assert.*
import org.junit.Test

class PanoramaOutlineTest {
    private val horizon = List(720) { HorizonSample(it * .5, 1.5, 5000.0, true) }
    private fun peak(id: String, bearing: Double, elevation: Double, visibility: Visibility = Visibility.UNCERTAIN) =
        PeakView(Peak(id, id, GeoPoint(0.0, 0.0), null, "test"), 10_000.0, bearing, elevation, 300.0, visibility, reason = PeakReason.BORDERLINE)

    @Test fun lowUncertainPeaksAreNotPresentedAsTheVisibleCrest() {
        val low = listOf(peak("Bamni", 108.2, .4), peak("Khairiahi", 114.7, .55))
        val result = PanoramaOutline.build(horizon, low, null)
        assertTrue(result.peaks.isEmpty())
        assertEquals(horizon, result.horizon)
        assertEquals(.4, low.first().elevationDeg!!, 0.0) // source geography is never moved to the ridge
    }
    @Test fun namedNarrowSummitBecomesAnExactVertexOfTheRenderedRidge() {
        val summit = peak("narrow", 110.25, 2.7, Visibility.TERRAIN_VISIBLE)
        val result = PanoramaOutline.build(horizon, listOf(summit), null)
        assertEquals(listOf(summit), result.peaks)
        val vertex = result.horizon.single { it.bearingDeg == summit.bearingDeg }
        assertEquals(summit.elevationDeg, vertex.elevationDeg)
        assertEquals(summit.distanceM, vertex.distanceM!!, 0.0)
    }
    @Test fun smallDraftInterpolationDifferenceUsesTheActualDemSummitWithoutFloatingDots() {
        val summit = peak("crest", 110.25, 1.46)
        val result = PanoramaOutline.build(horizon, listOf(summit), null)
        assertEquals(1.46, result.horizon.single { it.bearingDeg == 110.25 }.elevationDeg!!, 0.0)
        assertEquals(listOf(summit), result.peaks)
    }
    @Test fun missingTerrainIsNotInventedAndFocusedReferenceIsKeptExplicit() {
        val missing = horizon.map { if (it.bearingDeg == 110.0) it.copy(complete = false, elevationDeg = null) else it }
        val summit = peak("focus", 110.25, 2.7)
        val result = PanoramaOutline.build(missing, listOf(summit), "focus")
        assertEquals(missing, result.horizon)
        assertEquals(listOf(summit), result.peaks)
        assertTrue(result.focusedOffSkyline)
    }
    @Test fun twoSummitsAtTheSameBearingCannotBothAnchorTheOuterBoundary() {
        val low = peak("low", 110.25, 1.5)
        val high = peak("high", 110.25, 2.7)
        val result = PanoramaOutline.build(horizon, listOf(low, high), null)
        assertEquals(listOf(high), result.peaks)
        assertEquals(2.7, result.horizon.single { it.bearingDeg == 110.25 }.elevationDeg!!, 0.0)
    }
    @Test fun northCrossingUsesTheAdjacentRidgeRatherThanTheOppositeSideOfThePanorama() {
        val summit = peak("north", 359.75, 2.0)
        val result = PanoramaOutline.build(horizon, listOf(summit), null)
        assertEquals(listOf(summit), result.peaks)
        assertEquals(2.0, result.horizon.single { it.bearingDeg == 359.75 }.elevationDeg!!, 0.0)
    }
    @Test fun hiddenFocusedPeakDoesNotRaiseOrReshapeTheVisibleRidge() {
        val hidden = peak("hidden", 110.25, .4, Visibility.OCCLUDED)
        val result = PanoramaOutline.build(horizon, listOf(hidden), "hidden")
        assertEquals(horizon, result.horizon)
        assertEquals(listOf(hidden), result.peaks)
        assertTrue(result.focusedOffSkyline)
    }
    @Test fun isolatedValidSampleCannotProduceAPinOnAnUndrawnSegment() {
        val isolated = horizon.map { if (it.bearingDeg == 110.0) it else it.copy(complete = false, elevationDeg = null) }
        assertTrue(PanoramaOutline.build(isolated, listOf(peak("isolated", 110.0, 1.5)), null).peaks.isEmpty())
    }
}
