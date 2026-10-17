package com.harsh.peakbud

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.harsh.peakbud.core.model.GeoPoint
import com.harsh.peakbud.device.*
import com.harsh.peakbud.feature.panorama.CameraPositionOverlay
import com.harsh.peakbud.feature.panorama.PanoramaState
import com.harsh.peakbud.ui.theme.PeakBudTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class FullScreenCameraTest {
    @get:Rule(order = 0) val permission = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            listOf("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION").forEach {
                instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.$it").use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { stream -> stream.readBytes() }
                }
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun text(id: Int) = compose.activity.getString(id)

    @Before fun stableReadings() {
        val readings = object : DeviceReadings {
            override fun poses(displayRotation: Int) = emptyFlow<DevicePose>()
            override fun fixes() = emptyFlow<LocationStatus>()
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent { PeakBudRoot(readings) }
        }
    }

    @Test fun fullScreenKeepsCoordinatesAndExitsWithButtonAndBack() {
        compose.onNodeWithContentDescription(text(R.string.camera)).performClick()
        compose.onNodeWithContentDescription(text(R.string.enter_full_screen)).performClick()
        compose.onNodeWithContentDescription(text(R.string.exit_full_screen)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.camera_location_metadata)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.home)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.exit_full_screen)).performClick()
        compose.onNodeWithContentDescription(text(R.string.enter_full_screen)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.home)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.enter_full_screen)).performClick()
        Espresso.pressBack()
        compose.onNodeWithContentDescription(text(R.string.enter_full_screen)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.camera_location_metadata)).assertIsDisplayed()
    }

    @Test fun metadataUsesDeviceFixAndMarksLastKnownPosition() {
        val state = PanoramaState(followingGps = true, gpsFresh = false,
            gpsFix = PositionFix(GeoPoint(30.53, 79.57), 5f, 60, seaLevelAltitudeM = 3000.0))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent { PeakBudTheme { CameraPositionOverlay(state) } }
        }
        compose.onNodeWithText("Lat 30.53000° · Lon 79.57000°").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.camera_last_known_location), substring = true).assertIsDisplayed()
    }
}
