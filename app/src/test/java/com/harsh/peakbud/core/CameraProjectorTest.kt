package com.harsh.peakbud.core

import com.harsh.peakbud.core.projection.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.tan

class CameraProjectorTest {
    private val lens = CameraLens(500.0, 500.0, 300.0, 400.0)
    private fun point(b: Double, e: Double, h: Double = 0.0, p: Double = 0.0, r: Double = 0.0) =
        CameraProjector.project(b, e, CameraPose(h, p, r), lens)!!

    @Test fun opticalAxisUsesLensCenterEvenWithPitch() {
        val result = point(42.0, 23.0, 42.0, 23.0)
        assertEquals(300f, result.x, .001f); assertEquals(400f, result.y, .001f)
    }
    @Test fun perspectiveUsesTangentAndElevationMovesUp() {
        val right = point(30.0, 0.0)
        assertEquals((300 + 500 * tan(Math.toRadians(30.0))).toFloat(), right.x, .001f)
        assertEquals(400f, right.y, .001f)
        assertTrue(point(0.0, 10.0).y < 400f)
        assertTrue(point(0.0, -10.0).y > 400f)
    }
    @Test fun headingWrapHasNoNorthDiscontinuity() {
        assertEquals(point(1.0, 0.0, 359.0).x, point(2.0, 0.0).x, .001f)
    }
    @Test fun rollRotatesRightwardPeakDownOnScreen() {
        val result = point(10.0, 0.0, r = 90.0)
        assertEquals(300f, result.x, .001f); assertTrue(result.y > 400f)
    }
    @Test fun fieldOfViewMatchesVisibleCropRatherThanFullSensor() {
        val (horizontal, vertical) = CameraLens(500.0, 500.0, 500.0, 250.0).fieldOfView(1000, 500)
        assertEquals(90.0, horizontal, .0001)
        assertEquals(53.1301, vertical, .0001)
    }
    @Test fun rejectsBehindCameraAndInvalidPose() {
        assertNull(CameraProjector.project(180.0, 0.0, CameraPose(0.0, 0.0, 0.0), lens))
        assertNull(CameraProjector.project(90.0, 0.0, CameraPose(0.0, 0.0, 0.0), lens))
        assertNull(CameraProjector.project(0.0, 0.0, CameraPose(Double.NaN, 0.0, 0.0), lens))
    }
    @Test fun cropChangesPixelScaleAndPrincipalPoint() {
        val cropped = CameraLens(1000.0, 1000.0, 150.0, 200.0)
        val result = CameraProjector.project(10.0, 0.0, CameraPose(0.0, 0.0, 0.0), cropped)!!
        assertEquals(2 * (point(10.0, 0.0).x - 300) + 150, result.x, .001f)
        assertEquals(200f, result.y, .001f)
    }
}
