package com.harsh.peakbud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.harsh.peakbud.core.projection.*
import com.harsh.peakbud.feature.camera.CameraHorizon
import com.harsh.peakbud.ui.theme.PeakBudTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HorizonRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cameraDrawsEyeLevelAndDoesNotInventItWhenPointingAtTheSky() {
        var pitch by mutableDoubleStateOf(0.0)
        val description = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.level_horizon_description)
        compose.setContent {
            PeakBudTheme {
                val density = LocalDensity.current.density.toDouble()
                Box(Modifier.size(320.dp, 240.dp).background(Color.Black).testTag("view")) {
                    CameraHorizon(CameraPose(0.0, pitch, 0.0), CameraLens(200 * density, 200 * density, 160 * density, 120 * density), Modifier.fillMaxSize())
                }
            }
        }
        compose.onNodeWithContentDescription(description).assertIsDisplayed()
        val pixels = compose.onNodeWithTag("view").captureToImage().toPixelMap()
        val y = pixels.height / 2
        val bright = (0 until pixels.width).count { pixels[it, y].red > .5f }
        assertTrue("The dashed reference must be drawn across the calibrated eye level", bright > pixels.width / 3)
        compose.runOnIdle { pitch = 80.0 }
        compose.onNodeWithContentDescription(description).assertDoesNotExist()
        val sky = compose.onNodeWithTag("view").captureToImage().toPixelMap()
        assertTrue((0 until sky.width).all { sky[it, y] == Color.Black })
    }
}
