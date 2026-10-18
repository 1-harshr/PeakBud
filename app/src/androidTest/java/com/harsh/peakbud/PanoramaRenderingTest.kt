package com.harsh.peakbud

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.feature.panorama.PanoramaCanvas
import com.harsh.peakbud.feature.panorama.PanoramaState
import com.harsh.peakbud.ui.theme.PeakBudTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PanoramaRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val scene = MountainScene(Observer(GeoPoint(0.0, 0.0), 0.0),
        listOf(HorizonSample(340.0, 4.0, 1000.0, true), HorizonSample(350.0, 4.0, 1000.0, true),
            HorizonSample(0.0, null, null, false), HorizonSample(10.0, 4.0, 1000.0, true), HorizonSample(20.0, 4.0, 1000.0, true)),
        emptyList(), 50_000.0)

    @Test fun missingTerrainIsSkyRatherThanAnInventedRidgeAcrossTheGap() {
        var showTerrain by mutableStateOf(true)
        compose.setContent {
            PeakBudTheme {
                PanoramaCanvas(PanoramaState(scene = if (showTerrain) scene else scene.copy(horizon = emptyList()), heading = 0.0, fov = 60.0),
                    Modifier.size(280.dp).testTag("panorama"), {}, {})
            }
        }
        val terrain = compose.onNodeWithTag("panorama").captureToImage().toPixelMap()
        compose.runOnIdle { showTerrain = false }
        val sky = compose.onNodeWithTag("panorama").captureToImage().toPixelMap()
        val y = (terrain.height * .84).toInt() // below the eye-level reference, above the footer
        val gapX = terrain.width / 2
        val ridgeX = terrain.width / 4
        assertEquals("Unknown middle sector must remain sky", sky[gapX, y], terrain[gapX, y])
        assertNotEquals("Known side sector must still draw its ridge", sky[ridgeX, y], terrain[ridgeX, y])
    }

    @Test fun canvasSurvivesAZeroSizedLayoutThenRendersAfterResize() {
        var expanded by mutableStateOf(false)
        compose.setContent {
            PeakBudTheme {
                Box(Modifier.size(280.dp)) {
                    PanoramaCanvas(PanoramaState(scene = scene, heading = 0.0),
                        Modifier.size(if (expanded) 280.dp else 0.dp).testTag("resized"), {}, {})
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { expanded = true }
        compose.onNodeWithTag("resized").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("resized").captureToImage().width > 0)
    }

    @Test fun summitDotTouchesTheDrawnCrestAndLowUncertainDotIsNotDrawnInsideTheFill() {
        fun peak(id: String, bearing: Double, elevation: Double) = PeakView(
            Peak(id, id, GeoPoint(0.0, 0.0), 300.0, "synthetic regression"), 10_000.0, bearing, elevation, 300.0,
            Visibility.UNCERTAIN, reason = PeakReason.BORDERLINE)
        val scene = scene.copy(horizon = List(720) { HorizonSample(it * .5, 1.5, 5000.0, true) },
            peaks = listOf(peak("Low hill", 350.0, .4), peak("Summit", 10.0, 2.7)))
        compose.setContent {
            PeakBudTheme {
                PanoramaCanvas(PanoramaState(scene = scene, heading = 0.0, fov = 60.0),
                    Modifier.size(320.dp, 280.dp).testTag("pins"), {}, {})
            }
        }
        val image = compose.onNodeWithTag("pins").captureToImage().toPixelMap()
        val summitX = (image.width * (0.5 + 10.0 / 60)).toInt()
        val summitY = (image.height * .45).toInt()
        assertTrue("The actual summit must have a visible dot at its crest", image[summitX, summitY].red > .7f)
        val lowX = (image.width * (0.5 - 10.0 / 60)).toInt()
        val lowY = (image.height * (.6 - (.4 - 1.35) * .3 / 2.7)).toInt()
        val background = image[lowX - 15, lowY]
        val anchor = image[lowX, lowY]
        // GPU gradient dithering can vary a neighbouring pixel by a colour step; a summit dot is much brighter.
        assertEquals("No misleading dot under the ridge", background.red, anchor.red, .02f)
        assertEquals(background.green, anchor.green, .02f)
        assertEquals(background.blue, anchor.blue, .02f)
    }
}
