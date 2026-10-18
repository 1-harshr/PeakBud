package com.harsh.peakbud

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.harsh.peakbud.core.alignment.*
import com.harsh.peakbud.core.projection.CameraPose
import com.harsh.peakbud.feature.camera.*
import com.harsh.peakbud.feature.panorama.PanoramaState
import com.harsh.peakbud.ui.theme.PeakBudTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class CameraLifecycleTest {
    @get:Rule(order = 0) val permissions = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            listOf("CAMERA", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION").forEach {
                instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.$it").use { fd ->
                    java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
                }
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun levelHorizonWorksBeforeLocationAndTerrainAreAvailable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                PeakBudTheme {
                    CameraPanel(PanoramaState(followingCompass = true, cameraPose = CameraPose(0.0, 0.0, 0.0)),
                        Modifier.fillMaxSize(), {}, {}, {}, {}, onPeak = {})
                }
            }
        }
        val horizon = compose.activity.getString(R.string.level_horizon_description)
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(horizon).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(horizon).assertIsDisplayed()
    }

    @Test fun stoppedCameraClearsAlignmentAndPreviewCanResume() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = CameraAlignmentController(scope)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                compose.activity.setContent {
                    PeakBudTheme {
                        CameraPanel(PanoramaState(), Modifier.fillMaxSize(), {}, {}, {}, {}, alignment = controller, onPeak = {})
                    }
                }
            }
            val live = compose.activity.getString(R.string.live_rear_camera_preview)
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(live).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { controller.start(null, null, AlignmentProblem.NO_TERRAIN) }
            assertEquals(AlignmentPhase.FAILED, controller.ui.value.phase)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(AlignmentPhase.SENSOR, controller.ui.value.phase)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(live).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(live).assertIsDisplayed()
        } finally { controller.reset(); scope.cancel() }
    }
}
