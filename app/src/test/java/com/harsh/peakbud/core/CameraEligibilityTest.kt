package com.harsh.peakbud.core

import com.harsh.peakbud.core.alignment.AlignmentProblem
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.projection.CameraPose
import com.harsh.peakbud.data.regions.RegionPack
import com.harsh.peakbud.device.PositionFix
import com.harsh.peakbud.feature.panorama.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CameraEligibilityTest {
    private val point = GeoPoint(.5, .5)
    private val bounds = GeoBounds(0.0, 0.0, 1.0, 1.0)
    private val pack = RegionPack(File("unused"), "test", "Test", "1", bounds, bounds, 50_000.0, point, 0.0, "test", emptyList())
    private val ready = PanoramaState(activePack = pack, point = point, followingGps = true, gpsFresh = true,
        gpsFix = PositionFix(point, 5f, 0), followingCompass = true, cameraPose = CameraPose(0.0, 0.0, 0.0))

    @Test fun staleOrManualLocationCannotStartCameraAlignment() {
        assertNull(ready.alignmentBlocker(66.0))
        for (state in listOf(ready.copy(followingGps = false), ready.copy(gpsFresh = false), ready.copy(gpsFix = null),
            ready.copy(gpsFix = PositionFix(point, 100f, 0)))) {
            assertEquals(AlignmentProblem.ROUGH_LOCATION, state.alignmentBlocker(66.0))
        }
    }
    @Test fun stoppedTrackingOrUnavailableCameraCannotStartAlignment() {
        assertEquals(AlignmentProblem.HOLD_UPRIGHT, ready.copy(followingCompass = false).alignmentBlocker(66.0))
        assertEquals(AlignmentProblem.HOLD_UPRIGHT, ready.copy(cameraPose = null).alignmentBlocker(66.0))
        assertEquals(AlignmentProblem.NO_CAMERA, ready.alignmentBlocker(null))
        assertEquals(AlignmentProblem.NO_TERRAIN, ready.copy(point = GeoPoint(10.0, 10.0)).alignmentBlocker(66.0))
    }
}
