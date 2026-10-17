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
        val y = (terrain.height * .75).toInt()
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
}
