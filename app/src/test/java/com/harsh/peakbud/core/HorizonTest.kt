package com.harsh.peakbud.core

import com.harsh.peakbud.core.model.HorizonSample
import com.harsh.peakbud.core.projection.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class HorizonTest {
    private val lens = CameraLens(500.0, 480.0, 280.0, 380.0)
    @Test fun levelCameraUsesCalibratedPrincipalPointRatherThanViewCentre() {
        val line = CameraProjector.horizon(CameraPose(0.0, 0.0, 0.0), lens, 600, 800)!!
        assertEquals(380f, line.start.y, .001f); assertEquals(380f, line.end.y, .001f)
        assertEquals(0f, line.start.x, .001f); assertEquals(600f, line.end.x, .001f)
    }
    @Test fun lookingUpMovesLevelDownAndRollTiltsItWithThePeaks() {
        val pose = CameraPose(355.0, 12.0, 20.0)
        val line = CameraProjector.horizon(pose, lens, 600, 800)!!
        assertTrue(line.start.y > lens.cy)
        assertTrue(line.end.y > line.start.y)
        for (i in 0..10) {
            val x = line.start.x + (line.end.x - line.start.x) * i / 10
            val y = line.start.y + (line.end.y - line.start.y) * i / 10
            assertEquals(0.0, CameraRays.world(x.toDouble(), y.toDouble(), lens, pose).elevation, .00001)
        }
    }
    @Test fun everyClippedLinePointIsAZeroElevationRayIncludingQuarterTurnRoll() {
        for (roll in listOf(-120.0, -90.0, -35.0, 0.0, 35.0, 90.0, 120.0)) {
            val pose = CameraPose(150.0, -10.0, roll)
            val line = CameraProjector.horizon(pose, lens, 600, 800)!!
            for (point in listOf(line.start, line.end)) {
                assertTrue(point.x in 0f..600f && point.y in 0f..800f)
                assertEquals(0.0, CameraRays.world(point.x.toDouble(), point.y.toDouble(), lens, pose).elevation, .00001)
            }
        }
    }
    @Test fun noFabricatedLineWhenLookingStraightUpDownOrOutsideTheView() {
        for (pitch in listOf(-90.0, -80.0, 80.0, 90.0))
            assertNull(CameraProjector.horizon(CameraPose(0.0, pitch, 0.0), lens, 600, 800))
        assertNull(CameraProjector.horizon(CameraPose(0.0, Double.NaN, 0.0), lens, 600, 800))
        assertNull(CameraProjector.horizon(CameraPose(0.0, 0.0, 0.0), lens, 0, 800))
    }
    @Test fun canvasFitIncludesEyeLevelAboveOrBelowTheRidges() {
        for (elevation in listOf(-12.0, 12.0)) {
            val samples = List(5) { HorizonSample(it.toDouble(), elevation, 1000.0, true) }
            val window = PanoramaProjector.window(samples, emptyList(), 0.0, 60.0, includeHorizon = true)!!
            assertTrue(window.lowDeg <= 0 && window.highDeg >= 0)
            assertEquals(min(0.0, elevation), window.lowDeg, .0001)
            assertEquals(max(0.0, elevation), window.highDeg, .0001)
        }
    }
    @Test fun eyeLevelAndSteepRidgeBothFitInShortLandscapeCanvas() {
        val window = PanoramaProjector.VerticalWindow(0.0, 60.0)
        val scale = PanoramaProjector.fittedPxPerDeg(window, 90f, 150f, 60.0, 800f, fitEntireWindow = true)
        assertEquals(1.0, scale, .0001)
        assertEquals(150.0, 120 - (0 - 30) * scale, .0001)
        assertEquals(90.0, 120 - (60 - 30) * scale, .0001)
    }
}
