package com.harsh.peakbud.core

import com.harsh.peakbud.core.model.HorizonSample
import com.harsh.peakbud.core.projection.PanoramaProjector
import org.junit.Assert.*
import org.junit.Test

class PanoramaRenderingTest {
    private fun sample(b: Double, e: Double? = 4.0, complete: Boolean = true) = HorizonSample(b, e, 1000.0, complete)

    @Test fun missingElevationBreaksFillEvenWhenTheRecordClaimsComplete() {
        val input = listOf(sample(350.0), sample(355.0), sample(0.0, null), sample(5.0), sample(10.0))
        val result = PanoramaProjector.horizonSegments(input, 0.0, 60.0)
        assertEquals(listOf(listOf(350.0, 355.0), listOf(5.0, 10.0)), result.map { s -> s.map { it.bearingDeg } })
    }
    @Test fun partialTerrainAndNonFiniteElevationAlsoBreakFill() {
        val input = listOf(sample(0.0), sample(1.0, complete = false), sample(2.0), sample(3.0, Double.NaN), sample(4.0))
        assertEquals(3, PanoramaProjector.horizonSegments(input, 0.0, 60.0).size)
    }
    @Test fun completeNorthCrossingRemainsContinuousAndSortedInScreenOrder() {
        val input = listOf(sample(0.0), sample(5.0), sample(180.0), sample(355.0))
        val result = PanoramaProjector.horizonSegments(input, 0.0, 60.0)
        assertEquals(listOf(355.0, 0.0, 5.0), result.single().map { it.bearingDeg })
    }
}
