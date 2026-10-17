package com.harsh.peakbud

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import androidx.activity.compose.setContent
import com.harsh.peakbud.device.*
import com.harsh.peakbud.feature.panorama.PeakBudApp
import com.harsh.peakbud.ui.theme.PeakBudTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emptyFlow
import org.junit.rules.ExternalResource
import org.junit.Rule
import org.junit.Test

/** Run on a clean emulator; this deliberately does not require network or sensors. */
class PanoramaNavigationTest {
    @get:Rule(order = 0) val locationPermission = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val name = instrumentation.targetContext.packageName
            listOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION").forEach { permission ->
                instrumentation.uiAutomation.executeShellCommand("pm grant $name $permission").use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                }
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Before fun stableSensorReadings() {
        val readings = object : DeviceReadings {
            override fun poses(displayRotation: Int) = flowOf(DevicePose(90.0, 0.0, true, source = "Test magnetometer"))
            override fun fixes() = emptyFlow<LocationStatus>()
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent { PeakBudTheme { PeakBudApp(readings = readings) } }
        }
    }
    private fun tap(text: String) { compose.onNodeWithText(text).performClick() }

    @Test fun cameraIsDefaultAndPanoramaRemainsAvailable() {
        compose.onNodeWithText("Live camera").assertIsDisplayed()
        compose.onNodeWithText("Align labels").assertIsDisplayed()
        tap("Panorama")
        compose.onNodeWithText("Viewpoint").assertIsDisplayed()
        tap("Live camera")
        compose.onNodeWithText("Align labels").assertIsDisplayed()
    }

    @Test fun manualDemoAndOfflineRegionEntryPointsRemainAvailable() {
        compose.onNodeWithText("Find your horizon.").assertIsDisplayed()
        tap("Peaks")
        compose.onNodeWithText("Know your mountains.").assertIsDisplayed()
        tap("Regions")
        compose.onNodeWithText("Choose your hiking area").assertIsDisplayed()
        compose.onNodeWithText("Poon Hill · Annapurna").assertIsDisplayed()
        compose.onNodeWithText("Import region ZIP").assertDoesNotExist()
        compose.onNodeWithText("Download from URL").assertDoesNotExist()
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
        tap("Viewpoint")
        compose.onNodeWithText("Choose your viewpoint").assertIsDisplayed()
        tap("Cancel")
        tap("Settings")
        compose.onNodeWithText("Trail settings").assertIsDisplayed()
    }
}
